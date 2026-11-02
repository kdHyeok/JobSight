package com.jobsight.company.experience.dto;

import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.dto.ExperienceSearchRequest.Field;

import java.util.List;
import java.util.UUID;

/**
 * experience_search 응답 — 설계 5.4. 결과는 EXPERIENCE(경험 원본)와 RESUME_ROW(미연결 행 묶음)이고 형식은 3단계와 같다.
 * 신호는 REQUIREMENT·LEXICAL·DENSE(5단계)다. episodes·alreadyUsedFor 같은 4c·6단계 필드는 싣지 않는다.
 */
public record ExperienceSearchResult(
        Query query,
        Context context,
        boolean coverageIncomplete,
        List<String> warnings,
        List<String> degraded,
        List<Uncovered> uncovered,
        List<Qualification> qualifications,
        List<Result> results
) {
    /** requirementsSource 는 CLIENT(requirements[] 를 받음) 또는 SERVER_DICTIONARY(서버 사전으로 뽑음). */
    public record Query(String position, boolean saved, String requirementsSource, List<RequirementKey> requirements) {
    }

    public record RequirementKey(String key, Field field) {
    }

    /**
     * 재현 정보(설계 6.7). dataVersion 은 후보로 쓴 이력서·경험 (id, version) 목록의 해시, searchConfig 는 신호·가중 구성 상수.
     * embeddingModel 은 DENSE 에 쓴 모델(degraded 면 null), indexGeneration 은 이 계정 경험 조각의 마지막 적재 시각,
     * indexBehind 는 아직 적재되지 않은(dirty) 경험이 있다는 뜻이다 — 방금 저장한 경험은 DENSE 에 아직 없을 수 있다.
     */
    public record Context(String dataVersion, String searchConfig, String embeddingModel, String indexGeneration,
                          boolean indexBehind) {
    }

    /** 받쳐 줄 후보가 없는 요구. reason TAG_MISMATCH_POSSIBLE 은 후보 본문에 그 문자열이 보인다는 뜻이다. */
    public record Uncovered(String key, Field field, String reason) {
    }

    /** 점수에 넣지 않는 지원 자격. 충족 판단은 사용자와 클라이언트가 한다. */
    public record Qualification(String text, Field field, String quote) {
    }

    /** 원문 출처. textHash 는 행 평문 SHA-256 앞 16자, excerpt 는 앞 200자. */
    public record Source(UUID resumeId, String resumeName, String section, int index, long resumeVersion,
                         String textHash, String excerpt) {
    }

    /**
     * REQUIREMENT 는 맞은 키·칸·공고 인용, LEXICAL 은 그 신호 안 순위와 맞은 단어(최대 5개),
     * DENSE 는 그 신호 안 순위와 가장 가까운 조각의 출처 필드(sourceField, 예: facts.results). DENSE 만 있으면 "의미상 비슷할 뿐"이다.
     */
    public record Why(String signal, Integer rank, List<String> matched, Field field, String quote, String sourceField) {
    }

    /**
     * type RESUME_ROW: source 는 대표 행(가장 최근에 고친 이력서), alsoIn 은 같은 묶음의 다른 행이다. experienceId 등은 null.
     * type EXPERIENCE: 경험 원본. source 는 null(원문 근거는 experience_index 상세의 facts.evidence), alsoIn 은 이 경험을 쓰는 이력서 행,
     * revision 은 지금 개정, reviewed:false 면 'AI 기록'.
     */
    public record Result(int rank, String type, ExperienceKind kind, String title, String period, Source source,
                         List<Source> alsoIn, List<Why> why, UUID experienceId, Integer revision, Boolean reviewed) {
    }
}
