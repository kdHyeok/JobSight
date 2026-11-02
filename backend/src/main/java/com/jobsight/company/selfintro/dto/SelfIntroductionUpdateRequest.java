package com.jobsight.company.selfintro.dto;

import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.selfintro.Competency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 수정 요청. resumeIds·question·answer 는 보낸 값으로 바꾸고 연결 0개도 허용한다(직무가 지워져 연결이 없어진 문항도 고칠 수 있게).
 * positionIds·questionTypes 는 null 이면 유지, 배열이면 그 목록으로 맞춘다([] 는 비움) — 웹의 기존 저장 경로는 이 필드를 보내지 않는다.
 * expectedVersion 은 읽은 문항의 version 이다. 웹은 필수, MCP 는 resumeIds·question·answer 를 바꿀 때 필수다
 * (연결만 더할 때는 선택) — docs/self-introductions.md 불변 조건 12.
 */
public record SelfIntroductionUpdateRequest(
        @NotNull(message = "이력서 목록은 필수입니다(연결을 모두 끊으려면 []).")
        @Size(max = 30, message = "이력서는 30개 이하여야 합니다.") List<UUID> resumeIds,
        @Size(max = 10, message = "지원 직무는 10개 이하여야 합니다.") List<UUID> positionIds,
        @Size(max = 3, message = "문항 유형은 3개 이하여야 합니다.") List<Competency> questionTypes,
        @NotBlank(message = "질문은 필수입니다.")
        @Size(max = 1000, message = "질문은 1,000자 이하여야 합니다.") String question,
        @Size(max = 10000, message = "답변은 10,000자 이하여야 합니다.") String answer,
        Long expectedVersion,
        /**
         * 작성 근거 (경험 id, 개정) 쌍(설계 6.8). null 은 유지. 웹은 목록으로 맞추고([] 는 비움), MCP 는 더하기만 한다 —
         * 이미 연결된 경험은 건너뛰고, 빼기와 개정 바꾸기는 웹에서만 한다.
         */
        @Size(max = 10, message = "작성 근거 경험은 10개 이하여야 합니다.") List<ExperienceRef> experiences,
        /**
         * MCP 전용 재현 정보(설계 6.7): 이 답변을 쓰기 전에 받은 experience_search 응답의 context 를 그대로 넘긴다(선택).
         * 서버가 작성 기준 version·쓴 경험 개정과 함께 writing_context 에 남긴다. 웹 저장은 이 값을 쓰지 않는다.
         */
        ExperienceSearchResult.Context searchContext
) {
    public SelfIntroductionUpdateRequest(List<UUID> resumeIds, List<UUID> positionIds, List<Competency> questionTypes,
                                         String question, String answer, Long expectedVersion) {
        this(resumeIds, positionIds, questionTypes, question, answer, expectedVersion, null, null);
    }

    public SelfIntroductionUpdateRequest(List<UUID> resumeIds, List<UUID> positionIds, List<Competency> questionTypes,
                                         String question, String answer, Long expectedVersion, List<ExperienceRef> experiences) {
        this(resumeIds, positionIds, questionTypes, question, answer, expectedVersion, experiences, null);
    }
}
