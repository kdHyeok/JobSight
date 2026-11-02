package com.jobsight.company.experience.dto;

import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.dto.ExperienceSearchRequest.Field;

import java.util.List;
import java.util.UUID;

/**
 * experience_search 응답 — 설계 5.4 의 3단계 판. 결과는 RESUME_ROW 뿐이고, 4단계에 EXPERIENCE 가 같은 형식으로 더해진다.
 * 3단계에 없는 신호·필드(DENSE, episodes, alreadyUsedFor, embeddingModel 등)는 싣지 않는다.
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

    /** 재현 정보(설계 6.7). dataVersion 은 후보로 쓴 이력서 (id, version) 목록의 해시, searchConfig 는 신호·가중 구성 상수. */
    public record Context(String dataVersion, String searchConfig) {
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

    /** REQUIREMENT 는 맞은 키·칸·공고 인용, LEXICAL 은 그 신호 안 순위와 맞은 단어(최대 5개). */
    public record Why(String signal, Integer rank, List<String> matched, Field field, String quote) {
    }

    /** source 는 대표 행(가장 최근에 고친 이력서), alsoIn 은 같은 묶음의 다른 행이다. */
    public record Result(int rank, String type, ExperienceKind kind, String title, String period, Source source,
                         List<Source> alsoIn, List<Why> why) {
    }
}
