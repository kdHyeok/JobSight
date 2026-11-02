package com.jobsight.company.selfintro;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * competencies 표(V23)와 enum Competency 가 다르면 기동을 멈춘다.
 * 다른 채로 뜨면 저장할 때에야 FK 위반이나 엉뚱한 FIT 판정이 난다. JdbcTemplate 은 Flyway 뒤에 만들어진다.
 */
@Component
class CompetencyCatalogCheck {
    CompetencyCatalogCheck(JdbcTemplate jdbc) {
        List<String> table = jdbc.queryForList(
                "select code || '|' || name || '|' || question_only || '|' || search_mode from competencies order by sort_order",
                String.class);
        List<String> code = Arrays.stream(Competency.values())
                .map(c -> c.name() + "|" + c.label() + "|" + c.questionOnly() + "|" + c.searchMode())
                .toList();
        if (!table.equals(code)) {
            throw new IllegalStateException("competencies 표와 Competency enum 이 다릅니다. 표=" + table + " enum=" + code);
        }
    }
}
