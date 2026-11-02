package com.jobsight.company.resume;

import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.ExperienceHead;
import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.ExperienceRepository;
import com.jobsight.company.experience.KeyNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 이력서 경험 행 ↔ 경험 원본 연결 — 설계 6.2 링커. ResumeWriter 만 부른다. 행 문구로 경험 원본을 바꾸지 않고 새 경험도 만들지 않는다.
 * <ol>
 *   <li>앵커 이어받기: experienceId 만 있는 행은 저장된 문서의 같은 경험 행에서 개정을 이어받는다. 지금 개정은 새로 연결되는
 *   경험에만 쓴다(정정 전 문구가 최신 사실 근거처럼 바뀌면 '재검토 필요'가 사라진다). 두 키가 모두 빠진 행은 저장된 문서에
 *   같은 묶음 키 행이 정확히 하나 있고 앵커가 있으면 그 앵커를 잇는다(앵커를 모르는 웹·AI 요청 대비).</li>
 *   <li>개정 검사: 클라이언트는 개정을 바꾸지 못한다. 개정을 올리는 길은 refreshExperienceIds 와 resume_add_experiences 뿐이다.</li>
 *   <li>키가 없는 나머지 행은 (kind, title_key) 가 정확히 1건 맞고 시작 연월이 충돌하지 않을 때만 연결한다.</li>
 * </ol>
 */
@Component
class ResumeExperienceLinker {
    /** 경험 섹션 → 종류와 제목 칸. RowGroups 와 같은 대응이다. */
    static final Map<String, ExperienceKind> SECTION_KINDS = Map.of(
            "projects", ExperienceKind.PROJECT, "activities", ExperienceKind.ACTIVITY,
            "experiences", ExperienceKind.CAREER, "trainings", ExperienceKind.TRAINING);
    static final String ID = "experienceId";
    static final String REVISION = "experienceRevision";

    private final ExperienceRepository experiences;
    private final ObjectMapper mapper;

    ResumeExperienceLinker(ExperienceRepository experiences, ObjectMapper mapper) {
        this.experiences = experiences;
        this.mapper = mapper;
    }

    /** links 는 경험 id → 행이 쓴 개정(문서 순서). root 는 앵커를 채운 새 문서다. */
    record Result(ObjectNode root, Map<UUID, Integer> links, Set<UUID> before) {
        /** 저장 전에는 있었는데 새 문서에서 사라진 경험 — 웹 unlinkedWarnings, MCP LINKED_ROWS_MISSING. */
        Set<UUID> removed() {
            Set<UUID> removed = new LinkedHashSet<>(before);
            removed.removeAll(links.keySet());
            return removed;
        }
    }

    private record Row(String section, ExperienceKind kind, ObjectNode node) {
        String title() {
            return text(node, "experiences".equals(section) ? "company" : "name");
        }

        String startYm() {
            return text(node, "startYm");
        }

        String groupKey() {
            return kind + "|" + KeyNormalizer.key(title()) + "|" + KeyNormalizer.yearMonth(startYm());
        }

        UUID id() {
            String raw = text(node, ID);
            if (raw == null || raw.isBlank()) return null;
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "INVALID_ROW", ID + " 는 UUID 여야 합니다: " + raw);
            }
        }

        Integer revision() {
            JsonNode value = node.get(REVISION);
            return value == null || value.isNull() ? null : value.asInt();
        }

        void anchor(UUID id, Integer revision) {
            if (id == null) {
                node.remove(ID);
                node.remove(REVISION);
            } else {
                node.put(ID, id.toString());
                node.put(REVISION, revision);
            }
        }
    }

    Result link(UUID ownerId, ResumeContent before, ResumeContent after, Set<UUID> refresh) {
        List<Row> stored = rows((ObjectNode) mapper.valueToTree(before));
        ObjectNode root = mapper.valueToTree(after);
        List<Row> rows = rows(root);
        Map<UUID, Integer> storedRevisions = new HashMap<>();
        Map<String, List<Row>> storedByGroup = new HashMap<>();
        for (Row row : stored) {
            if (row.id() != null) storedRevisions.putIfAbsent(row.id(), row.revision());
            storedByGroup.computeIfAbsent(row.groupKey(), key -> new ArrayList<>()).add(row);
        }
        if (rows.isEmpty() && storedRevisions.isEmpty()) {
            return new Result(root, Map.of(), Set.of());
        }
        Map<UUID, ExperienceHead> heads = experiences.findHeads(ownerId).stream()
                .collect(Collectors.toMap(ExperienceHead::id, Function.identity()));
        Map<UUID, Integer> links = new LinkedHashMap<>();

        // 1차: 앵커가 있는 행. 개정을 이어받거나 검사한다.
        List<Row> bare = new ArrayList<>();
        for (Row row : rows) {
            UUID id = row.id();
            if (id == null) {
                bare.add(row);
                continue;
            }
            ExperienceHead head = heads.get(id);
            if (head == null) throw new ResourceNotFoundException(id);
            if (head.kind() != row.kind()) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_SECTION_MISMATCH",
                        row.section() + " 행에는 " + row.kind() + " 경험만 연결할 수 있습니다(이 경험은 " + head.kind() + ").");
            }
            Integer sent = row.revision();
            Integer kept = storedRevisions.get(id);
            int revision;
            if (refresh.contains(id)) {
                revision = head.revision();
            } else if (sent == null) {
                revision = kept != null ? kept : head.revision();
            } else if (kept != null) {
                if (!sent.equals(kept)) throw revisionMismatch(id);
                revision = kept;
            } else {
                // 다른 이력서에서 붙여 넣은 행처럼 새 연결에 개정이 실려 왔다. 개정 이력은 1..지금 개정으로 빈틈이 없다.
                if (sent < 1) {
                    throw new ApiRuleException(HttpStatus.NOT_FOUND, "EXPERIENCE_REVISION_NOT_FOUND",
                            "경험 " + id + " 에 개정 " + sent + " 이 없습니다.");
                }
                if (sent > head.revision()) throw revisionMismatch(id);
                revision = sent;
            }
            put(links, id, revision);
            row.anchor(id, revision);
        }

        // 2차: 두 키가 없는 행. 저장된 앵커 이어받기, 그다음 (kind, title_key) 정확히 1건 자동 연결.
        Map<String, List<ExperienceHead>> byTitle = new HashMap<>();
        heads.values().forEach(head -> byTitle.computeIfAbsent(head.kind() + "|" + head.titleKey(),
                key -> new ArrayList<>()).add(head));
        for (Row row : bare) {
            row.anchor(null, null); // 개정만 실려 온 행: id 없는 개정은 뜻이 없다
            List<Row> same = storedByGroup.getOrDefault(row.groupKey(), List.of());
            UUID inherited = same.size() == 1 ? same.get(0).id() : null;
            if (inherited != null && heads.containsKey(inherited) && !links.containsKey(inherited)) {
                Integer revision = same.get(0).revision();
                links.put(inherited, revision);
                row.anchor(inherited, revision);
                continue;
            }
            String titleKey = KeyNormalizer.key(row.title());
            if (titleKey.isEmpty()) continue;
            List<ExperienceHead> candidates = byTitle.getOrDefault(row.kind() + "|" + titleKey, List.of());
            if (candidates.size() != 1) continue;
            ExperienceHead head = candidates.get(0);
            if (!head.startCompatible(row.startYm()) || links.containsKey(head.id())) continue;
            links.put(head.id(), head.revision());
            row.anchor(head.id(), head.revision());
        }
        return new Result(root, links, storedRevisions.keySet());
    }

    private static void put(Map<UUID, Integer> links, UUID id, int revision) {
        if (links.putIfAbsent(id, revision) != null) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_ROW_DUPLICATE",
                    "한 이력서 안에서 같은 경험(" + id + ")을 두 행에 연결할 수 없습니다.");
        }
    }

    private static ApiRuleException revisionMismatch(UUID id) {
        return new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_REVISION_MISMATCH",
                "경험 행의 개정 번호는 바꿀 수 없습니다(" + id + "). 원본으로 갱신은 웹 '원본으로 갱신'(refreshExperienceIds)으로 합니다.");
    }

    /** 경험 섹션 4개의 행(null 행 제외). */
    private static List<Row> rows(ObjectNode root) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, ExperienceKind> section : SECTION_KINDS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            JsonNode array = root.get(section.getKey());
            if (array == null || !array.isArray()) continue;
            for (JsonNode node : array) {
                if (node instanceof ObjectNode object) rows.add(new Row(section.getKey(), section.getValue(), object));
            }
        }
        return rows;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** 문서의 앵커 id 집합(검사 없이). 행 도구의 experienceId 지목과 rebuild 대조에 쓴다. */
    static Set<UUID> anchors(ObjectNode root) {
        Set<UUID> ids = new HashSet<>();
        for (Row row : rows(root)) {
            UUID id = row.id();
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
