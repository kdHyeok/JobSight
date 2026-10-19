package com.jobsight.company.position;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.position.dto.PositionReferencesRequest;
import com.jobsight.company.position.dto.PositionRequest;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.posting.JobPosting;
import com.jobsight.company.posting.JobPostingRepository;
import com.jobsight.company.reference.ReferenceItem;
import com.jobsight.company.reference.ReferenceRepository;
import com.jobsight.company.reference.dto.ReferenceResponse;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 직무는 공고 폼이 만들고, 여기서 상세를 채우고 참고 정보를 붙인다. 모든 조회는 소유자로 한정. */
@Service
@Transactional(readOnly = true)
public class PositionService {
    private final PositionRepository repository;
    private final JobPostingRepository postingRepository;
    private final CompanyRepository companyRepository;
    private final ReferenceRepository referenceRepository;
    private final CurrentUser currentUser;

    public PositionService(PositionRepository repository,
                           JobPostingRepository postingRepository,
                           CompanyRepository companyRepository,
                           ReferenceRepository referenceRepository,
                           CurrentUser currentUser) {
        this.repository = repository;
        this.postingRepository = postingRepository;
        this.companyRepository = companyRepository;
        this.referenceRepository = referenceRepository;
        this.currentUser = currentUser;
    }

    /** q 는 이름·팀·회사·스택에 부분 일치. 이 규모에선 메모리 필터로 충분하다. */
    public List<PositionResponse> findAll(String q) {
        List<PositionResponse> all = toResponses(repository.findAllByOwnerIdOrderByCreatedAtDesc(currentUser.id()));
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return all;
        }
        return all.stream().filter(p -> p.matches(needle)).toList();
    }

    public PositionResponse findById(UUID id) {
        return toResponses(List.of(findOwned(id))).get(0);
    }

    @Transactional
    public PositionResponse create(UUID postingId, PositionRequest request) {
        postingRepository.findByIdAndOwnerId(postingId, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(postingId));
        if (repository.countByPostingIdAndOwnerId(postingId, currentUser.id()) >= 30) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "POSITION_LIMIT", "직무는 30개 이하여야 합니다.");
        }
        Position position = repository.save(new Position(currentUser.id(), postingId, request.name().trim()));
        return update(position.getId(), request);
    }

    @Transactional
    public PositionResponse update(UUID id, PositionRequest request) {
        Position position = findOwned(id);
        position.update(new PositionAttributes(
                request.name().trim(),
                normalize(request.team()),
                normalize(request.role()),
                normalize(request.responsibilities()),
                normalize(request.impact()),
                normalize(request.growth()),
                normalize(request.experience()),
                normalize(request.requiredSkills()),
                normalize(request.preferredSkills()),
                normalize(request.headcount()),
                normalize(request.workLocation()),
                normalize(request.memo()),
                normalizeList(request.techStack())
        ));
        return toResponses(List.of(repository.save(position))).get(0);
    }

    /** 참고 정보 집합 교체. 남의 참고 정보 id 는 조용히 버리지 않고 404. */
    @Transactional
    public PositionResponse replaceReferences(UUID id, PositionReferencesRequest request) {
        Position position = findOwned(id);
        Set<UUID> wanted = new LinkedHashSet<>(request.referenceIds() == null ? List.of() : request.referenceIds());
        if (!wanted.isEmpty()) {
            Set<UUID> owned = new HashSet<>();
            referenceRepository.findAllByIdInAndOwnerId(wanted, currentUser.id())
                    .forEach(item -> owned.add(item.getId()));
            for (UUID referenceId : wanted) {
                if (!owned.contains(referenceId)) {
                    throw new ResourceNotFoundException(referenceId);
                }
            }
        }
        position.replaceReferences(wanted);
        return toResponses(List.of(repository.save(position))).get(0);
    }

    /** 마지막 직무를 지우면 공고도 지운다. FK cascade가 그 직무와 절차를 정리한다. */
    @Transactional
    public void delete(UUID id) {
        UUID ownerId = currentUser.id();
        Position position = repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        UUID postingId = position.getPostingId();
        JobPosting posting;
        try {
            postingRepository.setLocalLockTimeout();
            posting = postingRepository.findOwnedForUpdate(postingId, ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException(postingId));
        } catch (PessimisticLockingFailureException exception) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "POSITION_DELETE_BUSY",
                    "다른 삭제 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요.");
        }
        position = repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        if (repository.countByPostingIdAndOwnerId(position.getPostingId(), ownerId) <= 1) {
            postingRepository.delete(posting);
            return;
        }
        repository.delete(position);
    }

    private Position findOwned(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /** 공고·기업·참고 정보를 각 한 번씩 조회해 채운다(N+1 방지). */
    private List<PositionResponse> toResponses(List<Position> positions) {
        if (positions.isEmpty()) {
            return List.of();
        }
        UUID ownerId = currentUser.id();

        Map<UUID, JobPosting> postings = new HashMap<>();
        postingRepository.findAllByIdInAndOwnerId(
                        positions.stream().map(Position::getPostingId).distinct().toList(), ownerId)
                .forEach(p -> postings.put(p.getId(), p));

        Map<UUID, String> companyNames = new HashMap<>();
        List<UUID> companyIds = postings.values().stream().map(JobPosting::getCompanyId).distinct().toList();
        if (!companyIds.isEmpty()) {
            companyRepository.findAllByIdInAndOwnerId(companyIds, ownerId)
                    .forEach(c -> companyNames.put(c.getId(), c.getName()));
        }

        Set<UUID> referenceIds = new HashSet<>();
        positions.forEach(p -> referenceIds.addAll(p.getReferenceIds()));
        Map<UUID, ReferenceItem> references = new HashMap<>();
        if (!referenceIds.isEmpty()) {
            referenceRepository.findAllByIdInAndOwnerId(referenceIds, ownerId)
                    .forEach(r -> references.put(r.getId(), r));
        }

        List<PositionResponse> out = new ArrayList<>();
        for (Position position : positions) {
            JobPosting posting = postings.get(position.getPostingId());
            List<ReferenceResponse> refs = position.getReferenceIds().stream()
                    .map(references::get)
                    .filter(r -> r != null)
                    .map(ReferenceResponse::of)
                    .toList();
            out.add(PositionResponse.of(position, posting,
                    posting == null ? null : companyNames.get(posting.getCompanyId()), refs));
        }
        return out;
    }

    private static List<String> normalizeList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = normalize(value);
            if (normalized != null) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
