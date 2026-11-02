package com.jobsight.company.resume;

import com.jobsight.company.attachment.AttachmentRepository;
import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.resume.dto.ResumeCopyRequest;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.resume.dto.ResumeRowRequest;
import com.jobsight.company.resume.dto.ResumeRowResult;
import com.jobsight.company.resume.dto.ResumeSummary;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 이력서는 문서 하나로 읽고 쓴다. REST 에 행 단위 API 는 없다 — docs/resumes.md. 모든 조회는 소유자로 한정.
 * 쓰기는 이력서 행을 잠그고 version 을 확인한다(불변 조건 8·10). 수정 요청의 null 은 유지다(불변 조건 9).
 */
@Service
@Transactional(readOnly = true)
public class ResumeService {
    private final ResumeRepository repository;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final PositionRepository positionRepository;
    private final AttachmentRepository attachmentRepository;

    public ResumeService(ResumeRepository repository, CurrentUser currentUser, ObjectMapper objectMapper,
                         Validator validator, PositionRepository positionRepository,
                         AttachmentRepository attachmentRepository) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.positionRepository = positionRepository;
        this.attachmentRepository = attachmentRepository;
    }

    /** 목록엔 content 를 싣지 않는다. 카드는 이름·수정일만 그린다. */
    public List<ResumeSummary> findAll() {
        return repository.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream()
                .map(ResumeSummary::of)
                .toList();
    }

    public ResumeResponse findById(UUID id) {
        return toResponse(findOwned(id));
    }

    @Transactional
    public ResumeResponse create(ResumeRequest request) {
        Resume resume = new Resume(currentUser.id(), request.name().trim(), write(request.content()));
        resume.replacePositions(ownedPositionIds(request.positionIds()));
        return toResponse(repository.save(resume));
    }

    /** content·positionIds·빠진 섹션이 null 이면 유지, [] 면 비움. expectedVersion 필수. */
    @Transactional
    public ResumeResponse update(UUID id, ResumeRequest request) {
        Resume resume = lockOwned(id);
        checkVersion(resume, request.expectedVersion(), true);
        ResumeContent stored = read(resume.getContent());
        ResumeContent next = request.content() == null ? stored : request.content().withMissingFrom(stored);
        resume.update(request.name().trim(), objectMapper.writeValueAsString(next.normalized()));
        if (request.positionIds() != null) {
            resume.replacePositions(ownedPositionIds(request.positionIds()));
        }
        return toResponse(repository.saveAndFlush(resume));
    }

    /** 같은 내용의 새 버전. 원본은 건드리지 않는다. */
    @Transactional
    public ResumeResponse copy(UUID id, ResumeCopyRequest request) {
        Resume source = findOwned(id);
        Resume copy = new Resume(currentUser.id(), request.name().trim(), source.getContent());
        copy.replacePositions(source.getPositionIds());
        return toResponse(repository.save(copy));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(findOwned(id));
    }

    // ── 행 단위 도구 (MCP 전용) ───────────────────────────────────────────
    // REST 에는 행 API 가 없다 — 화면은 문서 통째 PUT 이다. MCP 는 문서 전체를 되돌려 보내기 어려우니
    // 서버가 조회 → 한 군데 수정 → 저장을 한 트랜잭션으로 묶어 준다. 나머지 섹션은 건드리지 않는다.

    /** basic 에 보낸 필드만 덮는다. 빠진 필드는 유지, "" 는 비움. 행 번호에 기대지 않으므로 expectedVersion 은 선택이다. */
    @Transactional
    public ResumeResponse updateBasic(UUID id, ResumeContent.BasicInfo basic, Long expectedVersion) {
        Resume resume = lockOwned(id);
        checkVersion(resume, expectedVersion, false);
        ResumeContent current = read(resume.getContent());
        ResumeContent next = new ResumeContent(basic == null ? current.basic() : basic.withMissingFrom(current.basic()),
                current.educations(), current.trainings(), current.activities(), current.experiences(),
                current.awards(), current.certificates(), current.skills(), current.projects());
        resume.update(resume.getName(), objectMapper.writeValueAsString(next.normalized()));
        return toResponse(repository.saveAndFlush(resume));
    }

    /** 섹션 맨 뒤에 붙인다. 행 번호에 기대지 않으므로 expectedVersion 은 선택이다. */
    @Transactional
    public ResumeRowResult addRow(UUID id, ResumeSection section, ResumeRowRequest row, Long expectedVersion) {
        return mutateRows(id, section, expectedVersion, false, rows -> {
            rows.add(toNode(section, row, null));
            return rows.size() - 1;
        });
    }

    /** index(0-based) 행에 보낸 필드만 덮는다. 빠진 필드는 유지, "" 는 비움. expectedVersion 필수. */
    @Transactional
    public ResumeRowResult updateRow(UUID id, ResumeSection section, int index, ResumeRowRequest row,
                                     Long expectedVersion) {
        return mutateRows(id, section, expectedVersion, true, rows -> {
            requireIndex(rows, index);
            rows.set(index, toNode(section, row, rows.get(index)));
            return index;
        });
    }

    /** index(0-based) 행을 지운다. 뒤 행이 앞으로 당겨진다. expectedVersion 필수. */
    @Transactional
    public ResumeRowResult deleteRow(UUID id, ResumeSection section, int index, Long expectedVersion) {
        return mutateRows(id, section, expectedVersion, true, rows -> {
            requireIndex(rows, index);
            rows.remove(index);
            return null;
        });
    }

    /** change 는 바꾼 행의 index 를 돌려준다(삭제면 null). 응답은 이력서 전체가 아니라 그 행의 개요다. */
    private ResumeRowResult mutateRows(UUID id, ResumeSection section, Long expectedVersion, boolean versionRequired,
                                       Function<ArrayNode, Integer> change) {
        Resume resume = lockOwned(id);
        checkVersion(resume, expectedVersion, versionRequired);
        ObjectNode root = (ObjectNode) objectMapper.readTree(resume.getContent());
        JsonNode existing = root.get(section.key());
        ArrayNode rows = existing instanceof ArrayNode array ? array : root.putArray(section.key());
        Integer index = change.apply(rows);
        ResumeContent next = objectMapper.treeToValue(root, ResumeContent.class).normalized();
        resume.update(resume.getName(), objectMapper.writeValueAsString(next));
        Resume saved = repository.saveAndFlush(resume);
        JsonNode savedRows = objectMapper.readTree(saved.getContent()).get(section.key());
        Map<String, Object> savedRow = index == null ? null : asMap(savedRows.get(index));
        return new ResumeRowResult(saved.getId(), saved.getVersion(), section.key(), savedRows.size(), index, savedRow);
    }

    /**
     * 합집합 record 를 이 섹션의 record 로 엄격 변환한다. 다른 섹션 필드가 섞이면 거부한다 —
     * Jackson 이 조용히 버리면 값이 사라진 채 저장되고, 도구 사용자는 성공으로 알기 때문이다.
     * previous 가 있으면 그 위에 보낸 필드만 덮는다. 그래서 행 요청이 표현하지 못하는 중첩 배열
     * (학년별·학기별 이수 내역)도 남는다 — 이 병합이 없으면 학력 행을 고칠 때마다 성적표가 사라진다.
     */
    private JsonNode toNode(ResumeSection section, ResumeRowRequest row, JsonNode previous) {
        Map<String, Object> present = new LinkedHashMap<>();
        if (previous instanceof ObjectNode before) {
            present.putAll(asMap(before));
        }
        Map<?, ?> requested = objectMapper.convertValue(row, Map.class);
        requested.forEach((key, value) -> {
            if (value != null) present.put(String.valueOf(key), value);
        });
        Object typed;
        try {
            typed = objectMapper.readerFor(section.rowType())
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(objectMapper.writeValueAsString(present));
        } catch (JacksonException e) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "UNKNOWN_ROW_FIELD",
                    section.key() + " 섹션의 필드는 " + section.fields() + " 입니다.");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(typed);
        if (!violations.isEmpty()) {
            ConstraintViolation<Object> first = violations.iterator().next();
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "INVALID_ROW",
                    first.getPropertyPath() + ": " + first.getMessage());
        }
        return objectMapper.valueToTree(typed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    private static void requireIndex(ArrayNode rows, int index) {
        if (index < 0 || index >= rows.size()) {
            throw new ApiRuleException(HttpStatus.NOT_FOUND, "ROW_NOT_FOUND",
                    "행 번호가 범위를 벗어났습니다: " + index + " (0부터, 현재 " + rows.size() + "개)");
        }
    }

    /**
     * 예전에 읽은 문서로 덮어쓰지 않게 한다 — docs/resumes.md 불변 조건 8.
     * 오류에는 지금 버전을 싣지 않는다. 실어 주면 클라이언트가 대상을 확인하지 않고 그 값으로 다시 보낼 수 있다.
     */
    private static void checkVersion(Resume resume, Long expected, boolean required) {
        if (expected == null) {
            if (!required) return;
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED",
                    "expectedVersion 이 필요합니다. 이력서를 다시 읽고 고칠 대상을 확인한 뒤 그 version 을 보내세요.");
        }
        if (expected != resume.getVersion()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "RESUME_STALE",
                    "다른 곳에서 먼저 저장했습니다. 이력서를 다시 읽고 고칠 대상을 다시 확인한 뒤 보내세요. 같은 행 번호로 그대로 재시도하지 마세요.");
        }
    }

    /** 쓰기 경로의 조회. 행을 잠가 동시에 들어온 쓰기가 서로를 덮지 않게 한다 — 불변 조건 10. */
    private Resume lockOwned(UUID id) {
        try {
            repository.setLocalLockTimeout();
            return repository.findOwnedForUpdate(id, currentUser.id())
                    .orElseThrow(() -> new ResourceNotFoundException(id));
        } catch (PessimisticLockingFailureException exception) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "RESUME_BUSY",
                    "같은 이력서의 다른 저장을 처리 중입니다. 잠시 후 다시 읽고 시도해 주세요.");
        }
    }

    /** 남의 데이터는 존재 자체를 알리지 않도록 403 이 아니라 404. */
    private Resume findOwned(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    private ResumeResponse toResponse(Resume resume) {
        ResumeContent content = read(resume.getContent());
        return ResumeResponse.of(resume, content, attachmentMetas(content));
    }

    /**
     * 문서가 가리키는 내 첨부의 메타. 편집기가 그려진 뒤 칸마다 메타를 부르던 직렬 단계를 없앤다(설계 1.3 P5).
     * id 가 아닌 값은 버리고, 남의 것·지워진 것은 소유자 조건에서 빠진다 — 화면은 빠진 칸만 직접 부른다.
     */
    private Map<UUID, AttachmentResponse> attachmentMetas(ResumeContent content) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : content.attachmentIds()) {
            if (raw == null || raw.isBlank()) continue;
            try {
                ids.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // 손으로 적은 값이 섞여 있어도 이력서 열기를 막지 않는다. 그 칸은 "파일을 찾을 수 없습니다" 로 보인다.
            }
        }
        if (ids.isEmpty()) return Map.of();
        Map<UUID, AttachmentResponse> metas = new LinkedHashMap<>();
        attachmentRepository.findMetas(ids, currentUser.id()).forEach(meta -> metas.put(meta.id(), meta));
        return metas;
    }

    private Set<UUID> ownedPositionIds(List<UUID> rawIds) {
        Set<UUID> ids = new LinkedHashSet<>(rawIds == null ? List.of() : rawIds);
        if (ids.isEmpty()) return ids;
        Set<UUID> owned = new LinkedHashSet<>();
        positionRepository.findAllByIdInAndOwnerId(ids, currentUser.id()).forEach(position -> owned.add(position.getId()));
        for (UUID id : ids) {
            if (!owned.contains(id)) throw new ResourceNotFoundException(id);
        }
        return ids;
    }

    /** 생성 때 null 은 빈 문서로. 저장되는 JSON 이 항상 같은 모양이어야 읽는 쪽이 편하다. */
    private String write(ResumeContent content) {
        ResumeContent normalized = (content == null ? ResumeContent.empty() : content).normalized();
        return objectMapper.writeValueAsString(normalized);
    }

    private ResumeContent read(String json) {
        return objectMapper.readValue(json, ResumeContent.class).normalized();
    }
}
