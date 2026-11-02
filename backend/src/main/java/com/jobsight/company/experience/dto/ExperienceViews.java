package com.jobsight.company.experience.dto;

import com.jobsight.company.experience.ExperienceKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 목차(experience_index)·outline·missing·경험 상세의 응답 모양 — 설계 5.2·5.3. 3단계와 같은 모양에 4단계 값이 찬다(10.7 R44):
 * experiences[] 에 경험 원본, missing 에 은행 경험, 행 머리에 experienceId. unlinkedRows 는 앵커가 없는 행만이다.
 */
public final class ExperienceViews {
    private ExperienceViews() {
    }

    /** 묶음을 이룬 이력서 행 하나. index 는 0부터, 행 도구의 index 와 같다. */
    public record Source(UUID resumeId, String resumeName, String section, int index, long resumeVersion) {
    }

    /** 은행에 없는(앵커가 없는) 행을 (kind, title_key, 시작 연월 숫자)로 묶은 것. */
    public record UnlinkedGroup(ExperienceKind kind, String title, String period, List<Source> sources) {
    }

    /**
     * experiences: 종류별 경험 수, aiRecords: 지금 개정을 사용자가 검토하지 않은 경험 수, unlinkedRows: 미등록 행 수,
     * groups: 미등록 행 묶음 수, unknownSourceSelfIntros: 직접 출처가 없는 자소서 수.
     */
    public record Counts(Map<ExperienceKind, Integer> experiences, int aiRecords, int unlinkedRows, int groups,
                         long unknownSourceSelfIntros) {
    }

    /**
     * 늘 {counts, complete, importHint, experiences, unlinkedRows} 모양. 목록 모드의 experiences 는 Line, 상세 모드(ids)는 Detail 이다.
     * 경험 다음에 미등록 묶음이 이어지는 한 줄로 쪽을 나눈다 — complete:false 면 nextCursor 로 다음 쪽을 부른다.
     * importHint 는 미등록 행이 있을 때 웹 가져오기 화면 안내다.
     */
    public record Index(Counts counts, boolean complete, Integer nextCursor, String importHint, List<Object> experiences,
                        List<UnlinkedGroup> unlinkedRows) {
    }

    /** 목차 한 줄. reviewed:false 면 'AI 기록'(지금 개정을 사용자가 검토하지 않음). */
    public record Line(UUID id, ExperienceKind kind, String title, String period, int revision, boolean reviewed,
                       List<String> skills, List<String> domains, List<ResumeRef> inResumes, int usedInSelfIntros) {
    }

    public record ResumeRef(UUID id, String name) {
    }

    /**
     * 경험 상세(experience_index ids, 웹 상세). version 은 다음 experience_save 의 expectedVersion 이다.
     * dossierStale: 소재집을 만든 개정(dossierBasedOn)이 지금 개정과 다름 — '소재집 갱신 필요'.
     * rows·selfIntros 의 needsReview: 참조한 개정 뒤에 정정(CORRECT) 개정이 있음 — '재검토 필요'.
     */
    public record Detail(UUID id, ExperienceKind kind, String title, String startYm, String memo, long version,
                         int revision, boolean reviewed, Integer reviewedRevision, Integer dossierBasedOn,
                         boolean dossierStale, List<String> skills, List<String> domains, ExperienceFacts facts,
                         ExperienceDossier dossier, List<RowRef> rows, List<SelfIntroRef> selfIntros,
                         List<RevisionView> revisions) {
    }

    public record RowRef(UUID resumeId, String resumeName, String section, int index, int experienceRevision,
                         boolean needsReview) {
    }

    /** trust: '확정'(웹) 또는 'AI 기록'(MCP 가 남김). */
    public record SelfIntroRef(UUID id, String question, int experienceRevision, String trust, boolean needsReview) {
    }

    public record RevisionView(int revision, String changeKind, String source, Instant createdAt) {
    }

    /**
     * 이 이력서에 없는 경험. 미연결 행 묶음(experienceId null)과 은행 경험(experienceId 있음, sources 는 그 경험을 쓰는 다른 이력서 행)이다.
     * maybeUnlinked 는 이 이력서에 종류·제목 키가 같은 (기간이 다르거나 아직 연결되지 않은) 행이 있어 같은 경험일 수 있다는 표시다.
     */
    public record Missing(ExperienceKind kind, String title, String period, UUID experienceId, boolean maybeUnlinked,
                          List<Source> sources) {
    }

    /** 행 머리. experienceId 는 그 행의 경험 앵커다(없으면 null — 미연결). */
    public record RowHead(int index, UUID experienceId, String title, String period) {
    }

    public record Section(String section, int count, List<RowHead> rows) {
    }

    /** resume_get(view=outline). unlinkedRows 는 이 이력서의 미연결 경험 행 묶음이다. */
    public record Outline(UUID id, String name, long version, List<Section> sections, List<Missing> missing,
                          List<UnlinkedGroup> unlinkedRows) {
    }

    /** experience_save 응답(설계 5.2). changeKind 는 서버 판정 INIT·ADD·CORRECT·NONE. */
    public record Saved(UUID id, long version, int revision, String changeKind, List<NeedsReview> needsReview,
                        List<String> normalizedSkills, List<String> warnings) {
    }

    /** 정정으로 재검토가 필요해진 문서. type 은 RESUME 또는 SELF_INTRO. */
    public record NeedsReview(String type, UUID id, String name, int basedOnRevision) {
    }

    /** 경험 삭제 결과. detachedSelfIntros 는 detach:true 로 끊은 작성 근거 수. */
    public record Deleted(UUID id, int detachedSelfIntros) {
    }

    /** 가져오기 계획(dry-run, 설계 6.3). mergeCandidates 는 같은 경험일 수 있는 다른 묶음 키 — 자동으로 합치지 않는다. */
    public record ImportPlan(List<ImportGroup> groups, Map<UUID, Long> resumeVersions) {
    }

    /**
     * warning: 이 묶음은 CREATE·LINK_TO 할 수 없다는 안내(없으면 null). 지금은 한 이력서의 두 행이 같은 묶음일 때다 —
     * 한 이력서에서 같은 경험을 두 행에 걸 수 없다(EXPERIENCE_ROW_DUPLICATE). SKIP 하거나 이력서에서 행을 정리한다.
     */
    public record ImportGroup(String key, ExperienceKind kind, String title, String period, List<Source> rows,
                              List<String> mergeCandidates, String warning) {
    }

    public record ImportResult(int created, int linked, int skipped, List<UUID> experienceIds) {
    }
}
