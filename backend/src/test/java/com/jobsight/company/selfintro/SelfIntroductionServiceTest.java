package com.jobsight.company.selfintro;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.selfintro.dto.SelfIntroductionConfirmRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SelfIntroductionServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID POSTING_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock SelfIntroductionRepository repository;
    @Mock ResumeRepository resumeRepository;
    @Mock PositionRepository positionRepository;
    @Mock CurrentUser currentUser;
    SelfIntroductionService service;

    @BeforeEach
    void setUp() {
        service = new SelfIntroductionService(repository, resumeRepository, positionRepository, currentUser);
        lenient().when(currentUser.id()).thenReturn(OWNER_ID);
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            SelfIntroduction value = invocation.getArgument(0);
            value.onCreate();
            return value;
        });
    }

    @Test
    void bm25SearchesQuestionAndAnswerAndRanksFrequency() {
        SelfIntroduction frequent = value("협업 경험", "협업 과정에서 협업 규칙을 정했습니다.");
        SelfIntroduction once = value("문제 해결", "협업으로 해결했습니다.");
        SelfIntroduction absent = value("성장 과정", "꾸준히 학습했습니다.");

        assertThat(SelfIntroductionService.rank(List.of(once, absent, frequent), "협업"))
                .containsExactly(frequent, once);
    }

    @Test
    void createRequiresOwnedResumeAndNormalizesAnswer() {
        Resume resume = new Resume(OWNER_ID, "지원용", "{}");
        given(resumeRepository.findAllByIdInAndOwnerId(anyList(), eq(OWNER_ID))).willReturn(List.of(resume));

        var created = service.create(new SelfIntroductionCreateRequest(List.of(resume.getId()), null, null,
                "  지원 동기는? ", "  답변  "));

        assertThat(created.question()).isEqualTo("지원 동기는?");
        assertThat(created.answer()).isEqualTo("답변");
        assertThat(created.positionLinks()).isEmpty();
    }

    /** 생성은 이력서 또는 직무가 1개 이상이어야 하고, 수정은 0개도 된다(설계 5.3, D4). */
    @Test
    void createNeedsResumeOrPositionButUpdateAllowsNone() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new SelfIntroductionCreateRequest(List.of(), List.of(), null, "질문", null)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("linked");
        assertThat(validator.validate(new SelfIntroductionCreateRequest(null, null, null, "질문", null))).hasSize(1);
        assertThat(validator.validate(new SelfIntroductionCreateRequest(null, List.of(UUID.randomUUID()), null, "질문", null)))
                .isEmpty();
        assertThat(validator.validate(new SelfIntroductionUpdateRequest(List.of(), null, null, "질문", null))).isEmpty();
    }

    /** 웹 세션 요청이 만든 연결은 USER 다. 직무만 있어도 만들 수 있다. */
    @Test
    void webCreateWithPositionOnlyRecordsUserLinks() {
        Position position = position("백엔드");
        given(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).willReturn(List.of(position));

        var created = service.create(new SelfIntroductionCreateRequest(null, List.of(position.getId()),
                List.of(Competency.MOTIVATION), "지원 동기", null));

        assertThat(created.resumeIds()).isEmpty();
        assertThat(created.positionLinks()).containsExactly(
                new SelfIntroductionResponse.PositionLinkView(position.getId(), LinkSource.USER));
        assertThat(created.questionTypes()).containsExactly(
                new SelfIntroductionResponse.QuestionTypeView(Competency.MOTIVATION, LinkSource.USER));
    }

    /** MCP 토큰 요청이 만든 연결은 항상 AI 다 — 확정은 웹에서만 한다(설계 3절 규칙 2·3). */
    @Test
    void mcpCreateRecordsAiLinks() {
        Position position = position("백엔드");
        given(currentUser.viaMcp()).willReturn(true);
        given(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).willReturn(List.of(position));

        var created = service.create(new SelfIntroductionCreateRequest(null, List.of(position.getId()),
                List.of(Competency.ASPIRATION), "입사 후 포부", null));

        assertThat(created.positionLinks()).extracting(SelfIntroductionResponse.PositionLinkView::source)
                .containsExactly(LinkSource.AI);
        assertThat(created.questionTypes()).extracting(SelfIntroductionResponse.QuestionTypeView::source)
                .containsExactly(LinkSource.AI);
    }

    /** 다른 계정의 직무 id 는 소유자 조건으로 걸러져 404 다. 저장하지 않는다. */
    @Test
    void foreignPositionIsNotFound() {
        UUID foreign = UUID.randomUUID();
        given(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).willReturn(List.of());

        assertThatThrownBy(() -> service.create(new SelfIntroductionCreateRequest(null, List.of(foreign), null, "질문", null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(foreign.toString());
        verify(repository, never()).save(any());
    }

    /**
     * 빈 이력서 목록으로 수정해도 ids.get(0) 예외가 나지 않는다. 이미 있던 연결은 출처를 유지하고 새 연결만
     * 요청 주체의 출처를 단다. 웹이 저장해도 AI 기록이 저절로 확정되지 않는다.
     */
    @Test
    void updateAllowsNoResumesAndKeepsSourceOfKeptLinks() {
        Position aiLinked = position("백엔드");
        Position added = position("데이터");
        SelfIntroduction value = value("지원 동기", "답변");
        value.linkPositions(Set.of(aiLinked.getId()), LinkSource.AI);
        given(repository.findByIdAndOwnerId(value.getId(), OWNER_ID)).willReturn(Optional.of(value));
        given(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).willReturn(List.of(aiLinked, added));

        var updated = service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                List.of(aiLinked.getId(), added.getId()), null, "지원 동기", "고친 답변"));

        assertThat(updated.resumeIds()).isEmpty();
        assertThat(updated.positionLinks()).containsExactlyInAnyOrder(
                new SelfIntroductionResponse.PositionLinkView(aiLinked.getId(), LinkSource.AI),
                new SelfIntroductionResponse.PositionLinkView(added.getId(), LinkSource.USER));
        verify(resumeRepository, never()).findAllByIdInAndOwnerId(anyList(), any());
    }

    /** positionIds·questionTypes 를 보내지 않는 기존 웹 경로(null)는 연결을 그대로 둔다. */
    @Test
    void updateWithoutLinkFieldsKeepsLinks() {
        UUID positionId = UUID.randomUUID();
        SelfIntroduction value = value("지원 동기", "답변");
        value.linkPositions(Set.of(positionId), LinkSource.AI);
        value.linkQuestionTypes(Set.of(Competency.MOTIVATION), LinkSource.AI);
        Resume resume = new Resume(OWNER_ID, "지원용", "{}");
        given(repository.findByIdAndOwnerId(value.getId(), OWNER_ID)).willReturn(Optional.of(value));
        given(resumeRepository.findAllByIdInAndOwnerId(anyList(), eq(OWNER_ID))).willReturn(List.of(resume));

        var updated = service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(resume.getId()),
                null, null, "지원 동기", "답변"));

        assertThat(updated.positionLinks()).containsExactly(
                new SelfIntroductionResponse.PositionLinkView(positionId, LinkSource.AI));
        assertThat(updated.questionTypes()).containsExactly(
                new SelfIntroductionResponse.QuestionTypeView(Competency.MOTIVATION, LinkSource.AI));
        verify(positionRepository, never()).findAllByIdInAndOwnerId(anyCollection(), any());
    }

    /** 웹의 ✓: AI 기록을 USER 로 바꾼다. 연결돼 있지 않은 대상을 확정하려 하면 404 다. */
    @Test
    void confirmTurnsAiIntoUserAndRejectsUnlinkedTarget() {
        UUID positionId = UUID.randomUUID();
        SelfIntroduction value = value("지원 동기", "답변");
        value.linkPositions(Set.of(positionId), LinkSource.AI);
        value.linkQuestionTypes(Set.of(Competency.MOTIVATION), LinkSource.AI);
        given(repository.findByIdAndOwnerId(value.getId(), OWNER_ID)).willReturn(Optional.of(value));

        var confirmed = service.confirm(value.getId(),
                new SelfIntroductionConfirmRequest(List.of(positionId), List.of(Competency.MOTIVATION)));

        assertThat(confirmed.positionLinks()).containsExactly(
                new SelfIntroductionResponse.PositionLinkView(positionId, LinkSource.USER));
        assertThat(confirmed.questionTypes()).containsExactly(
                new SelfIntroductionResponse.QuestionTypeView(Competency.MOTIVATION, LinkSource.USER));
        assertThatThrownBy(() -> service.confirm(value.getId(),
                new SelfIntroductionConfirmRequest(List.of(UUID.randomUUID()), null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("LINK_NOT_FOUND"));
    }

    private static Position position(String name) {
        return new Position(OWNER_ID, POSTING_ID, name);
    }

    private static SelfIntroduction value(String question, String answer) {
        SelfIntroduction value = new SelfIntroduction(OWNER_ID, Set.of(RESUME_ID), question, answer);
        value.onCreate();
        return value;
    }
}
