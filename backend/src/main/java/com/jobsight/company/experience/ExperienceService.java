package com.jobsight.company.experience;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.selfintro.SelfIntroductionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 3단계 목차·outline·missing — 설계 5.2 "3단계 최소판", 5.3. 자료원은 계정의 이력서 행 묶음(RowGroups)뿐이다.
 * 전환 경로: 4단계는 응답 모양을 그대로 두고 경험 원본(experiences)을 자료원에 더한다. 모든 조회는 소유자로 한정한다.
 */
@Service
@Transactional(readOnly = true)
public class ExperienceService {
    /** 목차 한 쪽의 묶음 수 기본값과 상한(설계 5.2 "한 쪽에 60개"). */
    static final int PAGE = 60;

    private final ResumeRepository resumes;
    private final SelfIntroductionRepository selfIntros;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public ExperienceService(ResumeRepository resumes, SelfIntroductionRepository selfIntros, CurrentUser currentUser,
                             ObjectMapper objectMapper) {
        this.resumes = resumes;
        this.selfIntros = selfIntros;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    /** 계정의 모든 이력서. 최근 수정순이라 묶음의 대표 행은 가장 최근에 고친 이력서의 행이다. */
    public List<RowGroups.Doc> docs() {
        return resumes.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream().map(this::doc).toList();
    }

    public ExperienceViews.Index index(List<ExperienceKind> kinds, Integer cursor, Integer limit) {
        List<RowGroups.Doc> docs = docs();
        List<RowGroups.Group> groups = RowGroups.of(docs);
        int rows = groups.stream().mapToInt(group -> group.rows().size()).sum();
        ExperienceViews.Counts counts = new ExperienceViews.Counts(rows, groups.size(),
                selfIntros.countWithoutDirectSource(currentUser.id()));
        List<RowGroups.Group> picked = kinds == null || kinds.isEmpty() ? groups
                : groups.stream().filter(group -> kinds.contains(group.kind())).toList();
        int from = Math.min(Math.max(cursor == null ? 0 : cursor, 0), picked.size());
        int size = Math.min(Math.max(limit == null ? PAGE : limit, 1), PAGE);
        int to = Math.min(from + size, picked.size());
        boolean complete = to == picked.size();
        return new ExperienceViews.Index(counts, complete, complete ? null : to, List.of(),
                picked.subList(from, to).stream().map(ExperienceService::unlinked).toList());
    }

    /** 응답 머리에 붙일 missing — full resume_get·resume_create 가 쓴다. 남의 이력서면 404. */
    public List<ExperienceViews.Missing> missing(UUID resumeId) {
        List<RowGroups.Doc> docs = docs();
        requireOwned(docs, resumeId);
        return missing(RowGroups.of(docs), resumeId);
    }

    public ExperienceViews.Outline outline(UUID resumeId) {
        List<RowGroups.Doc> docs = docs();
        RowGroups.Doc doc = requireOwned(docs, resumeId);
        ResumeContent c = doc.content();
        List<ExperienceViews.Section> sections = List.of(
                section("educations", c.educations(), r -> head(r.school(), r.startYm(), r.endYm())),
                section("trainings", c.trainings(), r -> head(r.name(), r.startYm(), r.endYm())),
                section("activities", c.activities(), r -> head(r.name(), r.startYm(), r.endYm())),
                section("experiences", c.experiences(), r -> head(r.company(), r.startYm(), r.endYm())),
                section("awards", c.awards(), r -> head(r.name(), r.awardedYm(), null)),
                section("certificates", c.certificates(), r -> head(r.name(), r.acquiredYm(), null)),
                section("skills", c.skills(), r -> head(r.name(), null, null)),
                section("projects", c.projects(), r -> head(r.name(), r.startYm(), r.endYm())));
        return new ExperienceViews.Outline(doc.id(), doc.name(), doc.version(), sections,
                missing(RowGroups.of(docs), resumeId),
                RowGroups.of(List.of(doc)).stream().map(ExperienceService::unlinked).toList());
    }

    /**
     * 이 이력서에 같은 묶음 키 행이 없는 묶음(설계 5.3 missing ①). 묶음 키가 다르더라도 종류·제목 키가 같은 행이
     * 이 이력서에 있으면 maybeUnlinked — 기간을 고쳐 적은 같은 경험일 수 있어 사용자에게 확인받는다.
     */
    static List<ExperienceViews.Missing> missing(List<RowGroups.Group> groups, UUID resumeId) {
        Set<String> titles = new HashSet<>();
        Set<String> present = new HashSet<>();
        for (RowGroups.Group group : groups) {
            for (RowGroups.Row row : group.rows()) {
                if (!row.resume().id().equals(resumeId)) continue;
                present.add(group.key());
                titles.add(row.kind() + "|" + KeyNormalizer.key(row.title()));
            }
        }
        List<ExperienceViews.Missing> missing = new ArrayList<>();
        for (RowGroups.Group group : groups) {
            if (present.contains(group.key())) continue;
            boolean maybe = titles.contains(group.kind() + "|" + KeyNormalizer.key(group.title()));
            missing.add(new ExperienceViews.Missing(group.kind(), group.title(), group.period(), null, maybe,
                    sources(group)));
        }
        return missing;
    }

    private static ExperienceViews.UnlinkedGroup unlinked(RowGroups.Group group) {
        return new ExperienceViews.UnlinkedGroup(group.kind(), group.title(), group.period(), sources(group));
    }

    private static List<ExperienceViews.Source> sources(RowGroups.Group group) {
        return group.rows().stream().map(row -> new ExperienceViews.Source(row.resume().id(), row.resume().name(),
                row.section(), row.index(), row.resume().version())).toList();
    }

    private static ExperienceViews.RowHead head(String title, String start, String end) {
        return new ExperienceViews.RowHead(0, null, title, RowGroups.period(start, end));
    }

    /** null 행도 행 번호를 차지한다(행 도구 index 와 맞춘다). 머리는 제목·기간만. */
    private static <T> ExperienceViews.Section section(String key, List<T> rows,
                                                        Function<T, ExperienceViews.RowHead> head) {
        List<ExperienceViews.RowHead> heads = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ExperienceViews.RowHead value = rows.get(i) == null ? new ExperienceViews.RowHead(i, null, null, "")
                    : head.apply(rows.get(i));
            heads.add(new ExperienceViews.RowHead(i, null, value.title(), value.period()));
        }
        return new ExperienceViews.Section(key, rows.size(), heads);
    }

    /** 남의 이력서는 존재를 알리지 않도록 404. docs 가 이미 소유자로 한정돼 있다. */
    private static RowGroups.Doc requireOwned(List<RowGroups.Doc> docs, UUID resumeId) {
        return docs.stream().filter(doc -> doc.id().equals(resumeId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(resumeId));
    }

    private RowGroups.Doc doc(Resume resume) {
        ResumeContent content = objectMapper.readValue(resume.getContent(), ResumeContent.class).normalized();
        return new RowGroups.Doc(resume.getId(), resume.getName(), resume.getVersion(), content);
    }
}
