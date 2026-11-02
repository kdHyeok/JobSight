package com.jobsight.company.experience;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.dto.ExperienceDossier;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.selfintro.SelfIntroductionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 목차·outline·missing·경험 상세 — 설계 5.2·5.3. 자료원은 경험 원본(experiences)과 계정 이력서의 미연결 행 묶음(RowGroups)이다.
 * 3단계와 응답 모양이 같고 값만 찬다(전환 경로). 모든 조회는 소유자로 한정한다.
 */
@Service
@Transactional(readOnly = true)
public class ExperienceService {
    /** 목차 한 쪽의 항목 수 기본값과 상한(설계 5.2 "한 쪽에 60개"). 경험 다음에 미등록 묶음이 이어진다. */
    static final int PAGE = 60;
    static final int MAX_DETAIL = 10;
    static final String IMPORT_HINT = "이력서에 경험 원본과 연결되지 않은 행이 있습니다. 웹 [경험] 화면의 '경험 가져오기'에서 "
            + "묶음마다 새 경험으로 만들거나(CREATE) 기존 경험에 잇습니다(LINK_TO). MCP 로는 가져오지 않습니다.";

    private final ResumeRepository resumes;
    private final SelfIntroductionRepository selfIntros;
    private final ExperienceRepository experiences;
    private final ExperienceRevisions revisions;
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public ExperienceService(ResumeRepository resumes, SelfIntroductionRepository selfIntros,
                             ExperienceRepository experiences, ExperienceRevisions revisions, JdbcTemplate jdbc,
                             CurrentUser currentUser, ObjectMapper objectMapper) {
        this.resumes = resumes;
        this.selfIntros = selfIntros;
        this.experiences = experiences;
        this.revisions = revisions;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    /** 계정의 모든 이력서. 최근 수정순이라 묶음의 대표 행은 가장 최근에 고친 이력서의 행이다. */
    public List<RowGroups.Doc> docs() {
        return resumes.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream().map(this::doc).toList();
    }

    /**
     * 목록 모드: 경험 한 줄들 뒤에 미등록 묶음이 이어지는 한 줄을 cursor·limit 으로 자른다.
     * 상세 모드(ids, 10개 이하): 그 경험들의 Detail. 남의 id 는 404.
     */
    public ExperienceViews.Index index(List<ExperienceKind> kinds, List<UUID> ids, Integer cursor, Integer limit) {
        UUID owner = currentUser.id();
        List<RowGroups.Doc> docs = docs();
        List<RowGroups.Group> groups = RowGroups.unlinked(docs);
        List<Experience> all = experiences.findAllByOwnerIdOrderByKindAscTitleKeyAscIdAsc(owner);
        ExperienceViews.Counts counts = counts(all, groups);
        String hint = groups.isEmpty() ? null : IMPORT_HINT;
        if (ids != null && !ids.isEmpty()) {
            if (ids.size() > MAX_DETAIL) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "TOO_MANY_IDS", "ids 는 " + MAX_DETAIL + "개 이하여야 합니다.");
            }
            Map<UUID, Experience> byId = new HashMap<>();
            all.forEach(experience -> byId.put(experience.getId(), experience));
            List<Experience> picked = new ArrayList<>();
            for (UUID id : new java.util.LinkedHashSet<>(ids)) {
                Experience experience = byId.get(id);
                if (experience == null) throw new ResourceNotFoundException(id);
                picked.add(experience);
            }
            return new ExperienceViews.Index(counts, true, null, hint, List.copyOf(details(picked, docs)), List.of());
        }
        Set<ExperienceKind> wanted = kinds == null || kinds.isEmpty() ? Set.of(ExperienceKind.values()) : Set.copyOf(kinds);
        List<Experience> listed = all.stream().filter(experience -> wanted.contains(experience.getKind()))
                .sorted(java.util.Comparator.comparing(Experience::getKind)).toList();
        List<RowGroups.Group> pickedGroups = groups.stream().filter(group -> wanted.contains(group.kind())).toList();
        int total = listed.size() + pickedGroups.size();
        int from = Math.min(Math.max(cursor == null ? 0 : cursor, 0), total);
        int size = Math.min(Math.max(limit == null ? PAGE : limit, 1), PAGE);
        int to = Math.min(from + size, total);
        boolean complete = to == total;
        List<Experience> pageExperiences = listed.subList(Math.min(from, listed.size()), Math.min(to, listed.size()));
        List<RowGroups.Group> pageGroups = pickedGroups.subList(Math.max(from - listed.size(), 0),
                Math.max(to - listed.size(), 0));
        return new ExperienceViews.Index(counts, complete, complete ? null : to, hint,
                List.copyOf(lines(pageExperiences, docs)), pageGroups.stream().map(ExperienceService::unlinked).toList());
    }

    public ExperienceViews.Detail detail(UUID id) {
        Experience experience = experiences.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
        return details(List.of(experience), docs()).get(0);
    }

    /** 응답 머리에 붙일 missing — full resume_get·resume_create 가 쓴다. 남의 이력서면 404. */
    public List<ExperienceViews.Missing> missing(UUID resumeId) {
        List<RowGroups.Doc> docs = docs();
        requireOwned(docs, resumeId);
        return missing(docs, resumeId, experiences.findHeads(currentUser.id()));
    }

    public ExperienceViews.Outline outline(UUID resumeId) {
        List<RowGroups.Doc> docs = docs();
        RowGroups.Doc doc = requireOwned(docs, resumeId);
        ResumeContent c = doc.content();
        List<ExperienceViews.Section> sections = List.of(
                section("educations", c.educations(), r -> head(null, r.school(), r.startYm(), r.endYm())),
                section("trainings", c.trainings(), r -> head(r.experienceId(), r.name(), r.startYm(), r.endYm())),
                section("activities", c.activities(), r -> head(r.experienceId(), r.name(), r.startYm(), r.endYm())),
                section("experiences", c.experiences(), r -> head(r.experienceId(), r.company(), r.startYm(), r.endYm())),
                section("awards", c.awards(), r -> head(null, r.name(), r.awardedYm(), null)),
                section("certificates", c.certificates(), r -> head(null, r.name(), r.acquiredYm(), null)),
                section("skills", c.skills(), r -> head(null, r.name(), null, null)),
                section("projects", c.projects(), r -> head(r.experienceId(), r.name(), r.startYm(), r.endYm())));
        return new ExperienceViews.Outline(doc.id(), doc.name(), doc.version(), sections,
                missing(docs, resumeId, experiences.findHeads(currentUser.id())),
                RowGroups.unlinked(List.of(doc)).stream().map(ExperienceService::unlinked).toList());
    }

    /**
     * 이 이력서에 없는 것(설계 5.3 missing). ① 다른 이력서에만 있는 미연결 행 묶음 중 이 이력서에 같은 묶음 키 행이 없는 것,
     * ② 은행 경험 중 이 이력서의 어느 행도 앵커로 가리키지 않는 것(OTHER 제외 — 이력서에 그리지 않는다).
     * maybeUnlinked: 이 이력서에 종류·제목 키가 같은 미연결 행이 있음 — 같은 경험일 수 있어 사용자에게 확인받는다.
     */
    static List<ExperienceViews.Missing> missing(List<RowGroups.Doc> docs, UUID resumeId, List<ExperienceHead> heads) {
        Set<String> present = new HashSet<>();
        Set<String> unlinkedTitles = new HashSet<>();
        Set<UUID> anchored = new HashSet<>();
        Map<UUID, List<ExperienceViews.Source>> usedElsewhere = new LinkedHashMap<>();
        for (RowGroups.Doc doc : docs) {
            for (RowGroups.Row row : RowGroups.rows(doc)) {
                if (doc.id().equals(resumeId)) {
                    present.add(row.groupKey());
                    if (row.experienceId() != null) anchored.add(row.experienceId());
                    else unlinkedTitles.add(row.kind() + "|" + KeyNormalizer.key(row.title()));
                } else if (row.experienceId() != null) {
                    usedElsewhere.computeIfAbsent(row.experienceId(), id -> new ArrayList<>()).add(source(row));
                }
            }
        }
        List<ExperienceViews.Missing> missing = new ArrayList<>();
        for (RowGroups.Group group : RowGroups.unlinked(docs)) {
            if (present.contains(group.key())) continue;
            boolean maybe = unlinkedTitles.contains(group.kind() + "|" + KeyNormalizer.key(group.title()));
            missing.add(new ExperienceViews.Missing(group.kind(), group.title(), group.period(), null, maybe,
                    group.rows().stream().map(ExperienceService::source).toList()));
        }
        for (ExperienceHead head : heads) {
            if (head.kind() == ExperienceKind.OTHER || anchored.contains(head.id())) continue;
            boolean maybe = unlinkedTitles.contains(head.kind() + "|" + head.titleKey());
            missing.add(new ExperienceViews.Missing(head.kind(), head.title(), RowGroups.period(head.startYm(), null),
                    head.id(), maybe, usedElsewhere.getOrDefault(head.id(), List.of())));
        }
        return missing;
    }

    private ExperienceViews.Counts counts(List<Experience> all, List<RowGroups.Group> groups) {
        Map<ExperienceKind, Integer> byKind = new EnumMap<>(ExperienceKind.class);
        all.forEach(experience -> byKind.merge(experience.getKind(), 1, Integer::sum));
        int ai = (int) all.stream().filter(experience -> !experience.isReviewed()).count();
        int rows = groups.stream().mapToInt(group -> group.rows().size()).sum();
        return new ExperienceViews.Counts(byKind, ai, rows, groups.size(),
                selfIntros.countWithoutDirectSource(currentUser.id()));
    }

    private List<ExperienceViews.Line> lines(List<Experience> page, List<RowGroups.Doc> docs) {
        if (page.isEmpty()) return List.of();
        Map<UUID, List<ExperienceViews.ResumeRef>> inResumes = new HashMap<>();
        for (RowGroups.Doc doc : docs) {
            for (RowGroups.Row row : RowGroups.rows(doc)) {
                if (row.experienceId() == null) continue;
                List<ExperienceViews.ResumeRef> refs = inResumes.computeIfAbsent(row.experienceId(), id -> new ArrayList<>());
                if (refs.stream().noneMatch(ref -> ref.id().equals(doc.id()))) {
                    refs.add(new ExperienceViews.ResumeRef(doc.id(), doc.name()));
                }
            }
        }
        Map<UUID, Integer> usage = selfIntroUsage(currentUser.id());
        return page.stream().map(experience -> new ExperienceViews.Line(experience.getId(), experience.getKind(),
                experience.getTitle(), period(experience), experience.getRevision(), experience.isReviewed(),
                experience.getSkills(), experience.getDomains(), inResumes.getOrDefault(experience.getId(), List.of()),
                usage.getOrDefault(experience.getId(), 0))).toList();
    }

    private List<ExperienceViews.Detail> details(List<Experience> picked, List<RowGroups.Doc> docs) {
        UUID owner = currentUser.id();
        Map<UUID, List<ExperienceRevisions.Rev>> history = revisions.of(owner, picked.stream().map(Experience::getId).toList());
        Map<UUID, List<ExperienceViews.RowRef>> rows = new HashMap<>();
        for (RowGroups.Doc doc : docs) {
            for (RowGroups.Row row : RowGroups.rows(doc)) {
                if (row.experienceId() == null) continue;
                int revision = row.experienceRevision() == null ? 1 : row.experienceRevision();
                rows.computeIfAbsent(row.experienceId(), id -> new ArrayList<>()).add(new ExperienceViews.RowRef(doc.id(),
                        doc.name(), row.section(), row.index(), revision,
                        ExperienceRevisions.needsReview(history.getOrDefault(row.experienceId(), List.of()), revision)));
            }
        }
        Map<UUID, List<ExperienceViews.SelfIntroRef>> intros = new HashMap<>();
        jdbc.query("select se.experience_id, s.id, s.question, se.experience_revision, se.source "
                        + "from self_introduction_experiences se join self_introductions s on s.id = se.self_introduction_id "
                        + "and s.owner_id = se.owner_id where se.owner_id = ? order by s.updated_at desc",
                rs -> {
                    UUID experienceId = rs.getObject(1, UUID.class);
                    int revision = rs.getInt(4);
                    intros.computeIfAbsent(experienceId, id -> new ArrayList<>()).add(new ExperienceViews.SelfIntroRef(
                            rs.getObject(2, UUID.class), rs.getString(3), revision,
                            LinkSource.valueOf(rs.getString(5)).trust(),
                            ExperienceRevisions.needsReview(history.getOrDefault(experienceId, List.of()), revision)));
                }, owner);
        return picked.stream().map(experience -> new ExperienceViews.Detail(experience.getId(), experience.getKind(),
                experience.getTitle(), experience.getStartYm(), experience.getMemo(), experience.getVersion(),
                experience.getRevision(), experience.isReviewed(), experience.getReviewedRevision(),
                experience.getDossierBasedOn(),
                experience.getDossier() != null && !Integer.valueOf(experience.getRevision()).equals(experience.getDossierBasedOn()),
                experience.getSkills(), experience.getDomains(), facts(experience), dossier(experience),
                rows.getOrDefault(experience.getId(), List.of()), intros.getOrDefault(experience.getId(), List.of()),
                history.getOrDefault(experience.getId(), List.of()).stream()
                        .map(rev -> new ExperienceViews.RevisionView(rev.revision(), rev.changeKind().name(),
                                rev.source().name(), rev.createdAt())).toList())).toList();
    }

    /** 경험 id → 그 경험을 작성 근거로 쓴 자소서 수. */
    private Map<UUID, Integer> selfIntroUsage(UUID owner) {
        Map<UUID, Integer> usage = new HashMap<>();
        jdbc.query("select experience_id, count(*) from self_introduction_experiences where owner_id = ? group by experience_id",
                rs -> { usage.put(rs.getObject(1, UUID.class), rs.getInt(2)); }, owner);
        return usage;
    }

    ExperienceFacts facts(Experience experience) {
        return objectMapper.readValue(experience.getFacts(), ExperienceFacts.class);
    }

    ExperienceDossier dossier(Experience experience) {
        return experience.getDossier() == null ? null : objectMapper.readValue(experience.getDossier(), ExperienceDossier.class);
    }

    /** 사실의 기간을 우선하고, 없으면 시작 연월. 목차·검색 결과가 같은 값을 쓴다. */
    String period(Experience experience) {
        String period = facts(experience).period();
        return period != null && !period.isBlank() ? period : RowGroups.period(experience.getStartYm(), null);
    }

    private static ExperienceViews.UnlinkedGroup unlinked(RowGroups.Group group) {
        return new ExperienceViews.UnlinkedGroup(group.kind(), group.title(), group.period(),
                group.rows().stream().map(ExperienceService::source).toList());
    }

    static ExperienceViews.Source source(RowGroups.Row row) {
        return new ExperienceViews.Source(row.resume().id(), row.resume().name(), row.section(), row.index(),
                row.resume().version());
    }

    private static ExperienceViews.RowHead head(UUID experienceId, String title, String start, String end) {
        return new ExperienceViews.RowHead(0, experienceId, title, RowGroups.period(start, end));
    }

    /** null 행도 행 번호를 차지한다(행 도구 index 와 맞춘다). 머리는 앵커·제목·기간만. */
    private static <T> ExperienceViews.Section section(String key, List<T> rows,
                                                        Function<T, ExperienceViews.RowHead> head) {
        List<ExperienceViews.RowHead> heads = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ExperienceViews.RowHead value = rows.get(i) == null ? new ExperienceViews.RowHead(i, null, null, "")
                    : head.apply(rows.get(i));
            heads.add(new ExperienceViews.RowHead(i, value.experienceId(), value.title(), value.period()));
        }
        return new ExperienceViews.Section(key, rows.size(), heads);
    }

    /** 남의 이력서는 존재를 알리지 않도록 404. docs 가 이미 소유자로 한정돼 있다. */
    private static RowGroups.Doc requireOwned(List<RowGroups.Doc> docs, UUID resumeId) {
        return docs.stream().filter(doc -> doc.id().equals(resumeId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(resumeId));
    }

    RowGroups.Doc doc(Resume resume) {
        ResumeContent content = objectMapper.readValue(resume.getContent(), ResumeContent.class).normalized();
        return new RowGroups.Doc(resume.getId(), resume.getName(), resume.getVersion(), content);
    }
}
