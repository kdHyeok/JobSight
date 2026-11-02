package com.jobsight.company.experience.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * experience_search 입력 — 설계 4.1·4.2·5.2. 등록 직무 positionId 또는 저장하지 않는 공고 posting 중 하나.
 * posting 은 서버가 저장하지 않는다. 고른 공고만 그다음에 posting_create 로 저장한다(SKILL 2번).
 */
public record ExperienceSearchRequest(
        UUID positionId,
        @Valid Posting posting,
        @Size(max = 60, message = "요구는 60개 이하여야 합니다.") List<@Valid Requirement> requirements,
        Integer limit
) {
    /** 저장하지 않는 공고의 다섯 칸(설계 2.1). 칸을 섞지 않는다 — 기술 환경 나열은 필수 자격이 아니다. */
    public record Posting(
            @Size(max = 200) String companyName,
            @Size(max = 20) List<@Size(max = 60) String> industries,
            @Size(max = 200) String positionName,
            @Size(max = 5000) String qualifications,
            @Size(max = 5000) String responsibilities,
            @Size(max = 5000) String requiredSkills,
            @Size(max = 5000) String preferredSkills,
            @Size(max = 60) List<@Size(max = 60) String> techStack
    ) {
    }

    /**
     * 클라이언트가 공고 원문에서 뽑은 요구 하나. text 는 기술 이름("Kafka"), quote 는 공고 문장이다.
     * kind QUALIFICATION(경력 연수·학력·고용 조건)은 점수에 넣지 않고 qualifications[] 로 돌려준다.
     */
    public record Requirement(
            @NotBlank @Size(max = 100) String text,
            @NotNull Field field,
            @NotNull Kind kind,
            @Size(max = 500) String quote
    ) {
    }

    /** 다섯 칸과 칸별 가중(설계 4.2 초기값, 골드셋으로 조정). 상수 이름이 JSON 값이다. */
    public enum Field {
        qualifications(1.0, true),
        responsibilities(0.7, false),
        requiredSkills(1.0, true),
        preferredSkills(0.5, false),
        techStack(0.3, false);

        private final double weight;
        private final boolean reportsGap;

        Field(double weight, boolean reportsGap) {
            this.weight = weight;
            this.reportsGap = reportsGap;
        }

        public double weight() {
            return weight;
        }

        /** uncovered 는 지원자격·요구 역량 칸의 키만 — 우대·기술 환경 나열은 결핍으로 보고하지 않는다. */
        public boolean reportsGap() {
            return reportsGap;
        }
    }

    public enum Kind { SKILL, QUALIFICATION }
}
