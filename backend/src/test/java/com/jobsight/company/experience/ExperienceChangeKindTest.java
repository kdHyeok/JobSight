package com.jobsight.company.experience;

import com.jobsight.company.experience.ExperienceRevisions.ChangeKind;
import com.jobsight.company.experience.dto.ExperienceFacts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6.5 개정 판정(ExperienceBankService.changeKind)의 분기를 표로 고정한다. 정정(CORRECT)만 기업용 문구의 재검토를 부른다 —
 * 정정이 ADD 로 새면 재검토 표시가 빠진다. 입력은 정규화된 facts(배열은 [] 이상)다.
 */
class ExperienceChangeKindTest {
    private static final ExperienceFacts.Item A1 = new ExperienceFacts.Item("a1", "배치 분할", List.of());
    private static final ExperienceFacts.Item R1 = new ExperienceFacts.Item("r1", "40분 → 6분", List.of());
    private static final ExperienceFacts.Measurement ENV = new ExperienceFacts.Measurement("운영 DB", "야간 배치");

    private static ExperienceFacts facts(String period, String role, ExperienceFacts.Measurement measurement,
                                         List<ExperienceFacts.Item> actions, List<ExperienceFacts.Item> results,
                                         List<ExperienceFacts.Evidence> evidence, List<ExperienceFacts.Item> unverified,
                                         List<ExperienceFacts.Item> conflicts) {
        return new ExperienceFacts(period, role, measurement, actions, results, evidence, unverified, conflicts);
    }

    private static final ExperienceFacts BASE = facts("2025.03~2025.06", "백엔드", ENV, List.of(A1), List.of(R1),
            List.of(), List.of(), List.of());
    private static final ExperienceFacts EMPTY = facts(null, null, null, List.of(), List.of(), List.of(), List.of(), List.of());

    static Stream<Arguments> cases() {
        ExperienceFacts.Item note = new ExperienceFacts.Item("u1", "수치 출처 미확인", List.of());
        return Stream.of(
                Arguments.of("기간 수정", BASE, facts("2025.03~2025.07", "백엔드", ENV, List.of(A1), List.of(R1), List.of(), List.of(), List.of()), ChangeKind.CORRECT),
                Arguments.of("기간 삭제", BASE, facts(null, "백엔드", ENV, List.of(A1), List.of(R1), List.of(), List.of(), List.of()), ChangeKind.CORRECT),
                Arguments.of("역할 수정", BASE, facts("2025.03~2025.06", "리드", ENV, List.of(A1), List.of(R1), List.of(), List.of(), List.of()), ChangeKind.CORRECT),
                Arguments.of("측정 범위 수정", BASE, facts("2025.03~2025.06", "백엔드", new ExperienceFacts.Measurement("운영 DB", "전체"),
                        List.of(A1), List.of(R1), List.of(), List.of(), List.of()), ChangeKind.CORRECT),
                Arguments.of("행동 삭제", BASE, facts("2025.03~2025.06", "백엔드", ENV, List.of(), List.of(R1), List.of(), List.of(), List.of()), ChangeKind.CORRECT),
                Arguments.of("결과 문구 수정", BASE, facts("2025.03~2025.06", "백엔드", ENV, List.of(A1),
                        List.of(new ExperienceFacts.Item("r1", "40분 → 8분", List.of())), List.of(), List.of(), List.of()), ChangeKind.CORRECT),
                Arguments.of("새 id 추가", BASE, facts("2025.03~2025.06", "백엔드", ENV, List.of(A1,
                        new ExperienceFacts.Item("a2", "모니터링", List.of())), List.of(R1), List.of(), List.of(), List.of()), ChangeKind.ADD),
                Arguments.of("빈 칸 채우기", EMPTY, facts("2025.03", "백엔드", ENV, List.of(), List.of(), List.of(), List.of(), List.of()), ChangeKind.ADD),
                Arguments.of("미확인만 변경", BASE, facts("2025.03~2025.06", "백엔드", ENV, List.of(A1), List.of(R1), List.of(), List.of(note), List.of()), ChangeKind.ADD),
                Arguments.of("충돌만 변경", BASE, facts("2025.03~2025.06", "백엔드", ENV, List.of(A1), List.of(R1), List.of(), List.of(), List.of(note)), ChangeKind.ADD),
                Arguments.of("근거만 추가", BASE, facts("2025.03~2025.06", "백엔드", ENV, List.of(A1), List.of(R1),
                        List.of(new ExperienceFacts.Evidence("ev1", ExperienceFacts.SourceType.NOTE, null, "발췌", null, null, null, null)),
                        List.of(), List.of()), ChangeKind.ADD));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("cases")
    void judgesRevisionKind(String name, ExperienceFacts previous, ExperienceFacts next, ChangeKind expected) {
        assertThat(ExperienceBankService.changeKind(previous, next)).isEqualTo(expected);
    }
}
