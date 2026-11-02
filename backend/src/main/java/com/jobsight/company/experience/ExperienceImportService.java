package com.jobsight.company.experience;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.OwnerLock;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.resume.ResumeWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 기존 이력서 행에서 경험 원본 만들기(웹 기능, 마이그레이션 SQL 아님) — 설계 6.3. 같은 경험인지는 사람이 확인한다.
 * 계획은 미연결 행 묶음(RowGroups.unlinked, 3단계 묶음 함수)이고, 적용은 묶음마다 CREATE·SKIP·LINK_TO 다.
 * 적용은 행 텍스트를 바꾸지 않고 앵커만 쓴다. 이미 앵커가 있는 행은 계획에 없으므로 여러 번 돌려도 결과가 같다.
 */
@Service
@Transactional(readOnly = true)
public class ExperienceImportService {
    /** ExperienceFacts 의 상한: 인용 1000자, 항목 문구 2000자, 기간·위치 200자, 배열 50개. */
    private static final int QUOTE = 1000;
    private static final int TEXT = 2000;
    private static final int SHORT = 200;
    private static final int MAX_ITEMS = 50;
    /** ExperienceRequests.Save 의 skills 상한: 50개, 각 100자. */
    private static final int MAX_SKILLS = 50;
    private static final int MAX_SKILL_CHARS = 100;

    private final ExperienceService views;
    private final ExperienceBankService bank;
    private final ExperienceRepository experiences;
    private final ExperienceRevisions revisions;
    private final ResumeRepository resumes;
    private final ResumeWriter writer;
    private final OwnerLock ownerLock;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    public ExperienceImportService(ExperienceService views, ExperienceBankService bank, ExperienceRepository experiences,
                                   ExperienceRevisions revisions, ResumeRepository resumes, ResumeWriter writer,
                                   OwnerLock ownerLock, CurrentUser currentUser, ObjectMapper mapper) {
        this.views = views;
        this.bank = bank;
        this.experiences = experiences;
        this.revisions = revisions;
        this.resumes = resumes;
        this.writer = writer;
        this.ownerLock = ownerLock;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /** dry-run. 묶음과 구성 행, 같은 경험일 수 있는 다른 묶음(mergeCandidates), 관련 이력서 version. */
    public ExperienceViews.ImportPlan plan() {
        return plan(views.docs());
    }

    private static ExperienceViews.ImportPlan plan(List<RowGroups.Doc> docs) {
        List<RowGroups.Group> groups = RowGroups.unlinked(docs);
        Map<UUID, Long> versions = new LinkedHashMap<>();
        List<ExperienceViews.ImportGroup> planned = new ArrayList<>();
        for (RowGroups.Group group : groups) {
            group.rows().forEach(row -> versions.put(row.resume().id(), row.resume().version()));
            List<String> candidates = groups.stream().filter(other -> other != group && mayBeSame(group, other))
                    .map(RowGroups.Group::key).toList();
            planned.add(new ExperienceViews.ImportGroup(group.key(), group.kind(), group.title(), group.period(),
                    group.rows().stream().map(ExperienceService::source).toList(), candidates, sameResumeWarning(group)));
        }
        return new ExperienceViews.ImportPlan(planned, versions);
    }

    /**
     * 한 이력서의 두 행이 같은 묶음이면 그 묶음은 CREATE·LINK_TO 할 수 없다 — 한 이력서에서 같은 경험을 두 행에 걸 수 없다
     * (링커 EXPERIENCE_ROW_DUPLICATE, 설계 6.2-4). 행을 골라 잇는 기능은 없으므로 SKIP 하거나 이력서에서 행을 정리하게 안내한다.
     */
    private static String sameResumeWarning(RowGroups.Group group) {
        Set<UUID> seen = new HashSet<>();
        for (RowGroups.Row row : group.rows()) {
            if (!seen.add(row.resume().id())) {
                return "이력서 '" + row.resume().name() + "'에 이 묶음의 행이 둘 이상 있어 한 경험으로 이을 수 없습니다. "
                        + "건너뛰거나(SKIP), 이력서에서 행을 합치거나 제목·시작 연월을 구분한 뒤 계획을 다시 만드세요.";
            }
        }
        return null;
    }

    /** 제목 키가 서로 포함 관계이거나, 기간 숫자는 같은데 제목이 다르면 같은 경험일 수 있다. 자동으로 합치지 않는다. */
    private static boolean mayBeSame(RowGroups.Group a, RowGroups.Group b) {
        if (a.kind() != b.kind()) return false;
        String ka = KeyNormalizer.key(a.title());
        String kb = KeyNormalizer.key(b.title());
        String ya = KeyNormalizer.yearMonth(a.rows().get(0).startYm());
        String yb = KeyNormalizer.yearMonth(b.rows().get(0).startYm());
        return ka.contains(kb) || kb.contains(ka) || (!ya.isEmpty() && ya.equals(yb));
    }

    /**
     * 적용. T9 잠금 뒤 계정의 이력서를 id 오름차순으로 잠그고, 계획의 version 과 다른 이력서가 있으면 409 IMPORT_PLAN_STALE.
     * CREATE: 정체성·skills·사실 초안(구성 행 인용을 근거로, 행끼리 다른 기간·역할은 conflicts)으로 개정 1(INIT, 'AI 기록')을 만든다.
     * LINK_TO: 그 경험의 지금 개정으로 잇는다. 행은 ResumeWriter 로 쓴다.
     */
    @Transactional
    public ExperienceViews.ImportResult apply(ExperienceRequests.Import request) {
        // 묶음 하나에 결정은 하나. 둘이면 앞 결정이 만든 경험이 어디에도 연결되지 않은 채 남는다(이중 제출·재시도).
        Set<String> keys = new HashSet<>();
        for (ExperienceRequests.ImportDecision decision : request.decisions()) {
            if (!keys.add(decision.key())) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "DUPLICATE_DECISION",
                        "같은 묶음에 결정이 둘 이상입니다: " + decision.key() + ". 묶음마다 결정을 하나만 보내세요.");
            }
        }
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        resumes.setLocalLockTimeout();
        List<Resume> locked = resumes.findAllOwnedForUpdate(owner);
        Map<UUID, Resume> byId = new LinkedHashMap<>();
        locked.forEach(resume -> byId.put(resume.getId(), resume));
        // 잠금은 id 순, 묶음은 계획(views.docs)과 같은 순서 — 대표 행(제목·기간·50행 상한의 앞쪽)이 미리 보기와 같아야 한다.
        List<RowGroups.Doc> docs = locked.stream().sorted(Comparator.comparing(Resume::getUpdatedAt).reversed())
                .map(views::doc).toList();
        Map<String, RowGroups.Group> groups = new LinkedHashMap<>();
        RowGroups.unlinked(docs).forEach(group -> groups.put(group.key(), group));

        Map<UUID, ObjectNode> edited = new LinkedHashMap<>();
        List<Experience> created = new ArrayList<>();
        int linked = 0;
        int skipped = 0;
        for (ExperienceRequests.ImportDecision decision : request.decisions()) {
            RowGroups.Group group = groups.get(decision.key());
            if (group == null || group.rows().stream().anyMatch(row -> !Objects.equals(
                    request.resumeVersions().get(row.resume().id()), byId.get(row.resume().id()).getVersion()))) {
                throw new ApiRuleException(HttpStatus.CONFLICT, "IMPORT_PLAN_STALE",
                        "계획 뒤에 이력서가 바뀌었습니다. 가져오기 계획을 다시 만들어 주세요.");
            }
            String warning = decision.action() == ExperienceRequests.ImportAction.SKIP ? null : sameResumeWarning(group);
            if (warning != null) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "IMPORT_GROUP_SAME_RESUME", warning);
            }
            UUID experienceId;
            int revision;
            switch (decision.action()) {
                case SKIP -> {
                    skipped++;
                    continue;
                }
                case CREATE -> {
                    Experience experience = draft(owner, group, byId);
                    created.add(experience);
                    experienceId = experience.getId();
                    revision = 1;
                }
                case LINK_TO -> {
                    if (decision.experienceId() == null) {
                        throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_REFERENCE_REQUIRED",
                                "LINK_TO 에는 experienceId 가 필요합니다.");
                    }
                    Experience target = experiences.findByIdAndOwnerId(decision.experienceId(), owner)
                            .orElseThrow(() -> new ResourceNotFoundException(decision.experienceId()));
                    if (target.getKind() != group.kind()) {
                        throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_SECTION_MISMATCH",
                                group.kind() + " 묶음은 " + target.getKind() + " 경험에 이을 수 없습니다.");
                    }
                    experienceId = target.getId();
                    revision = target.getRevision();
                }
                default -> throw new IllegalStateException();
            }
            for (RowGroups.Row row : group.rows()) {
                ObjectNode root = edited.computeIfAbsent(row.resume().id(),
                        id -> mapper.valueToTree(row.resume().content()));
                ObjectNode node = (ObjectNode) root.get(row.section()).get(row.index());
                node.put("experienceId", experienceId.toString());
                node.put("experienceRevision", revision);
                linked++;
            }
        }
        // 대량 insert: Persistable 이라 merge(SELECT) 없이 persist 된다(R48). 개정 행은 경험 행 flush 뒤에 넣는다.
        experiences.saveAllAndFlush(created);
        for (Experience experience : created) {
            revisions.insert(experience, ExperienceRevisions.ChangeKind.INIT,
                    bank.hash(mapper.readValue(experience.getFacts(), ExperienceFacts.class)), LinkSource.AI);
        }
        for (Map.Entry<UUID, ObjectNode> entry : edited.entrySet()) {
            Resume resume = byId.get(entry.getKey());
            ResumeContent before = docs.stream().filter(doc -> doc.id().equals(resume.getId())).findFirst()
                    .orElseThrow().content();
            writer.write(resume, resume.getName(), before, mapper.treeToValue(entry.getValue(), ResumeContent.class),
                    Set.of(), null);
        }
        return new ExperienceViews.ImportResult(created.size(), linked, skipped,
                created.stream().map(Experience::getId).toList());
    }

    /**
     * 구성 행들로 만든 사실 초안. 행끼리 다른 기간·역할은 고르지 않고 conflicts 에 적는다. 주장마다 그 행 인용을 근거로 단다.
     * 이력서 칸(설명 5000자)이 사실 상한(항목 2000자·배열 50개·기간 200자)보다 커서, 긴 설명은 2000자 항목 여러 개로 나누고
     * 50개를 넘어 담지 못한 몫이 있으면 unverified 에 적는다. 마지막에 저장 경로와 같은 normalize 를 거친다 — 상세를 읽어
     * 그대로 다시 보내는 저장이 검증(@Valid)에 막히거나 공백 차이로 해시가 달라져 개정이 생기지 않게.
     */
    private Experience draft(UUID owner, RowGroups.Group group, Map<UUID, Resume> byId) {
        List<ExperienceFacts.Evidence> evidence = new ArrayList<>();
        List<ExperienceFacts.Item> actions = new ArrayList<>();
        List<ExperienceFacts.Item> results = new ArrayList<>();
        Set<String> periods = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        Set<String> skills = new LinkedHashSet<>();
        String captured = Instant.now().toString();
        List<RowGroups.Row> rows = group.rows();
        boolean overflow = rows.size() > MAX_ITEMS;
        Set<String> actionTexts = new HashSet<>();
        Set<String> resultTexts = new HashSet<>();
        for (RowGroups.Row row : rows.subList(0, Math.min(rows.size(), MAX_ITEMS))) {
            String evidenceId = "ev" + (evidence.size() + 1);
            String text = row.text();
            evidence.add(new ExperienceFacts.Evidence(evidenceId, ExperienceFacts.SourceType.RESUME_ROW,
                    new ExperienceFacts.Ref(row.resume().id(), row.section(), row.index(), row.resume().version(), null, null),
                    clip(text, QUOTE), null,
                    clip(row.resume().name() + " · " + row.section() + "[" + row.index() + "]", SHORT), sha256(text), captured));
            if (!row.period().isEmpty()) periods.add(clip(row.period(), SHORT));
            skills.addAll(KeyNormalizer.skills(row.techStack()));
            Parts parts = parts(row);
            if (parts.role() != null) roles.add(clip(parts.role(), TEXT));
            // 같은 문구(행 전체)는 한 번만 담는다. 나눈 조각끼리는 같아도 지우지 않는다.
            if (actionTexts.add(parts.action())) overflow |= add(actions, "a", parts.action(), evidenceId);
            if (resultTexts.add(parts.result())) overflow |= add(results, "r", parts.result(), evidenceId);
        }
        List<ExperienceFacts.Item> conflicts = new ArrayList<>();
        if (periods.size() > 1) add(conflicts, "c", clip("이력서마다 기간이 다르다: " + String.join(" / ", periods), TEXT), null);
        if (roles.size() > 1) add(conflicts, "c", clip("이력서마다 역할이 다르다: " + String.join(" / ", roles), TEXT), null);
        List<ExperienceFacts.Item> unverified = new ArrayList<>();
        if (overflow) add(unverified, "u", "구성 행 " + rows.size() + "개의 내용이 초안 상한(배열 " + MAX_ITEMS
                + "개)을 넘어 일부만 담았다. 빠진 내용은 이력서 행에서 확인한다.", null);
        // skills 도 저장 검증(ExperienceRequests.Save: 50개·각 100자)을 지킨다. 넘으면 상세를 그대로 다시 저장할 수 없다.
        List<String> keptSkills = skills.stream().filter(skill -> skill.length() <= MAX_SKILL_CHARS).limit(MAX_SKILLS).toList();
        if (keptSkills.size() < skills.size()) {
            List<String> dropped = skills.stream().filter(skill -> !keptSkills.contains(skill)).toList();
            add(unverified, "u", clip("구성 행의 기술 " + dropped.size() + "개는 저장 상한(" + MAX_SKILLS + "개·" + MAX_SKILL_CHARS
                    + "자)을 넘어 skills 에 담지 않았다: " + String.join(", ", dropped), TEXT), null);
        }
        ExperienceFacts facts = bank.normalize(new ExperienceFacts(periods.stream().findFirst().orElse(null),
                roles.stream().findFirst().orElse(null), null, actions, results, evidence, unverified, conflicts), null);
        RowGroups.Row first = rows.get(0);
        Experience experience = new Experience(owner, group.kind(), group.title(), first.startYm(), bank.json(facts));
        experience.describe(null, keptSkills, List.of());
        return experience;
    }

    private record Parts(String role, String action, String result) {
    }

    /** 행의 역할·행동·결과 칸. 프로젝트만 칸이 나뉘어 있고, 나머지는 설명 한 칸이 행동이다. */
    private static Parts parts(RowGroups.Row row) {
        ResumeContent c = row.resume().content();
        return switch (row.section()) {
            case "projects" -> {
                ResumeContent.Project p = c.projects().get(row.index());
                yield new Parts(blank(p.role()), joined(p.summary(), p.description()), blank(p.outcome()));
            }
            case "experiences" -> new Parts(null, blank(c.experiences().get(row.index()).description()), null);
            case "activities" -> new Parts(null, blank(c.activities().get(row.index()).description()), null);
            default -> new Parts(null, blank(c.trainings().get(row.index()).description()), null);
        };
    }

    /** 항목 상한(2000자)에 맞춰 나눠 붙인다. 배열 상한(50개)에 걸려 버린 몫이 있으면 true. */
    private static boolean add(List<ExperienceFacts.Item> items, String prefix, String text, String evidenceId) {
        if (text == null) return false;
        for (int start = 0; start < text.length(); ) {
            String chunk = clip(text.substring(start), TEXT);
            start += chunk.length();
            if (chunk.isBlank()) continue;
            if (items.size() >= MAX_ITEMS) return true;
            items.add(new ExperienceFacts.Item(prefix + (items.size() + 1), chunk,
                    evidenceId == null ? List.of() : List.of(evidenceId)));
        }
        return false;
    }

    /** 앞에서 max 자까지. 서로게이트 쌍을 가르지 않는다. */
    private static String clip(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max);
    }

    private static String joined(String a, String b) {
        String joined = java.util.stream.Stream.of(a, b).map(ExperienceImportService::blank).filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
