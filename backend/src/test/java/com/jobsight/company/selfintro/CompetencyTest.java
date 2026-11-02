package com.jobsight.company.selfintro;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 저장하지 않은 문항 유형은 정규식 상위 2개를 신뢰도와 함께 추정한다(설계 4.1). */
class CompetencyTest {

    @Test
    void estimatesTopTwoWithShareOfHits() {
        assertThat(Competency.estimate("지원 동기와 입사 후 포부를 작성해 주세요."))
                .containsExactly(new Competency.Estimate(Competency.ASPIRATION, 0.67),
                        new Competency.Estimate(Competency.MOTIVATION, 0.33));
        assertThat(Competency.estimate("본인 성격의 장단점을 기술하시오."))
                .containsExactly(new Competency.Estimate(Competency.PERSONALITY, 1.0));
    }

    /** 맞는 키워드가 없으면 지어내지 않고 비운다. 같은 일치 수는 표 순서(sort_order)로 정한다. */
    @Test
    void noHitMeansNoEstimateAndTiesFollowCatalogOrder() {
        assertThat(Competency.estimate("자유롭게 작성해 주세요.")).isEmpty();
        assertThat(Competency.estimate(null)).isEmpty();
        assertThat(Competency.estimate("갈등을 해결한 경험을 쓰세요."))
                .containsExactly(new Competency.Estimate(Competency.PROBLEM_SOLVING, 0.5),
                        new Competency.Estimate(Competency.CONFLICT, 0.5));
        // "기술하시오"의 '기술'은 동사다. 기술 전문성으로 세지 않는다.
        assertThat(Competency.estimate("갈등 경험을 기술하시오."))
                .extracting(Competency.Estimate::code).containsExactly(Competency.CONFLICT);
    }
}
