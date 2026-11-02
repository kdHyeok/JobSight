package com.jobsight.company.experience.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 경험 원본의 사실 — 설계 6.5. 저장 JSON 과 입력 DTO 가 같은 모양이다(평평한 record, MCP 스키마 생성기 제약).
 * 배열 항목은 경험 안에서 유일한 안정 id 를 든다. id 없이 온 새 항목에는 서버가 붙이고, 고친 항목은 같은 id 로 보낸다.
 * 주장 칸(period·role·measurement·actions·results)과 근거·미확인·충돌 칸을 나눠 개정 종류(ADD·CORRECT)를 판정한다.
 */
public record ExperienceFacts(
        @Size(max = 200) String period,
        @Size(max = 2000) String role,
        @Valid Measurement measurement,
        @Size(max = 50) List<@NotNull @Valid Item> actions,
        @Size(max = 50) List<@NotNull @Valid Item> results,
        @Size(max = 50) List<@NotNull @Valid Evidence> evidence,
        @Size(max = 50) List<@NotNull @Valid Item> unverified,
        @Size(max = 50) List<@NotNull @Valid Item> conflicts
) {
    /** 주장 하나. evidenceIds 는 이 주장을 받치는 evidence[].id. */
    public record Item(
            @Size(max = 40) String id,
            @NotBlank @Size(max = 2000) String text,
            @Size(max = 10) List<@NotNull @Size(max = 40) String> evidenceIds
    ) {
    }

    /** 표본 개선을 전체 성과로 키우지 않게 측정 환경과 범위를 따로 적는다. */
    public record Measurement(@Size(max = 1000) String environment, @Size(max = 1000) String scope) {
    }

    /**
     * 근거. 인용(quote)은 필수다 — 다른 PC 에서 원문을 열 수 없어도 확인할 수 있어야 한다. 로컬 경로만 있는 근거는 받지 않는다.
     * 자소서 답변은 근거가 아니다(내 글이 다시 사실 근거가 되는 순환을 막는다).
     */
    public record Evidence(
            @Size(max = 40) String id,
            @NotNull SourceType sourceType,
            @Valid Ref ref,
            @NotBlank @Size(max = 1000) String quote,
            @Size(max = 3000) String context,
            @Size(max = 200) String location,
            @Size(max = 64) String hash,
            @Size(max = 40) String capturedAt
    ) {
    }

    public enum SourceType { RESUME_ROW, ATTACHMENT, URL, NOTE }

    /** RESUME_ROW 는 resumeId·section·index·resumeVersion, ATTACHMENT 는 attachmentId, URL 은 https 주소. */
    public record Ref(UUID resumeId, @Size(max = 40) String section, Integer index, Long resumeVersion,
                      UUID attachmentId, @Size(max = 500) String url) {
    }
}
