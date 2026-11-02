package com.jobsight.company.experience;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.OwnerLock;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.ExperienceRevisions.ChangeKind;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 경험 원본 쓰기 — 설계 5.2 experience_save, 6.4 삭제, 6.5 개정 판정. 모든 쓰기는 OwnerLock(T9) 아래에서 한다.
 * 출처는 요청 주체가 정한다: 웹 USER(검토 확정까지 함께), MCP AI('AI 기록').
 */
@Service
@Transactional(readOnly = true)
public class ExperienceBankService {
    /** 경험 단독 삭제가 영향받는 지연 FK 셋. 삭제 전에 당겨 위반을 이 문장에서 낸다(2.3, R15). */
    private static final String IMMEDIATE_EXPERIENCE_FKS = "set constraints resume_experiences_experience_fk, "
            + "resume_experiences_revision_fk, self_introduction_experiences_revision_fk immediate";

    private final ExperienceRepository repository;
    private final ExperienceRevisions revisions;
    private final ExperienceService views;
    private final OwnerLock ownerLock;
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    public ExperienceBankService(ExperienceRepository repository, ExperienceRevisions revisions, ExperienceService views,
                                 OwnerLock ownerLock, JdbcTemplate jdbc, CurrentUser currentUser, ObjectMapper mapper) {
        this.repository = repository;
        this.revisions = revisions;
        this.views = views;
        this.ownerLock = ownerLock;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /**
     * id 가 null 이면 생성(kind·title 필수, 개정 1 INIT), 아니면 수정(null=유지). 기존 경험에 facts·dossier 를 보내면 expectedVersion 필수.
     * facts 해시가 직전 개정과 같으면 개정을 만들지 않는다(NONE) — 소재집·메모·skills·domains 만 바뀐 저장이 여기다.
     */
    @Transactional
    public ExperienceViews.Saved save(UUID id, ExperienceRequests.Save request) {
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        LinkSource source = currentUser.viaMcp() ? LinkSource.AI : LinkSource.USER;
        List<String> skills = keys(request.skills());
        List<String> domains = keys(request.domains());
        return id == null ? create(owner, request, source, skills, domains)
                : update(owner, id, request, source, skills, domains);
    }

    private ExperienceViews.Saved create(UUID owner, ExperienceRequests.Save request, LinkSource source,
                                         List<String> skills, List<String> domains) {
        if (request.kind() == null || request.title() == null || request.title().isBlank()) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "KIND_TITLE_REQUIRED", "새 경험은 kind 와 title 이 필요합니다.");
        }
        ExperienceFacts facts = normalize(request.facts(), null);
        Experience experience = new Experience(owner, request.kind(), request.title(), request.startYm(), json(facts));
        experience.describe(request.memo(), skills, domains);
        if (request.dossier() != null) experience.writeDossier(json(request.dossier()));
        if (source == LinkSource.USER) experience.markReviewed();
        Experience saved = repository.saveAndFlush(experience);
        revisions.insert(saved, ChangeKind.INIT, hash(facts), source);
        return new ExperienceViews.Saved(saved.getId(), saved.getVersion(), saved.getRevision(), ChangeKind.INIT.name(),
                List.of(), skills, List.of());
    }

    private ExperienceViews.Saved update(UUID owner, UUID id, ExperienceRequests.Save request, LinkSource source,
                                         List<String> skills, List<String> domains) {
        Experience experience = repository.findByIdAndOwnerId(id, owner).orElseThrow(() -> new ResourceNotFoundException(id));
        Long expected = request.expectedVersion();
        if (expected == null && (request.facts() != null || request.dossier() != null)) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED",
                    "사실·소재집을 고치려면 expectedVersion 이 필요합니다. experience_index 상세로 다시 읽고 그 version 을 보내세요.");
        }
        if (expected != null && expected != experience.getVersion()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "EXPERIENCE_STALE",
                    "다른 곳에서 먼저 저장했습니다. 경험을 다시 읽고 바뀐 사실을 확인한 뒤 보내세요.");
        }
        if (request.kind() != null && request.kind() != experience.getKind() && inResumes(owner, id)) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "EXPERIENCE_IN_USE",
                    "이력서 행이 쓰고 있는 경험은 종류를 바꿀 수 없습니다. 행을 다른 경험에 연결한 뒤 바꾸세요.");
        }
        if (request.kind() != null || request.title() != null || request.startYm() != null) {
            experience.rename(request.kind() != null ? request.kind() : experience.getKind(),
                    request.title() != null && !request.title().isBlank() ? request.title() : experience.getTitle(),
                    request.startYm() != null ? request.startYm() : experience.getStartYm());
        }
        experience.describe(request.memo() != null ? request.memo() : experience.getMemo(),
                request.skills() != null ? skills : experience.getSkills(),
                request.domains() != null ? domains : experience.getDomains());

        ChangeKind change = ChangeKind.NONE;
        String hash = null;
        if (request.facts() != null) {
            ExperienceFacts previous = views.facts(experience);
            ExperienceFacts next = normalize(request.facts(), previous);
            hash = hash(next);
            if (!hash.equals(revisions.latestHash(experience))) {
                change = changeKind(previous, next);
                experience.revise(json(next));
            }
        }
        if (request.dossier() != null) experience.writeDossier(json(request.dossier()));
        if (source == LinkSource.USER) experience.markReviewed();
        experience.touchSearchText();
        Experience saved = repository.saveAndFlush(experience);
        List<ExperienceViews.NeedsReview> needsReview = List.of();
        if (change != ChangeKind.NONE) {
            revisions.insert(saved, change, hash, source);
            if (change == ChangeKind.CORRECT) needsReview = needsReview(owner, id, saved.getRevision());
        }
        return new ExperienceViews.Saved(saved.getId(), saved.getVersion(), saved.getRevision(), change.name(),
                needsReview, saved.getSkills(), List.of());
    }

    /** 웹 검토 확정: 화면이 보고 있던 개정이어야 한다. 그사이 개정이 올랐으면 409 EXPERIENCE_STALE. */
    @Transactional
    public ExperienceViews.Detail review(UUID id, ExperienceRequests.Review request) {
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        Experience experience = repository.findByIdAndOwnerId(id, owner).orElseThrow(() -> new ResourceNotFoundException(id));
        if (request.revision() != experience.getRevision()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "EXPERIENCE_STALE",
                    "검토하던 사이에 경험이 다시 개정됐습니다. 다시 읽고 검토하세요.");
        }
        experience.markReviewed();
        repository.saveAndFlush(experience);
        return views.detail(id);
    }

    /**
     * 경험 삭제(웹 전용, 6.4). T9 잠금 아래에서 사용 중인지 먼저 본다: 이력서가 쓰면 409 EXPERIENCE_IN_USE(이력서 이름),
     * 자소서 작성 근거가 가리키면 detach 없이는 409(자소서 질문), detach 면 근거를 먼저 끊는다.
     * 그다음 지연 FK 셋을 IMMEDIATE 로 당기고 지운다 — 잠금을 거치지 않은 경로가 그사이 연결했어도 이 문장에서 막힌다.
     */
    @Transactional
    public ExperienceViews.Deleted delete(UUID id, boolean detach) {
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        Experience experience = repository.findByIdAndOwnerId(id, owner).orElseThrow(() -> new ResourceNotFoundException(id));
        List<String> resumes = jdbc.queryForList("select distinct r.name from resume_experiences re join resumes r "
                + "on r.id = re.resume_id and r.owner_id = re.owner_id where re.owner_id = ? and re.experience_id = ? "
                + "order by r.name", String.class, owner, id);
        if (!resumes.isEmpty()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "EXPERIENCE_IN_USE",
                    "이력서가 쓰고 있는 경험입니다: " + resumes + ". 그 행을 지우거나 다른 경험에 연결한 뒤 지우세요.");
        }
        List<String> intros = jdbc.queryForList("select s.question from self_introduction_experiences se join self_introductions s "
                + "on s.id = se.self_introduction_id and s.owner_id = se.owner_id where se.owner_id = ? and se.experience_id = ? "
                + "order by s.question", String.class, owner, id);
        int detached = 0;
        if (!intros.isEmpty()) {
            if (!detach) {
                throw new ApiRuleException(HttpStatus.CONFLICT, "EXPERIENCE_IN_USE",
                        "자기소개 작성 근거로 쓰인 경험입니다: " + intros + ". 근거를 끊고 지우려면 detach=true 로 보내세요.");
            }
            detached = jdbc.update("delete from self_introduction_experiences where owner_id = ? and experience_id = ?", owner, id);
        }
        jdbc.execute(IMMEDIATE_EXPERIENCE_FKS);
        repository.delete(experience);
        repository.flush();
        return new ExperienceViews.Deleted(id, detached);
    }

    private boolean inResumes(UUID owner, UUID id) {
        Integer count = jdbc.queryForObject("select count(*) from resume_experiences where owner_id = ? and experience_id = ?",
                Integer.class, owner, id);
        return count != null && count > 0;
    }

    /** 정정 개정으로 재검토가 필요해진 이력서·자소서: 새 개정보다 낮은 개정을 참조한다. */
    private List<ExperienceViews.NeedsReview> needsReview(UUID owner, UUID id, int revision) {
        List<ExperienceViews.NeedsReview> found = new ArrayList<>();
        jdbc.query("select r.id, r.name, re.based_on_revision from resume_experiences re join resumes r on r.id = re.resume_id "
                        + "and r.owner_id = re.owner_id where re.owner_id = ? and re.based_on_experience_id = ? "
                        + "and re.based_on_revision < ? order by r.name",
                rs -> { found.add(new ExperienceViews.NeedsReview("RESUME", rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getInt(3))); }, owner, id, revision);
        jdbc.query("select s.id, s.question, se.experience_revision from self_introduction_experiences se "
                        + "join self_introductions s on s.id = se.self_introduction_id and s.owner_id = se.owner_id "
                        + "where se.owner_id = ? and se.experience_id = ? and se.experience_revision < ? order by s.question",
                rs -> { found.add(new ExperienceViews.NeedsReview("SELF_INTRO", rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getInt(3))); }, owner, id, revision);
        return found;
    }

    /**
     * 서버 판정(6.5, R39). 주장 칸(period·role·measurement·actions·results)을 직전 개정과 비교한다.
     * 배열은 id 로 짝짓는다: 새 id 만 늘면 ADD, 기존 id 의 값이 바뀌거나 사라지면 CORRECT. 단일 값은 비었던 칸을 채우면 ADD,
     * 있던 값을 바꾸거나 지우면 CORRECT. 근거·미확인·충돌 칸만 바뀌었으면 ADD 다 — 주장은 그대로라 기업용 문구의 재검토가 필요 없다.
     */
    static ChangeKind changeKind(ExperienceFacts previous, ExperienceFacts next) {
        boolean correct = corrected(previous.period(), next.period()) || corrected(previous.role(), next.role())
                || corrected(previous.measurement(), next.measurement())
                || corrected(previous.actions(), next.actions()) || corrected(previous.results(), next.results());
        return correct ? ChangeKind.CORRECT : ChangeKind.ADD;
    }

    private static boolean corrected(Object before, Object after) {
        return !blank(before) && !Objects.equals(before, after);
    }

    private static boolean corrected(List<ExperienceFacts.Item> before, List<ExperienceFacts.Item> after) {
        Map<String, String> next = after.stream().collect(Collectors.toMap(ExperienceFacts.Item::id, ExperienceFacts.Item::text));
        return before.stream().anyMatch(item -> !item.text().equals(next.get(item.id())));
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof String text && text.isBlank())
                || (value instanceof ExperienceFacts.Measurement m && blank(m.environment()) && blank(m.scope()));
    }

    /**
     * 빈 배열을 [] 로, id 없는 항목에 경험 안에서 유일한 id 를 붙인다. 같은 id 두 번, 없는 근거를 가리키는 주장,
     * 로컬 경로만 있는 근거는 400 이다. previous 의 id 와 겹치지 않게 접두어 + 순번으로 붙인다.
     */
    ExperienceFacts normalize(ExperienceFacts facts, ExperienceFacts previous) {
        ExperienceFacts value = facts == null ? new ExperienceFacts(null, null, null, null, null, null, null, null) : facts;
        Set<String> used = new HashSet<>();
        if (previous != null) {
            Stream.of(previous.actions(), previous.results(), previous.unverified(), previous.conflicts())
                    .filter(Objects::nonNull).flatMap(List::stream).map(ExperienceFacts.Item::id).forEach(used::add);
            if (previous.evidence() != null) previous.evidence().forEach(ev -> used.add(ev.id()));
        }
        Set<String> seen = new HashSet<>();
        List<ExperienceFacts.Evidence> evidence = new ArrayList<>();
        for (ExperienceFacts.Evidence ev : orEmpty(value.evidence())) {
            checkEvidence(ev);
            String evidenceId = unique(ev.id(), "ev", seen, used);
            evidence.add(new ExperienceFacts.Evidence(evidenceId, ev.sourceType(), ev.ref(), ev.quote().trim(), ev.context(),
                    ev.location(), ev.hash(), ev.capturedAt()));
        }
        Set<String> evidenceIds = evidence.stream().map(ExperienceFacts.Evidence::id).collect(Collectors.toSet());
        return new ExperienceFacts(trim(value.period()), trim(value.role()), value.measurement(),
                items(value.actions(), "a", seen, used, evidenceIds), items(value.results(), "r", seen, used, evidenceIds),
                evidence, items(value.unverified(), "u", seen, used, evidenceIds),
                items(value.conflicts(), "c", seen, used, evidenceIds));
    }

    private static List<ExperienceFacts.Item> items(List<ExperienceFacts.Item> list, String prefix, Set<String> seen,
                                                    Set<String> used, Set<String> evidenceIds) {
        List<ExperienceFacts.Item> items = new ArrayList<>();
        for (ExperienceFacts.Item item : orEmpty(list)) {
            List<String> refs = orEmpty(item.evidenceIds());
            String unknown = refs.stream().filter(ref -> !evidenceIds.contains(ref)).findFirst().orElse(null);
            if (unknown != null) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "UNKNOWN_EVIDENCE",
                        "주장 '" + item.text() + "' 이 없는 근거 id(" + unknown + ")를 가리킵니다. evidence[] 에 그 id 를 함께 보내세요.");
            }
            items.add(new ExperienceFacts.Item(unique(item.id(), prefix, seen, used), item.text().trim(), List.copyOf(refs)));
        }
        return items;
    }

    private static String unique(String requested, String prefix, Set<String> seen, Set<String> used) {
        if (requested != null && !requested.isBlank()) {
            if (!seen.add(requested)) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "FACT_ID_DUPLICATE", "사실 항목 id 가 겹칩니다: " + requested);
            }
            return requested;
        }
        for (int n = 1; ; n++) {
            String candidate = prefix + n;
            if (!used.contains(candidate) && seen.add(candidate)) return candidate;
        }
    }

    /** 다른 PC 에서 열 수 없는 로컬 경로만 있는 근거는 받지 않는다(6.5). 근거 종류마다 가리킬 것이 있어야 한다. */
    private static void checkEvidence(ExperienceFacts.Evidence ev) {
        ExperienceFacts.Ref ref = ev.ref();
        String url = ref == null ? null : ref.url();
        if (url != null && !url.startsWith("https://")) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EVIDENCE_LOCAL_PATH",
                    "근거 주소는 https 여야 합니다(로컬 경로는 다른 환경에서 열 수 없음): " + url + ". 필요한 발췌를 quote·context 에 넣으세요.");
        }
        boolean pointed = switch (ev.sourceType()) {
            case RESUME_ROW -> ref != null && ref.resumeId() != null && ref.section() != null && ref.index() != null;
            case ATTACHMENT -> ref != null && ref.attachmentId() != null;
            case URL -> url != null;
            case NOTE -> true;
        };
        if (!pointed) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE",
                    ev.sourceType() + " 근거에는 ref 가 필요합니다(RESUME_ROW: resumeId·section·index, ATTACHMENT: attachmentId, URL: https url).");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 기술·도메인 이름 → 정규화 키(KeyNormalizer.skill), 순서 유지·중복 없음. */
    static List<String> keys(List<String> names) {
        if (names == null) return List.of();
        Set<String> keys = new LinkedHashSet<>();
        names.stream().map(KeyNormalizer::skill).filter(key -> !key.isEmpty()).forEach(keys::add);
        return List.copyOf(keys);
    }

    String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** 정규화한 facts 의 SHA-256. record 순서로 직렬화하므로 같은 내용이면 같은 문자열이다. */
    String hash(ExperienceFacts facts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
