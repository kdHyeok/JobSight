package com.jobsight.company.posting;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.Company;
import com.jobsight.company.company.CompanyAttributes;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.posting.dto.StepRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JobPostingServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    @Mock private JobPostingRepository repository;
    @Mock private CompanyRepository companyRepository;
    @Mock private PositionRepository positionRepository;
    @Mock private CurrentUser currentUser;

    private JobPostingService service;

    @BeforeEach
    void setUp() {
        service = new JobPostingService(repository, companyRepository, positionRepository, currentUser);
    }

    private static JobPosting posting(Instant deadline, ApplicationStatus status) {
        JobPosting created = new JobPosting(OWNER_ID, new JobPostingAttributes(
                COMPANY_ID, "백엔드 개발", null, EmploymentType.FULL_TIME, deadline, status, null, null, List.of()));
        created.onCreate();
        return created;
    }

    private static JobPostingRequest request(UUID companyId, String companyName,
                                             List<PositionNameRequest> positions, List<StepRequest> steps) {
        return new JobPostingRequest(companyId, companyName, "2026 신입 공채", null,
                EmploymentType.FULL_TIME, null, ApplicationStatus.INTERESTED, null, null, positions, steps);
    }

    /** 마감이 지난 관심·작성중 공고는 목록 조회 시점에 보관함으로 옮겨진다. */
    @Test
    void expiredUnsubmittedPostingIsArchivedOnList() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        JobPosting interested = posting(past, ApplicationStatus.INTERESTED);
        JobPosting drafting = posting(past, ApplicationStatus.DRAFTING);
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.findPendingAutoArchive(any(), any())).willReturn(List.of(interested, drafting));
        given(repository.findOpen(OWNER_ID)).willReturn(List.of());

        service.findOpen();

        assertThat(interested.isArchived()).isTrue();
        assertThat(drafting.isArchived()).isTrue();
        verify(repository).saveAll(anyList());
    }

    /** 낸 공고는 마감이 지나도 결과를 기다리는 중이라 보관하지 않는다. 작성중은 이제 보관 대상이다. */
    @Test
    void onlyUnsubmittedStatusesAutoArchive() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant now = Instant.now();

        assertThat(posting(past, ApplicationStatus.INTERESTED).shouldAutoArchive(now)).isTrue();
        assertThat(posting(past, ApplicationStatus.DRAFTING).shouldAutoArchive(now)).isTrue();
        assertThat(posting(past, ApplicationStatus.SUBMITTED).shouldAutoArchive(now)).isFalse();
        assertThat(posting(past, ApplicationStatus.CLOSED).shouldAutoArchive(now)).isFalse();
        assertThat(posting(null, ApplicationStatus.DOCUMENT_REJECTED).isArchived()).isTrue();
        assertThat(posting(now.plus(1, ChronoUnit.DAYS), ApplicationStatus.INTERESTED).shouldAutoArchive(now)).isFalse();
        assertThat(posting(null, ApplicationStatus.INTERESTED).shouldAutoArchive(now)).isFalse();
    }

    /** 지원완료로 옮기면 보관함에서 꺼낸다. 지원 중인 공고가 보관함에 있으면 앞뒤가 맞지 않는다. */
    @Test
    void submittingRestoresFromArchive() {
        JobPosting archived = posting(Instant.now().minus(1, ChronoUnit.DAYS), ApplicationStatus.DRAFTING);
        archived.archive(Instant.now());

        archived.changeStatus(ApplicationStatus.SUBMITTED);

        assertThat(archived.isArchived()).isFalse();
    }

    @Test
    void rejectedStatusArchivesAndChangingBackRestores() {
        JobPosting posting = posting(Instant.now().plus(1, ChronoUnit.DAYS), ApplicationStatus.SUBMITTED);

        posting.changeStatus(ApplicationStatus.WRITTEN_TEST_REJECTED);
        assertThat(posting.isArchived()).isTrue();

        posting.changeStatus(ApplicationStatus.INTERVIEW_PREP);
        assertThat(posting.isArchived()).isFalse();
    }

    /** 절차 단계는 배열 순서가 진행 순서고, 결과는 seq 로 바꾼다. */
    @Test
    void stepsKeepOrderAndResultChangesBySeq() {
        JobPosting p = new JobPosting(OWNER_ID, new JobPostingAttributes(
                COMPANY_ID, "공채", null, EmploymentType.FULL_TIME, null, ApplicationStatus.SUBMITTED, null, null,
                List.of(new RecruitmentStep("서류", null, null, null),
                        new RecruitmentStep("인적성", null, null, null),
                        new RecruitmentStep("면접", null, null, null))));

        p.changeStepResult(0, StepResult.PASSED);
        p.changeStepResult(1, StepResult.PASSED);

        assertThat(p.getSteps()).extracting(RecruitmentStep::getName).containsExactly("서류", "인적성", "면접");
        assertThat(p.getSteps()).extracting(RecruitmentStep::getResult)
                .containsExactly(StepResult.PASSED, StepResult.PASSED, StepResult.UPCOMING);
        assertThatThrownBy(() -> p.changeStepResult(3, StepResult.PASSED)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    /** 직접 입력한 회사명은 기존 기업을 찾고(공백·대소문자 무시), 없으면 만든다. */
    @Test
    void directCompanyNameFindsExistingCompany() {
        given(currentUser.id()).willReturn(OWNER_ID);
        Company existing = new Company(OWNER_ID, CompanyAttributes.nameOnly("Naver Cloud"));
        given(companyRepository.findByOwnerIdAndNameKey(OWNER_ID, "navercloud")).willReturn(Optional.of(existing));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.create(request(null, "  naver  cloud ", List.of(), List.of()));

        verify(companyRepository, never()).save(any());
        ArgumentCaptor<JobPosting> saved = ArgumentCaptor.forClass(JobPosting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isEqualTo(existing.getId());
    }

    @Test
    void unknownCompanyNameCreatesCompany() {
        given(currentUser.id()).willReturn(OWNER_ID);
        given(companyRepository.findByOwnerIdAndNameKey(any(), any())).willReturn(Optional.empty());
        given(companyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.create(request(null, "새 회사", List.of(), List.of()));

        ArgumentCaptor<Company> created = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(created.capture());
        assertThat(created.getValue().getName()).isEqualTo("새 회사");
    }

    /** 직무를 비우면 제목 이름의 직무 하나가 생긴다 — 공고는 직무가 1개 이상. */
    @Test
    void emptyPositionsCreateOneNamedAfterTitle() {
        given(currentUser.id()).willReturn(OWNER_ID);
        given(companyRepository.findByIdAndOwnerId(COMPANY_ID, OWNER_ID))
                .willReturn(Optional.of(new Company(OWNER_ID, CompanyAttributes.nameOnly("회사"))));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.create(request(COMPANY_ID, null, null, List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Position>> positions = ArgumentCaptor.forClass(List.class);
        verify(positionRepository).saveAll(positions.capture());
        assertThat(positions.getValue()).extracting(Position::getName).containsExactly("2026 신입 공채");
    }

    /** 공고 수정은 공고 행을 잠근 조회로 읽는다(docs/job-postings.md 불변 조건 10). */
    private void givenOwnedPosting(JobPosting posting, Position... positions) {
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.findOwnedForUpdate(posting.getId(), OWNER_ID)).willReturn(Optional.of(posting));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(companyRepository.findByIdAndOwnerId(COMPANY_ID, OWNER_ID))
                .willReturn(Optional.of(new Company(OWNER_ID, CompanyAttributes.nameOnly("회사"))));
        given(positionRepository.findAllByPostingIdAndOwnerId(posting.getId(), OWNER_ID)).willReturn(List.of(positions));
    }

    /** positions·steps 를 빼고 보낸 수정은 기존 직무와 절차를 그대로 둔다 — null 은 "유지"다(설계 1.4 T3). */
    @Test
    void nullPositionsAndStepsKeepExisting() {
        JobPosting existing = new JobPosting(OWNER_ID, new JobPostingAttributes(
                COMPANY_ID, "2026 신입 공채", null, EmploymentType.FULL_TIME, null, ApplicationStatus.SUBMITTED, null, null,
                List.of(new RecruitmentStep("서류", null, null, null), new RecruitmentStep("면접", null, null, null))));
        Position backend = new Position(OWNER_ID, existing.getId(), "백엔드 개발");
        givenOwnedPosting(existing, backend);

        service.update(existing.getId(), request(COMPANY_ID, null, null, null));

        assertThat(existing.getSteps()).extracting(RecruitmentStep::getName).containsExactly("서류", "면접");
        verify(positionRepository, never()).saveAll(any());
        verify(positionRepository, never()).deleteAll(any());
    }

    /**
     * id 없이 이름만 보내도 같은 이름(공백·대소문자 무시)의 기존 직무를 그대로 쓴다.
     * 지웠다 다시 만들면 resume_positions·자소서 직무 연결이 ON DELETE CASCADE 로 끊긴다.
     */
    @Test
    void nameOnlyPositionKeepsExistingPosition() {
        JobPosting existing = posting(null, ApplicationStatus.INTERESTED);
        Position backend = new Position(OWNER_ID, existing.getId(), "백엔드 개발");
        Position frontend = new Position(OWNER_ID, existing.getId(), "프론트엔드");
        givenOwnedPosting(existing, backend, frontend);

        service.update(existing.getId(), request(COMPANY_ID, null, List.of(
                new PositionNameRequest(null, " 백엔드개발 "), new PositionNameRequest(null, "데이터 엔지니어")), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Position>> saved = ArgumentCaptor.forClass(List.class);
        verify(positionRepository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0)).isSameAs(backend);
        assertThat(saved.getValue()).extracting(Position::getName).containsExactly("백엔드개발", "데이터 엔지니어");
        verify(positionRepository).deleteAll(List.of(frontend));
    }

    /** [] 는 비움이다. 절차는 사라지고 직무는 제목 이름 하나만 남는데, 그 이름의 기존 직무가 있으면 그것을 쓴다. */
    @Test
    void emptyListsClearStepsAndLeaveOnlyTitlePosition() {
        JobPosting existing = new JobPosting(OWNER_ID, new JobPostingAttributes(
                COMPANY_ID, "2026 신입 공채", null, EmploymentType.FULL_TIME, null, ApplicationStatus.INTERESTED, null, null,
                List.of(new RecruitmentStep("서류", null, null, null))));
        Position titled = new Position(OWNER_ID, existing.getId(), "2026 신입 공채");
        Position other = new Position(OWNER_ID, existing.getId(), "프론트엔드");
        givenOwnedPosting(existing, titled, other);

        service.update(existing.getId(), request(COMPANY_ID, null, List.of(), List.of()));

        assertThat(existing.getSteps()).isEmpty();
        verify(positionRepository).saveAll(List.of(titled));
        verify(positionRepository).deleteAll(List.of(other));
    }

    @SuppressWarnings("unchecked")
    private List<Position> savedPositions() {
        ArgumentCaptor<List<Position>> saved = ArgumentCaptor.forClass(List.class);
        verify(positionRepository).saveAll(saved.capture());
        return saved.getValue();
    }

    /**
     * id 로 지목된 직무는 앞선 항목의 이름 맞춤에 뺏기지 않는다 — id 를 먼저 다 고른다.
     * 항목 순서대로 한 번에 맞추면 첫 항목이 X 를 이름으로 가져가 X 의 resume_positions 연결이 엉뚱한 이름에 붙는다.
     */
    @Test
    void idClaimsPositionBeforeNameMatching() {
        JobPosting existing = posting(null, ApplicationStatus.INTERESTED);
        Position backend = new Position(OWNER_ID, existing.getId(), "백엔드");
        Position frontend = new Position(OWNER_ID, existing.getId(), "프론트엔드");
        givenOwnedPosting(existing, backend, frontend);

        service.update(existing.getId(), request(COMPANY_ID, null, List.of(
                new PositionNameRequest(null, "백엔드"), new PositionNameRequest(backend.getId(), "서버")), null));

        List<Position> saved = savedPositions();
        assertThat(saved.get(1)).isSameAs(backend);
        assertThat(saved.get(0)).isNotSameAs(backend).isNotSameAs(frontend);
        assertThat(saved).extracting(Position::getName).containsExactly("백엔드", "서버");
        verify(positionRepository).deleteAll(List.of(frontend));
    }

    /** 웹 폼 경로: id 로 보낸 직무는 이름만 바뀌고, 빠진 직무는 삭제된다. 삭제되는 직무가 지원 직무면 비운다. */
    @Test
    void idRenamesAndMissingPositionIsDeletedClearingTarget() {
        JobPosting existing = posting(null, ApplicationStatus.INTERESTED);
        Position backend = new Position(OWNER_ID, existing.getId(), "백엔드");
        Position frontend = new Position(OWNER_ID, existing.getId(), "프론트엔드");
        existing.setTargetPositionId(frontend.getId());
        givenOwnedPosting(existing, backend, frontend);

        service.update(existing.getId(), request(COMPANY_ID, null, List.of(
                new PositionNameRequest(backend.getId(), "서버 개발")), null));

        assertThat(savedPositions()).containsExactly(backend);
        assertThat(backend.getName()).isEqualTo("서버 개발");
        verify(positionRepository).deleteAll(List.of(frontend));
        assertThat(existing.getTargetPositionId()).isNull();
    }

    /** 지원 직무가 이름으로 맞춰져 같은 행이 남으면 지원 직무도 그대로다. */
    @Test
    void targetPositionStaysWhenMatchedByName() {
        JobPosting existing = posting(null, ApplicationStatus.INTERESTED);
        Position backend = new Position(OWNER_ID, existing.getId(), "백엔드");
        Position frontend = new Position(OWNER_ID, existing.getId(), "프론트엔드");
        existing.setTargetPositionId(backend.getId());
        givenOwnedPosting(existing, backend, frontend);

        service.update(existing.getId(), request(COMPANY_ID, null, List.of(new PositionNameRequest(null, "백엔드")), null));

        assertThat(savedPositions()).containsExactly(backend);
        verify(positionRepository).deleteAll(List.of(frontend));
        assertThat(existing.getTargetPositionId()).isEqualTo(backend.getId());
    }

    /** 같은 id 가 두 번 오면 두 번째는 새 직무다. 이 공고의 직무가 아닌 id 는 없는 것으로 보고 이름으로 맞춘다. */
    @Test
    void repeatedOrForeignIdDoesNotReuseRow() {
        JobPosting existing = posting(null, ApplicationStatus.INTERESTED);
        Position backend = new Position(OWNER_ID, existing.getId(), "백엔드");
        givenOwnedPosting(existing, backend);

        service.update(existing.getId(), request(COMPANY_ID, null, List.of(
                new PositionNameRequest(backend.getId(), "백엔드"),
                new PositionNameRequest(backend.getId(), "서버"),
                new PositionNameRequest(UUID.randomUUID(), "데이터")), null));

        List<Position> saved = savedPositions();
        assertThat(saved.get(0)).isSameAs(backend);
        assertThat(saved.get(1)).isNotSameAs(backend);
        assertThat(saved.get(2)).isNotSameAs(backend);
        assertThat(saved).extracting(Position::getName).containsExactly("백엔드", "서버", "데이터");
        verify(positionRepository, never()).deleteAll(any());
    }

    /** 다른 계정 소유의 공고는 존재를 알리지 않고 404. */
    @Test
    void otherOwnersPostingIsNotReachable() {
        UUID id = UUID.randomUUID();
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.findByIdAndOwnerId(id, OWNER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
        // 잠금 조회도 소유자 조건으로 찾는다. 남의 공고는 잠그지도 않고 404.
        assertThatThrownBy(() -> service.update(id, request(COMPANY_ID, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
