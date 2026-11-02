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
    private static final int QUOTE = 1000;

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
                    group.rows().stream().map(ExperienceService::source).toList(), candidates));
        }
        return new ExperienceViews.ImportPlan(planned, versions);
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
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        resumes.setLocalLockTimeout();
        List<Resume> locked = resumes.findAllOwnedForUpdate(owner);
        Map<UUID, Resume> byId = new LinkedHashMap<>();
        locked.forEach(resume -> byId.put(resume.getId(), resume));
        List<RowGroups.Doc> docs = locked.stream().map(views::doc).toList();
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

    /** 구성 행들로 만든 사실 초안. 행끼리 다른 기간·역할은 고르지 않고 conflicts 에 적는다. 주장마다 그 행 인용을 근거로 단다. */
    private Experience draft(UUID owner, RowGroups.Group group, Map<UUID, Resume> byId) {
        List<ExperienceFacts.Evidence> evidence = new ArrayList<>();
        List<ExperienceFacts.Item> actions = new ArrayList<>();
        List<ExperienceFacts.Item> results = new ArrayList<>();
        Set<String> periods = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        Set<String> skills = new LinkedHashSet<>();
        String captured = Instant.now().toString();
        for (RowGroups.Row row : group.rows()) {
            String evidenceId = "ev" + (evidence.size() + 1);
            String text = row.text();
            evidence.add(new ExperienceFacts.Evidence(evidenceId, ExperienceFacts.SourceType.RESUME_ROW,
                    new ExperienceFacts.Ref(row.resume().id(), row.section(), row.index(), row.resume().version(), null, null),
                    text.length() <= QUOTE ? text : text.substring(0, QUOTE), null,
                    row.resume().name() + " · " + row.section() + "[" + row.index() + "]", sha256(text), captured));
            if (!row.period().isEmpty()) periods.add(row.period());
            skills.addAll(KeyNormalizer.skills(row.techStack()));
            Parts parts = parts(row);
            if (parts.role() != null) roles.add(parts.role());
            add(actions, "a", parts.action(), evidenceId);
            add(results, "r", parts.result(), evidenceId);
        }
        List<ExperienceFacts.Item> conflicts = new ArrayList<>();
        if (periods.size() > 1) add(conflicts, "c", "이력서마다 기간이 다르다: " + String.join(" / ", periods), null);
        if (roles.size() > 1) add(conflicts, "c", "이력서마다 역할이 다르다: " + String.join(" / ", roles), null);
        ExperienceFacts facts = new ExperienceFacts(periods.stream().findFirst().orElse(null),
                roles.stream().findFirst().orElse(null), null, actions, results, evidence, List.of(), conflicts);
        RowGroups.Row first = group.rows().get(0);
        Experience experience = new Experience(owner, group.kind(), group.title(), first.startYm(), bank.json(facts));
        experience.describe(null, List.copyOf(skills), List.of());
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

    private static void add(List<ExperienceFacts.Item> items, String prefix, String text, String evidenceId) {
        if (text == null) return;
        if (items.stream().anyMatch(item -> item.text().equals(text))) return;
        items.add(new ExperienceFacts.Item(prefix + (items.size() + 1), text,
                evidenceId == null ? List.of() : List.of(evidenceId)));
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
