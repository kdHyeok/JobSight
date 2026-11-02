package com.jobsight.company.selfintro.dto;

import com.jobsight.company.selfintro.Competency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 수정 요청. resumeIds·question·answer 는 보낸 값으로 바꾸고 연결 0개도 허용한다(직무가 지워져 연결이 없어진 문항도 고칠 수 있게).
 * positionIds·questionTypes 는 null 이면 유지, 배열이면 그 목록으로 맞춘다([] 는 비움) — 웹의 기존 저장 경로는 이 필드를 보내지 않는다.
 */
public record SelfIntroductionUpdateRequest(
        @NotNull(message = "이력서 목록은 필수입니다(연결을 모두 끊으려면 []).")
        @Size(max = 30, message = "이력서는 30개 이하여야 합니다.") List<UUID> resumeIds,
        @Size(max = 10, message = "지원 직무는 10개 이하여야 합니다.") List<UUID> positionIds,
        @Size(max = 3, message = "문항 유형은 3개 이하여야 합니다.") List<Competency> questionTypes,
        @NotBlank(message = "질문은 필수입니다.")
        @Size(max = 1000, message = "질문은 1,000자 이하여야 합니다.") String question,
        @Size(max = 10000, message = "답변은 10,000자 이하여야 합니다.") String answer
) {
}
