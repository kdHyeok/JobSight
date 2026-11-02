package com.jobsight.company.experience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 묶음 키와 REQUIREMENT 요구 키가 같은 정규화를 쓴다 — 설계 2.5, 7절 3단계. */
class KeyNormalizerTest {

    @Test
    void foldsCaseSpacesAndSymbols() {
        assertThat(KeyNormalizer.key("  Spring Boot ")).isEqualTo("springboot");
        assertThat(KeyNormalizer.key("Node.js")).isEqualTo("nodejs");
        assertThat(KeyNormalizer.key("ＡＷＳ　ＥＣ２")).isEqualTo("awsec2");
        assertThat(KeyNormalizer.key("C++")).isEqualTo("c++");
        assertThat(KeyNormalizer.key("C#")).isEqualTo("c#");
        assertThat(KeyNormalizer.key("결제 정산-배치!")).isEqualTo("결제정산배치");
        assertThat(KeyNormalizer.key(null)).isEmpty();
    }

    @Test
    void appliesSeedAliases() {
        assertThat(KeyNormalizer.skill("K8s")).isEqualTo("kubernetes");
        assertThat(KeyNormalizer.skill("JS")).isEqualTo("javascript");
        assertThat(KeyNormalizer.skill("React.js")).isEqualTo("react");
        assertThat(KeyNormalizer.skill("자바")).isEqualTo("java");
        assertThat(KeyNormalizer.skill("Kafka")).isEqualTo("kafka");
        // 별칭이 없는 값은 정규화만 한다.
        assertThat(KeyNormalizer.skill("Planetory")).isEqualTo("planetory");
    }

    @Test
    void splitsTechStackText() {
        assertThat(KeyNormalizer.skills("Java, Spring Boot / k8s · Kafka | kafka\nAWS EC2"))
                .containsExactly("java", "springboot", "kubernetes", "kafka", "awsec2");
        assertThat(KeyNormalizer.skills(null)).isEmpty();
    }

    @Test
    void mentionsReadAsciiTokensAliasesBigramsAndKoreanTerms() {
        assertThat(KeyNormalizer.mentions("AWS EC2 위에서 Kafka를 k8s로 운영, Spring Boot 사용"))
                .contains("aws", "ec2", "awsec2", "kafka", "kubernetes", "spring", "springboot");
        assertThat(KeyNormalizer.mentions("쿠버네티스 클러스터를 운영")).contains("kubernetes");
        // 짧은 영문 별칭은 단어 안 부분 문자열로 맞추지 않는다.
        assertThat(KeyNormalizer.dictionaryTerms("going forward with jsonb")).doesNotContain("go", "javascript");
        assertThat(KeyNormalizer.dictionaryTerms("Go 언어와 Kafka, 그리고 사내 도구")).containsExactlyInAnyOrder("go", "kafka");
    }

    @Test
    void yearMonthKeepsOnlyNumbers() {
        assertThat(KeyNormalizer.yearMonth("2024.03")).isEqualTo("202403");
        assertThat(KeyNormalizer.yearMonth("2024.3")).isEqualTo("202403");
        assertThat(KeyNormalizer.yearMonth("2024년 3월")).isEqualTo("202403");
        assertThat(KeyNormalizer.yearMonth("2024")).isEqualTo("2024");
        assertThat(KeyNormalizer.yearMonth("현재")).isEmpty();
        assertThat(KeyNormalizer.yearMonth(null)).isEmpty();
    }
}
