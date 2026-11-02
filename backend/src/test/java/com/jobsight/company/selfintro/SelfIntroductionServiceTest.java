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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

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
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
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
        assertThat(validator.validate(new SelfIntroductionUpdateRequest(List.of(), null, null, "질문", null, 0L))).isEmpty();
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
        verify(repository, never()).saveAndFlush(any());
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
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));
        given(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).willReturn(List.of(aiLinked, added));

        var updated = service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                List.of(aiLinked.getId(), added.getId()), null, "지원 동기", "고친 답변", 0L));

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
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));
        given(resumeRepository.findAllByIdInAndOwnerId(anyList(), eq(OWNER_ID))).willReturn(List.of(resume));

        var updated = service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(resume.getId()),
                null, null, "지원 동기", "답변", 0L));

        assertThat(updated.positionLinks()).containsExactly(
                new SelfIntroductionResponse.PositionLinkView(positionId, LinkSource.AI));
        assertThat(updated.questionTypes()).containsExactly(
                new SelfIntroductionResponse.QuestionTypeView(Competency.MOTIVATION, LinkSource.AI));
        verify(positionRepository, never()).findAllByIdInAndOwnerId(anyCollection(), any());
    }

    /**
     * MCP 수정은 연결을 더하기만 한다. 목록에 없는 기존 연결(확정·AI 기록)을 지우지 않는다 —
     * AI 기록의 확정과 삭제는 웹 전용이다(설계 3절 규칙 3, 5.2).
     */
    @Test
    void mcpUpdateOnlyAddsLinks() {
        Position confirmed = position("백엔드");
        Position added = position("프론트");
        SelfIntroduction value = value("지원 동기", "답변");
        value.linkPositions(Set.of(confirmed.getId()), LinkSource.USER);
        value.linkQuestionTypes(Set.of(Competency.MOTIVATION), LinkSource.USER);
        given(currentUser.viaMcp()).willReturn(true);
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));
        given(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).willReturn(List.of(added));

        var updated = service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                List.of(added.getId()), List.of(Competency.COLLABORATION), "지원 동기", "답변", 0L));

        assertThat(updated.positionLinks()).containsExactlyInAnyOrder(
                new SelfIntroductionResponse.PositionLinkView(confirmed.getId(), LinkSource.USER),
                new SelfIntroductionResponse.PositionLinkView(added.getId(), LinkSource.AI));
        assertThat(updated.questionTypes()).containsExactlyInAnyOrder(
                new SelfIntroductionResponse.QuestionTypeView(Competency.MOTIVATION, LinkSource.USER),
                new SelfIntroductionResponse.QuestionTypeView(Competency.COLLABORATION, LinkSource.AI));
    }

    /** 더한 결과가 상한(문항 유형 3개)을 넘으면 400 이고 저장하지 않는다. 빼는 것은 웹에서 한다. */
    @Test
    void mcpUpdateOverLinkLimitIsRejected() {
        SelfIntroduction value = value("지원 동기", "답변");
        value.linkQuestionTypes(Set.of(Competency.MOTIVATION, Competency.ASPIRATION, Competency.JOB_FIT), LinkSource.USER);
        given(currentUser.viaMcp()).willReturn(true);
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));

        assertThatThrownBy(() -> service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                null, List.of(Competency.COLLABORATION), "지원 동기", "답변", 0L)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("TOO_MANY_LINKS"));
        assertThat(value.getQuestionTypes()).hasSize(3);
        verify(repository, never()).saveAndFlush(any());
    }

    /** 웹의 ✓: AI 기록을 USER 로 바꾼다. 연결돼 있지 않은 대상을 확정하려 하면 404 다. */
    @Test
    void confirmTurnsAiIntoUserAndRejectsUnlinkedTarget() {
        UUID positionId = UUID.randomUUID();
        SelfIntroduction value = value("지원 동기", "답변");
        value.linkPositions(Set.of(positionId), LinkSource.AI);
        value.linkQuestionTypes(Set.of(Competency.MOTIVATION), LinkSource.AI);
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));

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

    /** 웹 PUT 은 expectedVersion 이 필수다. 없으면 400, 다르면 409 이고 어느 쪽도 저장하지 않는다(불변 조건 12). */
    @Test
    void webUpdateRequiresMatchingVersion() {
        SelfIntroduction value = value("지원 동기", "처음 답변");
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));

        assertThatThrownBy(() -> service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                null, null, "지원 동기", "고친 답변", null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VERSION_REQUIRED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThatThrownBy(() -> service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                null, null, "지원 동기", "고친 답변", 3L)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SELF_INTRO_STALE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    // 지금 버전을 싣지 않는다 — 클라이언트가 다시 읽고 확인하게 한다.
                    assertThat(e.getMessage()).doesNotContain("0");
                });
        assertThat(value.getAnswer()).isEqualTo("처음 답변");
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * MCP 는 질문·답변·이력서 목록을 바꿀 때만 expectedVersion 이 필수다. 직무·문항 유형을 더하기만 할 때는 생략해도 된다 —
     * 더하기는 남이 저장한 내용을 지우지 못하기 때문이다. 보냈는데 다르면 409 다.
     */
    @Test
    void mcpNeedsVersionOnlyWhenRewritingText() {
        Resume resume = new Resume(OWNER_ID, "지원용", "{}");
        Position added = position("프론트");
        SelfIntroduction value = new SelfIntroduction(OWNER_ID, Set.of(resume.getId()), "지원 동기", "답변");
        value.onCreate();
        given(currentUser.viaMcp()).willReturn(true);
        given(repository.findOwnedForUpdate(value.getId(), OWNER_ID)).willReturn(Optional.of(value));
        lenient().when(resumeRepository.findAllByIdInAndOwnerId(anyList(), eq(OWNER_ID))).thenReturn(List.of(resume));
        lenient().when(positionRepository.findAllByIdInAndOwnerId(anyCollection(), eq(OWNER_ID))).thenReturn(List.of(added));

        var linked = service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(resume.getId()),
                List.of(added.getId()), List.of(Competency.COLLABORATION), " 지원 동기 ", "답변 ", null));
        assertThat(linked.positionLinks()).extracting(SelfIntroductionResponse.PositionLinkView::positionId)
                .containsExactly(added.getId());

        assertThatThrownBy(() -> service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(resume.getId()),
                null, null, "지원 동기", "AI 가 고친 답변", null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("VERSION_REQUIRED"));
        assertThatThrownBy(() -> service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(),
                null, null, "지원 동기", "답변", null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("VERSION_REQUIRED"));
        assertThatThrownBy(() -> service.update(value.getId(), new SelfIntroductionUpdateRequest(List.of(resume.getId()),
                List.of(added.getId()), null, "지원 동기", "답변", 7L)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SELF_INTRO_STALE"));
        assertThat(value.getAnswer()).isEqualTo("답변");
    }

    /** 잠금을 3초 안에 얻지 못하면 409 SELF_INTRO_BUSY 다. 확정도 같은 잠금을 탄다. */
    @Test
    void lockTimeoutIsBusy() {
        UUID id = UUID.randomUUID();
        given(repository.findOwnedForUpdate(id, OWNER_ID)).willThrow(new PessimisticLockingFailureException("lock timeout"));

        assertThatThrownBy(() -> service.update(id, new SelfIntroductionUpdateRequest(List.of(), null, null, "질문", null, 0L)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SELF_INTRO_BUSY");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThatThrownBy(() -> service.confirm(id, new SelfIntroductionConfirmRequest(null, null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SELF_INTRO_BUSY"));
        verify(repository, org.mockito.Mockito.times(2)).setLocalLockTimeout();
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
