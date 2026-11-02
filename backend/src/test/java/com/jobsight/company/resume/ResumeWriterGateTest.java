package com.jobsight.company.resume;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이력서 content 는 ResumeWriter 하나로만 쓴다(설계 6.2, R40). JSON 과 resume_experiences 두 곳에 쓰므로 관문을 우회한 저장 하나가
 * 둘을 어긋나게 한다. ArchUnit 의존성을 들이지 않고 소스를 직접 검사한다: ResumeRepository 를 주입받은 클래스가
 * 그 필드로 save* 를 부르면 실패한다. Resume.update 는 패키지 전용이라 다른 패키지는 content 를 바꿀 수도 없다.
 */
class ResumeWriterGateTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern FIELD = Pattern.compile("ResumeRepository\\s+(\\w+)\\s*[;,)=]");

    static List<String> bypasses(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("ResumeWriter.java")) continue;
                String source = Files.readString(file);
                Matcher field = FIELD.matcher(source);
                java.util.Set<String> names = new java.util.TreeSet<>();
                while (field.find()) names.add(field.group(1));
                for (String name : names) {
                    if (Pattern.compile("\\b" + name + "\\s*\\.\\s*save\\w*\\s*\\(").matcher(source).find()) {
                        found.add(root.relativize(file) + " → " + name + ".save*");
                    }
                }
            }
        }
        return found;
    }

    @Test
    void onlyResumeWriterSavesResumes() throws IOException {
        assertThat(bypasses(MAIN)).isEmpty();
    }

    /** 검사가 실제로 우회를 잡는지: 우회하는 소스를 만들어 넣으면 걸린다. */
    @Test
    void gateCatchesABypass(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("Bypass.java"), """
                class Bypass {
                    private final ResumeRepository resumes;
                    Bypass(ResumeRepository resumes) { this.resumes = resumes; }
                    void sneak(Resume r) { resumes.saveAndFlush(r); }
                }
                """);
        assertThat(bypasses(temp)).singleElement().asString().contains("resumes.save*");
    }
}
