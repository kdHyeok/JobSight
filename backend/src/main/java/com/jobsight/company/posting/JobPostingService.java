package com.jobsight.company.posting;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.Company;
import com.jobsight.company.company.CompanyAttributes;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.position.dto.PositionSummary;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.posting.dto.StepRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 모든 조회·변경이 현재 로그인 계정의 소유 데이터로 한정된다.
 * 소유자 id 를 컨트롤러에서 받지 않고 여기서 직접 얻으므로 스코프 누락이 발생할 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class JobPostingService {
    private final JobPostingRepository repository;
    private final CompanyRepository companyRepository;
    private final PositionRepository positionRepository;
    private final CurrentUser currentUser;

    public JobPostingService(JobPostingRepository repository,
                             CompanyRepository companyRepository,
                             PositionRepository positionRepository,
                             CurrentUser currentUser) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.positionRepository = positionRepository;
        this.currentUser = currentUser;
    }

    /**
     * 진행 중 공고를 D-day 순으로. 조회 시점에 마감 지난 관심·작성 중 공고와 탈락 공고를 보관함으로 옮긴다.
     * 스케줄러를 두지 않은 이유는 docs/decisions.md 참고.
     */
    @Transactional
    public List<JobPostingResponse> findOpen() {
        UUID ownerId = currentUser.id();
        autoArchiveExpired(ownerId);
        return toResponses(repository.findOpen(ownerId));
    }

    @Transactional
    public List<JobPostingResponse> findArchived() {
        UUID ownerId = currentUser.id();
        autoArchiveExpired(ownerId);
        return toResponses(repository.findArchived(ownerId));
    }

    public JobPostingResponse findById(UUID id) {
        return toResponses(List.of(findOwned(id))).get(0);
    }

    /** 기업 상세의 채용정보 카드. 마감이 지나지 않은 공고만. */
    public List<JobPostingResponse> findOpenByCompany(UUID companyId) {
        return toResponses(repository.findOpenByCompany(currentUser.id(), companyId, Instant.now()));
    }

    @Transactional
    public JobPostingResponse create(JobPostingRequest request) {
        JobPosting posting = new JobPosting(currentUser.id(), toAttributes(request));
        JobPosting saved = repository.save(posting);
        syncPositions(saved, request.positions());
        return toResponses(List.of(saved)).get(0);
    }

    @Transactional
    public JobPostingResponse update(UUID id, JobPostingRequest request) {
        JobPosting posting = findOwned(id);
        posting.update(toAttributes(request));
        JobPosting saved = repository.save(posting);
        syncPositions(saved, request.positions());
        return toResponses(List.of(saved)).get(0);
    }

    @Transactional
    public JobPostingResponse changeStatus(UUID id, ApplicationStatus status) {
        JobPosting posting = findOwned(id);
        posting.changeStatus(status);
        return toResponses(List.of(repository.save(posting))).get(0);
    }

    @Transactional
    public JobPostingResponse changeStepResult(UUID id, int seq, StepResult result) {
        JobPosting posting = findOwned(id);
        try {
            posting.changeStepResult(seq, result);
        } catch (IndexOutOfBoundsException e) {
            throw new ResourceNotFoundException(id);
        }
        return toResponses(List.of(repository.save(posting))).get(0);
    }

    @Transactional
    public JobPostingResponse setArchived(UUID id, boolean archived) {
        JobPosting posting = findOwned(id);
        if (archived) {
            posting.archive(Instant.now());
        } else {
            posting.restore();
        }
        return toResponses(List.of(repository.save(posting))).get(0);
    }

    @Transactional
    public void delete(UUID id) {
        // 직무·절차는 FK ON DELETE CASCADE 로 함께 지워진다.
        repository.delete(findOwned(id));
    }

    private void autoArchiveExpired(UUID ownerId) {
        Instant now = Instant.now();
        List<JobPosting> expired = repository.findPendingAutoArchive(ownerId, now);
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(posting -> posting.archive(now));
        repository.saveAll(expired);
    }

    /** 남의 데이터는 존재 자체를 알리지 않도록 403 이 아니라 404 로 응답한다. */
    private JobPosting findOwned(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /**
     * 직무 목록을 요청에 맞춘다. null 은 유지, [] 는 제목 이름의 직무 하나 — 공고는 직무가 1개 이상이다.
     * id 로 먼저 맞추고, id 없는 항목은 이름(공백 제거·소문자)으로 남은 기존 직무와 맞춘다. 못 맞춘 항목은 생성,
     * 아무 항목도 가리키지 않은 기존 직무는 삭제. 지웠다 다시 만들면 resume_positions·자소서 직무 연결이
     * CASCADE 로 끊기므로 같은 직무는 반드시 같은 행을 쓴다(설계 1.4 T3).
     */
    private void syncPositions(JobPosting posting, List<PositionNameRequest> requested) {
        UUID ownerId = currentUser.id();
        Map<UUID, Position> unclaimed = new LinkedHashMap<>();
        positionRepository.findAllByPostingIdAndOwnerId(posting.getId(), ownerId)
                .forEach(position -> unclaimed.put(position.getId(), position));
        // 새 공고는 기존 직무가 없으니 null 이어도 아래에서 제목 직무를 만든다.
        if (requested == null && !unclaimed.isEmpty()) {
            return;
        }
        List<PositionNameRequest> wanted = requested == null || requested.isEmpty()
                ? List.of(new PositionNameRequest(null, posting.getTitle()))
                : requested;

        // id 로 지목된 직무를 이름 맞춤이 먼저 가져가지 않도록 id 부터 고른다.
        Position[] matched = new Position[wanted.size()];
        for (int i = 0; i < wanted.size(); i++) {
            UUID id = wanted.get(i).id();
            matched[i] = id == null ? null : unclaimed.remove(id);
        }
        List<Position> toSave = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            String name = wanted.get(i).name().trim();
            Position position = matched[i] != null ? matched[i] : claimByName(unclaimed, name);
            if (position == null) {
                position = new Position(ownerId, posting.getId(), name);
            } else {
                position.rename(name);
            }
            toSave.add(position);
        }
        positionRepository.saveAll(toSave);

        List<Position> orphaned = List.copyOf(unclaimed.values());
        if (!orphaned.isEmpty()) {
            if (posting.getTargetPositionId() != null
                    && orphaned.stream().anyMatch(p -> p.getId().equals(posting.getTargetPositionId()))) {
                posting.setTargetPositionId(null);
            }
            positionRepository.deleteAll(orphaned);
        }
    }

    private static Position claimByName(Map<UUID, Position> unclaimed, String name) {
        String key = Company.nameKey(name);
        Position found = unclaimed.values().stream()
                .filter(position -> Company.nameKey(position.getName()).equals(key))
                .findFirst().orElse(null);
        if (found != null) {
            unclaimed.remove(found.getId());
        }
        return found;
    }

    /**
     * 연결된 기업 이름과 직무 요약을 한 번에 조회해 채운다.
     * 공고마다 조회하면 목록에서 N+1 이 된다.
     */
    private List<JobPostingResponse> toResponses(List<JobPosting> postings) {
        if (postings.isEmpty()) {
            return List.of();
        }
        UUID ownerId = currentUser.id();
        List<UUID> companyIds = postings.stream().map(JobPosting::getCompanyId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> namesById = new HashMap<>();
        companyRepository.findAllByIdInAndOwnerId(companyIds, ownerId)
                .forEach(company -> namesById.put(company.getId(), company.getName()));

        List<UUID> postingIds = postings.stream().map(JobPosting::getId).toList();
        Map<UUID, List<PositionSummary>> positionsByPosting = new HashMap<>();
        positionRepository.findAllByPostingIdInAndOwnerIdOrderByCreatedAtAsc(postingIds, ownerId)
                .forEach(position -> positionsByPosting
                        .computeIfAbsent(position.getPostingId(), k -> new ArrayList<>())
                        .add(PositionSummary.of(position)));

        return postings.stream()
                .map(posting -> JobPostingResponse.of(
                        posting,
                        namesById.get(posting.getCompanyId()),
                        positionsByPosting.getOrDefault(posting.getId(), List.of())))
                .toList();
    }

    /** steps 가 null 이면 null 로 넘겨 기존 절차를 유지한다. */
    private JobPostingAttributes toAttributes(JobPostingRequest request) {
        List<RecruitmentStep> steps = null;
        if (request.steps() != null) {
            steps = new ArrayList<>();
            for (StepRequest step : request.steps()) {
                steps.add(new RecruitmentStep(step.name().trim(), step.result(), step.scheduledAt(), normalize(step.memo())));
            }
        }
        return new JobPostingAttributes(
                resolveCompany(request.companyId(), request.companyName()),
                request.title().trim(),
                normalize(request.postingUrl()),
                request.employmentType(),
                request.deadlineAt(),
                request.status(),
                normalize(request.qualifications()),
                normalize(request.memo()),
                steps
        );
    }

    /**
     * 기업 id 가 있으면 내 기업인지 확인(남의 기업은 404).
     * 없으면 이름으로 찾고, 없으면 만든다 — 공백 제거·소문자 기준(docs/decisions.md).
     */
    private UUID resolveCompany(UUID companyId, String companyName) {
        UUID ownerId = currentUser.id();
        if (companyId != null) {
            return companyRepository.findByIdAndOwnerId(companyId, ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException(companyId))
                    .getId();
        }
        String name = normalize(companyName);
        if (name == null) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "COMPANY_REQUIRED",
                    "기업을 고르거나 회사명을 입력해 주세요.");
        }
        return companyRepository.findByOwnerIdAndNameKey(ownerId, Company.nameKey(name))
                .orElseGet(() -> companyRepository.save(new Company(ownerId, CompanyAttributes.nameOnly(name))))
                .getId();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
