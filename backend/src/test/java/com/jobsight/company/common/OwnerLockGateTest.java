package com.jobsight.company.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9 잠금 규약(설계 1.4): 경험 관계를 바꾸는 서비스의 쓰기 트랜잭션(@Transactional, readOnly 아님)은 모두 OwnerLock 을 잡는다.
 * 한 곳이라도 빠지면 경험 삭제의 사용 중 확인과 이력서 저장이 엇갈린다. ResumeWriterGateTest 처럼 소스를 직접 검사한다:
 * 메서드 본문이 ownerLock.acquire 를 부르거나, 그것을 부르는 같은 클래스 메서드(mutateRows 등)를 부르면 통과다.
 */
class OwnerLockGateTest {
    private static final Path MAIN = Path.of("src/main/java/com/jobsight/company");
    private static final List<String> SERVICES = List.of("resume/ResumeService.java", "selfintro/SelfIntroductionService.java",
            "experience/ExperienceBankService.java", "experience/ExperienceImportService.java", "submission/SubmissionService.java");
    /**
     * 예외: 자소서 확정은 직무·문항 유형만 바꾸고, 자소서 삭제는 작성 근거를 줄이기만 한다(CASCADE) — 경험 쪽 확인과 엇갈려도
     * 없는 연결을 만들지 않는다.
     */
    private static final Set<String> EXEMPT = Set.of("SelfIntroductionService.confirm", "SelfIntroductionService.delete");

    private static final Pattern LITERALS = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern HEADER = Pattern.compile("\\b(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws[^{;]*)?\\{");
    private static final Pattern WRITE = Pattern.compile("@Transactional\\s+public[^(]*?\\b(\\w+)\\s*\\(");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "new");

    static List<String> unlocked(Path file) throws IOException {
        String source = LITERALS.matcher(Files.readString(file)).replaceAll("\"\"");
        Map<String, StringBuilder> bodies = new HashMap<>();
        Matcher header = HEADER.matcher(source);
        while (header.find()) {
            if (KEYWORDS.contains(header.group(1))) continue;
            int start = header.end() - 1;
            int depth = 0;
            int end = start;
            for (; end < source.length(); end++) {
                if (source.charAt(end) == '{') depth++;
                else if (source.charAt(end) == '}' && --depth == 0) break;
            }
            bodies.computeIfAbsent(header.group(1), name -> new StringBuilder()).append(source, start, end);
        }
        Set<String> locked = new HashSet<>();
        bodies.forEach((name, body) -> { if (body.indexOf("ownerLock.acquire(") >= 0) locked.add(name); });
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Map.Entry<String, StringBuilder> entry : bodies.entrySet()) {
                if (locked.contains(entry.getKey())) continue;
                boolean callsLocked = locked.stream().anyMatch(name ->
                        Pattern.compile("\\b" + name + "\\s*\\(").matcher(entry.getValue()).find());
                if (callsLocked) grew = locked.add(entry.getKey());
            }
        }
        String type = file.getFileName().toString().replace(".java", "");
        List<String> missing = new ArrayList<>();
        Matcher write = WRITE.matcher(source);
        while (write.find()) {
            String name = type + "." + write.group(1);
            if (!locked.contains(write.group(1)) && !EXEMPT.contains(name)) missing.add(name);
        }
        return missing;
    }

    @Test
    void everyRelationWriteTakesTheOwnerLock() throws IOException {
        for (String service : SERVICES) {
            assertThat(unlocked(MAIN.resolve(service))).as(service).isEmpty();
        }
    }

    /** 검사가 실제로 빠진 잠금을 잡는지: 위임으로 잠그는 메서드는 통과, 잠그지 않는 쓰기는 걸린다. */
    @Test
    void gateCatchesAMissingLock(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("Sneaky.java");
        Files.writeString(file, """
                class Sneaky {
                    @Transactional
                    public void viaHelper(UUID id) { helper(id, rows -> { rows.clear(); }); }
                    @Transactional
                    public void forgot(UUID id) { if (id != null) { repository.delete("ownerLock.acquire("); } }
                    private void helper(UUID id, Consumer<List<String>> change) { ownerLock.acquire(id); }
                    @Transactional(readOnly = true)
                    public void read() { }
                }
                """);
        assertThat(unlocked(file)).containsExactly("Sneaky.forgot");
    }
}
