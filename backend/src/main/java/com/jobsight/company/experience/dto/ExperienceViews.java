package com.jobsight.company.experience.dto;

import com.jobsight.company.experience.ExperienceKind;

import java.util.List;
import java.util.UUID;

/**
 * 목차(experience_index)·outline·missing 의 응답 모양 — 설계 5.2·5.3. 4단계와 같은 모양으로 낸다(10.7 R44).
 * 4단계는 형식을 유지한 채 experiences[] 를 채우고, experienceId 와 missing 의 은행 경험을 더한다(자료원만 바뀐다).
 */
public final class ExperienceViews {
    private ExperienceViews() {
    }

    /** 묶음을 이룬 이력서 행 하나. index 는 0부터, 행 도구의 index 와 같다. */
    public record Source(UUID resumeId, String resumeName, String section, int index, long resumeVersion) {
    }

    /** 은행에 없는 행을 (kind, title_key, 시작 연월 숫자)로 묶은 것. */
    public record UnlinkedGroup(ExperienceKind kind, String title, String period, List<Source> sources) {
    }

    /** 3단계에서 셀 수 있는 것만: 미등록 행 수, 묶음 수, 출처 미상(직접 출처 없는) 자소서 수. */
    public record Counts(int unlinkedRows, int groups, long unknownSourceSelfIntros) {
    }

    /**
     * 늘 {counts, complete, experiences, unlinkedRows} 모양. 3단계 experiences 는 늘 [] 다(경험 테이블이 4단계).
     * complete:false 면 nextCursor 로 다음 쪽을 부른다. importHint 는 웹 가져오기 화면이 생기는 4단계에 붙인다.
     */
    public record Index(Counts counts, boolean complete, Integer nextCursor, List<Object> experiences,
                        List<UnlinkedGroup> unlinkedRows) {
    }

    /**
     * 이 이력서에 같은 묶음 키 행이 없는 묶음. 3단계는 다른 이력서에만 있는 행 묶음이라 experienceId 가 늘 null 이다.
     * maybeUnlinked 는 이 이력서에 종류·제목 키가 같고 시작 연월만 다른 행이 있어 같은 경험일 수 있다는 표시다.
     */
    public record Missing(ExperienceKind kind, String title, String period, UUID experienceId, boolean maybeUnlinked,
                          List<Source> sources) {
    }

    /** 행 머리. experienceId 는 4단계부터 값이 찬다. */
    public record RowHead(int index, UUID experienceId, String title, String period) {
    }

    public record Section(String section, int count, List<RowHead> rows) {
    }

    /** resume_get(view=outline). unlinkedRows 는 이 이력서의 경험 행 묶음이다(3단계는 전부 미연결). */
    public record Outline(UUID id, String name, long version, List<Section> sections, List<Missing> missing,
                          List<UnlinkedGroup> unlinkedRows) {
    }
}
