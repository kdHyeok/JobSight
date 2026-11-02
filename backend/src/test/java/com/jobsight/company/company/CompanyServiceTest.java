package com.jobsight.company.company;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.dto.CompanyRequest;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.posting.JobPostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private CompanyRepository repository;
    @Mock
    private JobPostingService postings;
    @Mock
    private PositionRepository positionRepository;
    @Mock
    private CurrentUser currentUser;

    private CompanyService service;

    @BeforeEach
    void setUp() {
        service = new CompanyService(repository, postings, positionRepository, currentUser);
    }

    private static CompanyRequest request(String name, List<String> industries, String website, String summary) {
        return new CompanyRequest(name, website, industries, null, null, null, null, null, null, null, summary, null, null);
    }

    @Test
    void createNormalizesBlankOptionalFields() {
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.save(any(Company.class))).willAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.onCreate();
            return company;
        });

        var response = service.create(request("  테스트 기업  ", List.of(), "", " 소개 "));

        assertThat(response.name()).isEqualTo("테스트 기업");
        assertThat(response.websiteUrl()).isNull();
        assertThat(response.summary()).isEqualTo("소개");
        assertThat(response.industries()).isEmpty();
    }

    /** 업종은 다중 입력이고, 빈 값·중복은 걸러내되 입력 순서는 유지한다. */
    @Test
    void createKeepsIndustryOrderAndDropsBlanksAndDuplicates() {
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.save(any(Company.class))).willAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.onCreate();
            return company;
        });

        var response = service.create(
                request("테스트", List.of(" IT서비스 ", "", "금융권", "IT서비스", "  "), null, null));

        assertThat(response.industries()).containsExactly("IT서비스", "금융권");
    }

    @Test
    void missingCompanyThrowsNotFound() {
        UUID id = UUID.randomUUID();
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.findByIdAndOwnerId(id, OWNER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    /**
     * 계정별 데이터 분리의 핵심 검증.
     * 다른 계정 소유의 id 로 접근하면 소유자 조건이 붙은 쿼리가 비어 돌아오므로
     * 읽기·삭제 모두 존재를 알리지 않고 404 가 된다.
     */
    @Test
    void otherOwnersCompanyIsNotReachable() {
        UUID otherOwnersCompanyId = UUID.randomUUID();
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.findByIdAndOwnerId(otherOwnersCompanyId, OWNER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(otherOwnersCompanyId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(otherOwnersCompanyId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listIsScopedToCurrentOwner() {
        given(currentUser.id()).willReturn(OWNER_ID);
        given(repository.findAllByOwnerIdOrderByUpdatedAtDesc(OWNER_ID)).willReturn(List.of());

        assertThat(service.findAll()).isEmpty();
    }
}
