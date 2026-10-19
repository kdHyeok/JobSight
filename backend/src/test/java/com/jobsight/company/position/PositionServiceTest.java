package com.jobsight.company.position;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPosting;
import com.jobsight.company.posting.JobPostingAttributes;
import com.jobsight.company.posting.JobPostingRepository;
import com.jobsight.company.reference.ReferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    @Mock private PositionRepository repository;
    @Mock private JobPostingRepository postingRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private ReferenceRepository referenceRepository;
    @Mock private CurrentUser currentUser;

    private PositionService service;

    @BeforeEach
    void setUp() {
        service = new PositionService(repository, postingRepository, companyRepository, referenceRepository, currentUser);
        given(currentUser.id()).willReturn(OWNER_ID);
    }

    @Test
    void deletingNonLastPositionKeepsPosting() {
        JobPosting posting = posting();
        Position position = position(posting);
        given(repository.findByIdAndOwnerId(position.getId(), OWNER_ID))
                .willReturn(Optional.of(position), Optional.of(position));
        given(postingRepository.findOwnedForUpdate(posting.getId(), OWNER_ID)).willReturn(Optional.of(posting));
        given(repository.countByPostingIdAndOwnerId(posting.getId(), OWNER_ID)).willReturn(2L);

        service.delete(position.getId());

        verify(postingRepository).setLocalLockTimeout();
        verify(repository).delete(position);
        verify(postingRepository, never()).delete(posting);
    }

    @Test
    void deletingLastPositionDeletesPosting() {
        JobPosting posting = posting();
        Position position = position(posting);
        given(repository.findByIdAndOwnerId(position.getId(), OWNER_ID))
                .willReturn(Optional.of(position), Optional.of(position));
        given(postingRepository.findOwnedForUpdate(posting.getId(), OWNER_ID)).willReturn(Optional.of(posting));
        given(repository.countByPostingIdAndOwnerId(posting.getId(), OWNER_ID)).willReturn(1L);

        service.delete(position.getId());

        verify(postingRepository).delete(posting);
        verify(repository, never()).delete(position);
    }

    @Test
    void deletingOtherOwnersPositionReturnsNotFound() {
        UUID id = UUID.randomUUID();
        given(repository.findByIdAndOwnerId(id, OWNER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);

        verify(postingRepository, never()).findOwnedForUpdate(any(), any());
        verify(repository, never()).delete(any());
        verify(postingRepository, never()).delete(any());
    }

    @Test
    void deletingPositionWhosePostingIsNotOwnedReturnsNotFound() {
        JobPosting posting = posting();
        Position position = position(posting);
        given(repository.findByIdAndOwnerId(position.getId(), OWNER_ID)).willReturn(Optional.of(position));
        given(postingRepository.findOwnedForUpdate(posting.getId(), OWNER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(position.getId())).isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(position);
        verify(postingRepository, never()).delete(any());
    }

    @Test
    void repeatedDeleteDoesNotDeletePostingAfterTargetDisappearsWhileWaiting() {
        JobPosting posting = posting();
        Position position = position(posting);
        given(repository.findByIdAndOwnerId(position.getId(), OWNER_ID))
                .willReturn(Optional.of(position), Optional.empty());
        given(postingRepository.findOwnedForUpdate(posting.getId(), OWNER_ID)).willReturn(Optional.of(posting));

        assertThatThrownBy(() -> service.delete(position.getId())).isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).countByPostingIdAndOwnerId(any(), any());
        verify(repository, never()).delete(any());
        verify(postingRepository, never()).delete(any());
    }

    @Test
    void lockTimeoutReturnsRetryableConflict() {
        JobPosting posting = posting();
        Position position = position(posting);
        given(repository.findByIdAndOwnerId(position.getId(), OWNER_ID)).willReturn(Optional.of(position));
        given(postingRepository.findOwnedForUpdate(posting.getId(), OWNER_ID))
                .willThrow(new CannotAcquireLockException("timeout"));

        Throwable thrown = catchThrowable(() -> service.delete(position.getId()));

        assertThat(thrown).isInstanceOfSatisfying(ApiRuleException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("POSITION_DELETE_BUSY"));
        verify(repository, never()).delete(any());
        verify(postingRepository, never()).delete(any());
    }

    private static JobPosting posting() {
        return new JobPosting(OWNER_ID, new JobPostingAttributes(
                COMPANY_ID, "공고", null, EmploymentType.FULL_TIME, null,
                ApplicationStatus.INTERESTED, null, null, List.of()));
    }

    private static Position position(JobPosting posting) {
        return new Position(OWNER_ID, posting.getId(), "백엔드 개발");
    }
}
