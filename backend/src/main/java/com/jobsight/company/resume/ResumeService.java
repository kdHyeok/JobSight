package com.jobsight.company.resume;

import com.jobsight.company.attachment.AttachmentRepository;
import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.OwnerLock;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.Experience;
import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.ExperienceRepository;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceRequests;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 이력서는 문서 하나로 읽고 쓴다. REST 에 행 단위 API 는 없다 — docs/resumes.md. 모든 조회는 소유자로 한정.
 * 쓰기는 계정 잠금(OwnerLock, T9) → 이력서 행 잠금 → version 확인 뒤 ResumeWriter 한 곳으로 저장한다(불변 조건 8·10, 설계 6.2).
 * 수정 요청의 null 은 유지다(불변 조건 9).
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
    private final ResumeWriter writer;
    private final OwnerLock ownerLock;
    private final ExperienceRepository experiences;

    public ResumeService(ResumeRepository repository, CurrentUser currentUser, ObjectMapper objectMapper,
                         Validator validator, PositionRepository positionRepository,
                         AttachmentRepository attachmentRepository, ResumeWriter writer, OwnerLock ownerLock,
                         ExperienceRepository experiences) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.positionRepository = positionRepository;
        this.attachmentRepository = attachmentRepository;
        this.writer = writer;
        this.ownerLock = ownerLock;
        this.experiences = experiences;
    }

    /** 목록엔 content 를 싣지 않는다. 카드는 이름·수정일만 그린다. */
    public List<ResumeSummary> findAll() {
        return repository.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream()
                .map(ResumeSummary::of)
                .toList();
    }

    public ResumeResponse findById(UUID id) {
        return toResponse(findOwned(id), Set.of());
    }

    @Transactional
    public ResumeResponse create(ResumeRequest request) {
        ownerLock.acquire(currentUser.id());
        Resume resume = new Resume(currentUser.id(), request.name().trim(), "{}");
        resume.replacePositions(ownedPositionIds(request.positionIds()));
        ResumeContent content = request.content() == null ? ResumeContent.empty() : request.content();
        return saved(writer.write(resume, resume.getName(), ResumeContent.empty(), content, Set.of(), null));
    }

    /** 웹 PUT. content·positionIds·빠진 섹션이 null 이면 유지, [] 면 비움. expectedVersion 필수. */
    @Transactional
    public ResumeResponse update(UUID id, ResumeRequest request) {
        return update(id, request, null);
    }

    /**
     * allowedRemovals 가 null 이 아니면(MCP resume_update) 사라지는 경험 앵커가 모두 그 안에 있어야 한다 — 409 LINKED_ROWS_MISSING.
     * refreshExperienceIds(웹 "원본으로 갱신")는 그 경험 행의 개정을 지금 개정으로 올린다.
     */
    @Transactional
    public ResumeResponse update(UUID id, ResumeRequest request, Set<UUID> allowedRemovals) {
        ownerLock.acquire(currentUser.id());
        Resume resume = lockOwned(id);
        checkVersion(resume, request.expectedVersion(), true);
        ResumeContent stored = read(resume.getContent());
        ResumeContent next = request.content() == null ? stored : request.content().withMissingFrom(stored);
        if (request.positionIds() != null) {
            resume.replacePositions(ownedPositionIds(request.positionIds()));
        }
        Set<UUID> refresh = request.refreshExperienceIds() == null ? Set.of() : Set.copyOf(request.refreshExperienceIds());
        return saved(writer.write(resume, request.name().trim(), stored, next, refresh, allowedRemovals));
    }

    /** 같은 내용의 새 버전. 원본을 잠그고 읽어 앵커(경험 id·개정)를 그대로 복사한다 — 링커의 before 는 원본 문서다(6.4). */
    @Transactional
    public ResumeResponse copy(UUID id, ResumeCopyRequest request) {
        ownerLock.acquire(currentUser.id());
        Resume source = lockOwned(id);
        ResumeContent content = read(source.getContent());
        Resume copy = new Resume(currentUser.id(), request.name().trim(), "{}");
        copy.replacePositions(source.getPositionIds());
        return saved(writer.write(copy, copy.getName(), content, content, Set.of(), null));
    }

    /** resume_experiences 는 CASCADE 로 지워진다. 경험 원본·개정 이력·자소서는 남는다(6.4). */
    @Transactional
    public void delete(UUID id) {
        ownerLock.acquire(currentUser.id());
        repository.delete(lockOwned(id));
    }

    // ── 행 단위 도구 (MCP 전용) ───────────────────────────────────────────
    // REST 에는 행 API 가 없다 — 화면은 문서 통째 PUT 이다. MCP 는 문서 전체를 되돌려 보내기 어려우니
    // 서버가 조회 → 한 군데 수정 → 저장을 한 트랜잭션으로 묶어 준다. 나머지 섹션은 건드리지 않는다.

    /** basic 에 보낸 필드만 덮는다. 빠진 필드는 유지, "" 는 비움. 행 번호에 기대지 않으므로 expectedVersion 은 선택이다. */
    @Transactional
    public ResumeResponse updateBasic(UUID id, ResumeContent.BasicInfo basic, Long expectedVersion) {
        ownerLock.acquire(currentUser.id());
        Resume resume = lockOwned(id);
        checkVersion(resume, expectedVersion, false);
        ResumeContent current = read(resume.getContent());
        ResumeContent next = new ResumeContent(basic == null ? current.basic() : basic.withMissingFrom(current.basic()),
                current.educations(), current.trainings(), current.activities(), current.experiences(),
                current.awards(), current.certificates(), current.skills(), current.projects());
        return saved(writer.write(resume, resume.getName(), current, next, Set.of(), null));
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
        return updateRow(id, section, index, null, row, expectedVersion);
    }

    /** 대상은 index 또는 experienceId 중 하나(설계 5.3, R50). experienceId 면 행 번호가 밀려도 같은 경험 행을 찾는다. */
    @Transactional
    public ResumeRowResult updateRow(UUID id, ResumeSection section, Integer index, UUID experienceId,
                                     ResumeRowRequest row, Long expectedVersion) {
        return mutateRows(id, section, expectedVersion, true, rows -> {
            int target = target(rows, section, index, experienceId);
            rows.set(target, toNode(section, row, rows.get(target)));
            return target;
        });
    }

    /** index(0-based) 행을 지운다. 뒤 행이 앞으로 당겨진다. expectedVersion 필수. */
    @Transactional
    public ResumeRowResult deleteRow(UUID id, ResumeSection section, int index, Long expectedVersion) {
        return deleteRow(id, section, index, null, expectedVersion);
    }

    @Transactional
    public ResumeRowResult deleteRow(UUID id, ResumeSection section, Integer index, UUID experienceId,
                                     Long expectedVersion) {
        return mutateRows(id, section, expectedVersion, true, rows -> {
            rows.remove(target(rows, section, index, experienceId));
            return null;
        });
    }

    /**
     * resume_add_experiences — 경험마다 행을 만들어 종류에 맞는 섹션 끝에 붙인다(설계 5.2). 문구는 경험 원본의 사실로 만든 중립 문구(FACTS)이고
     * 행에 experienceId 와 지금 개정을 쓴다 — 개정을 올리는 두 길 중 하나다. 이미 이 이력서에 있는 경험은 건너뛴다.
     * 섹션 상한(T6)을 넘으면 하나도 넣지 않는다(ResumeWriter 의 문서 검증). expectedVersion 은 선택이다(행 추가).
     */
    @Transactional
    public ResumeRowResult addExperiences(UUID id, List<ExperienceRequests.AddItem> items, Long expectedVersion) {
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        Resume resume = lockOwned(id);
        checkVersion(resume, expectedVersion, false);
        Set<UUID> wanted = items.stream().map(ExperienceRequests.AddItem::experienceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Experience> found = experiences.findAllByIdInAndOwnerId(wanted, owner).stream()
                .collect(Collectors.toMap(Experience::getId, Function.identity()));
        UUID missing = wanted.stream().filter(experienceId -> !found.containsKey(experienceId)).findFirst().orElse(null);
        if (missing != null) throw new ResourceNotFoundException(missing);
        ResumeContent current = read(resume.getContent());
        ObjectNode root = objectMapper.valueToTree(current);
        Set<UUID> present = ResumeExperienceLinker.anchors(root);
        String lastSection = null;
        for (UUID experienceId : wanted) {
            if (present.contains(experienceId)) continue;
            Experience experience = found.get(experienceId);
            String section = sectionOf(experience.getKind());
            ((ArrayNode) root.get(section)).add(factsRow(experience));
            lastSection = section;
        }
        ResumeContent next = objectMapper.treeToValue(root, ResumeContent.class);
        ResumeWriter.Saved saved = writer.write(resume, resume.getName(), current, next, Set.of(), null);
        String section = lastSection == null ? "projects" : lastSection;
        JsonNode rows = objectMapper.valueToTree(saved.content()).get(section);
        return new ResumeRowResult(saved.resume().getId(), saved.resume().getVersion(), section, rows.size(),
                lastSection == null ? null : rows.size() - 1, null);
    }

    /** 경험 종류 → 이력서 섹션. OTHER 는 이력서에 그리지 않는다. */
    private static String sectionOf(ExperienceKind kind) {
        return ResumeExperienceLinker.SECTION_KINDS.entrySet().stream().filter(entry -> entry.getValue() == kind)
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_SECTION_MISMATCH",
                        "OTHER 경험은 이력서 섹션에 넣을 수 없습니다."));
    }

    /** 경험 원본의 사실로 만든 중립 행(FACTS). 사실에 없는 것을 지어내지 않는다. */
    private ObjectNode factsRow(Experience experience) {
        ExperienceFacts facts = objectMapper.readValue(experience.getFacts(), ExperienceFacts.class);
        String period = facts.period() == null ? "" : facts.period();
        String[] ends = period.split("~", 2);
        String start = experience.getStartYm() != null ? experience.getStartYm() : blankToNull(ends[0]);
        String end = ends.length == 2 ? blankToNull(ends[1]) : null;
        String actions = texts(facts.actions());
        String results = texts(facts.results());
        Map<String, Object> row = new LinkedHashMap<>();
        // 사실 상한(항목 2000자×50, role 2000자)이 이력서 칸 상한보다 크다. 칸에 맞춰 자르고 생략 표시를 붙인다 —
        // 그대로 넣으면 문서 검증이 400 으로 막고, 호출자는 사실을 지우는 것 말고 고칠 길이 없다. 원문은 경험 원본에 그대로 있다.
        switch (experience.getKind()) {
            case PROJECT -> {
                row.put("name", fit(experience.getTitle(), ResumeContent.SHORT));
                row.put("role", fit(facts.role(), ResumeContent.URL));
                row.put("techStack", experience.getSkills().isEmpty() ? null
                        : fit(String.join(", ", experience.getSkills()), ResumeContent.URL));
                row.put("description", fit(actions, ResumeContent.LONG));
                row.put("outcome", fit(results, ResumeContent.LONG));
            }
            case CAREER -> {
                row.put("company", fit(experience.getTitle(), ResumeContent.SHORT));
                row.put("description", fit(join(facts.role(), actions, results), ResumeContent.LONG));
            }
            default -> {
                row.put("name", fit(experience.getTitle(), ResumeContent.SHORT));
                row.put("description", fit(join(facts.role(), actions, results), ResumeContent.LONG));
            }
        }
        row.put("startYm", fit(start, ResumeContent.SHORT));
        row.put("endYm", fit(end, ResumeContent.SHORT));
        row.put(ResumeExperienceLinker.ID, experience.getId().toString());
        row.put(ResumeExperienceLinker.REVISION, experience.getRevision());
        row.values().removeIf(Objects::isNull);
        return objectMapper.valueToTree(row);
    }

    private static String texts(List<ExperienceFacts.Item> items) {
        if (items == null || items.isEmpty()) return null;
        return items.stream().map(item -> "- " + item.text()).collect(Collectors.joining("\n"));
    }

    private static String join(String... parts) {
        String joined = Stream.of(parts).filter(part -> part != null && !part.isBlank()).collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    /** max 자를 넘으면 앞 max-1 자 + "…". 서로게이트 쌍을 가르지 않는다. */
    private static String fit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 2)) ? max - 2 : max - 1;
        return value.substring(0, end) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** index 또는 experienceId 중 정확히 하나. experienceId 는 이 섹션에서 그 앵커를 든 행을 찾는다. */
    private static int target(ArrayNode rows, ResumeSection section, Integer index, UUID experienceId) {
        if ((index == null) == (experienceId == null)) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "TARGET_REQUIRED", "index 또는 experienceId 중 하나만 보내세요.");
        }
        if (index != null) {
            requireIndex(rows, index);
            return index;
        }
        for (int i = 0; i < rows.size(); i++) {
            JsonNode anchor = rows.get(i) == null ? null : rows.get(i).get(ResumeExperienceLinker.ID);
            if (anchor != null && experienceId.toString().equals(anchor.asString())) return i;
        }
        throw new ApiRuleException(HttpStatus.NOT_FOUND, "ROW_NOT_FOUND",
                section.key() + " 에 경험 " + experienceId + " 이 연결된 행이 없습니다. 이력서를 다시 읽어 확인하세요.");
    }

    /** change 는 바꾼 행의 index 를 돌려준다(삭제면 null). 응답은 이력서 전체가 아니라 그 행의 개요다. */
    private ResumeRowResult mutateRows(UUID id, ResumeSection section, Long expectedVersion, boolean versionRequired,
                                       Function<ArrayNode, Integer> change) {
        ownerLock.acquire(currentUser.id());
        Resume resume = lockOwned(id);
        checkVersion(resume, expectedVersion, versionRequired);
        ResumeContent current = read(resume.getContent());
        ObjectNode root = (ObjectNode) objectMapper.readTree(resume.getContent());
        JsonNode existing = root.get(section.key());
        ArrayNode rows = existing instanceof ArrayNode array ? array : root.putArray(section.key());
        Integer index = change.apply(rows);
        ResumeContent next = objectMapper.treeToValue(root, ResumeContent.class);
        ResumeWriter.Saved saved = writer.write(resume, resume.getName(), current, next, Set.of(), null);
        JsonNode savedRows = objectMapper.valueToTree(saved.content()).get(section.key());
        Map<String, Object> savedRow = index == null ? null : asMap(savedRows.get(index));
        return new ResumeRowResult(saved.resume().getId(), saved.resume().getVersion(), section.key(), savedRows.size(),
                index, savedRow);
    }

    /**
     * 합집합 record 를 이 섹션의 record 로 엄격 변환한다. 다른 섹션 필드가 섞이면 거부한다 —
     * Jackson 이 조용히 버리면 값이 사라진 채 저장되고, 도구 사용자는 성공으로 알기 때문이다.
     * previous 가 있으면 그 위에 보낸 필드만 덮는다. 그래서 행 요청이 표현하지 못하는 중첩 배열
     * (학년별·학기별 이수 내역)과 경험 개정(experienceRevision)도 남는다 — 이 병합이 없으면 학력 행을 고칠 때마다 성적표가 사라진다.
     * 다른 experienceId 를 보내면 "다른 경험에 연결"이라 이전 개정을 버린다(새 연결은 링커가 지금 개정을 쓴다).
     */
    private JsonNode toNode(ResumeSection section, ResumeRowRequest row, JsonNode previous) {
        Map<String, Object> present = new LinkedHashMap<>();
        if (previous instanceof ObjectNode before) {
            present.putAll(asMap(before));
        }
        Map<?, ?> requested = objectMapper.convertValue(row, Map.class);
        Object relinked = requested.get(ResumeExperienceLinker.ID);
        if (relinked != null && !relinked.equals(present.get(ResumeExperienceLinker.ID))) {
            present.remove(ResumeExperienceLinker.REVISION);
        }
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

    private ResumeResponse saved(ResumeWriter.Saved saved) {
        return toResponse(saved.resume(), saved.removed());
    }

    private ResumeResponse toResponse(Resume resume, Set<UUID> unlinked) {
        ResumeContent content = read(resume.getContent());
        return ResumeResponse.of(resume, content, attachmentMetas(content), List.copyOf(unlinked));
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

    private ResumeContent read(String json) {
        return objectMapper.readValue(json, ResumeContent.class).normalized();
    }
}
