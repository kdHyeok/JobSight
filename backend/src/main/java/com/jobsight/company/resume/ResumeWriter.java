package com.jobsight.company.resume;

import com.jobsight.company.attachment.AttachmentService;
import com.jobsight.company.common.ApiRuleException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 이력서 content 를 쓰는 단 하나의 관문 — 설계 6.2, R40. create·update·copy·updateBasic·행 도구·가져오기·
 * resume_add_experiences 가 모두 여기를 지난다. JSON 과 resume_experiences 두 곳에 쓰므로 우회한 저장 하나가 둘을 어긋나게 한다.
 * 그래서 ResumeRepository.save* 는 이 클래스 밖에서 부르지 않는다(ResumeWriterGateTest 가 소스를 검사한다).
 * 선행 조건: 호출자가 OwnerLock(T9)·이력서 행 잠금(T1)·version 확인(T2)을 마쳤다. 같은 트랜잭션에서 차례로:
 * <ol><li>문서 전체 검증(T6 섹션 상한 포함)</li><li>링커</li><li>저장과 resume_experiences 차이 반영</li>
 * <li>SET CONSTRAINTS ALL IMMEDIATE — 지연 FK 위반을 커밋이 아니라 이 문장에서 낸다(2.3)</li></ol>
 */
@Component
public class ResumeWriter {
    private final ResumeRepository repository;
    private final ResumeExperienceLinker linker;
    private final Validator validator;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final AttachmentService attachments;

    ResumeWriter(ResumeRepository repository, ResumeExperienceLinker linker, Validator validator, ObjectMapper mapper,
                 JdbcTemplate jdbc, AttachmentService attachments) {
        this.repository = repository;
        this.linker = linker;
        this.validator = validator;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.attachments = attachments;
    }

    /** removed 는 저장 전 문서에는 있었는데 이번 저장으로 사라진 경험 id — 웹 응답 unlinkedWarnings. */
    public record Saved(Resume resume, ResumeContent content, Set<UUID> removed) {
    }

    /**
     * before 는 저장된 문서(생성이면 빈 문서, 복제면 원본 문서)다. refresh 는 지금 개정으로 올릴 경험(웹 "원본으로 갱신").
     * allowedRemovals 가 null 이 아니면 사라지는 앵커가 모두 그 안에 있어야 한다(MCP resume_update 의 LINKED_ROWS_MISSING).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Saved write(Resume resume, String name, ResumeContent before, ResumeContent after, Set<UUID> refresh,
                       Set<UUID> allowedRemovals) {
        ResumeContent next = after.normalized();
        Set<ConstraintViolation<ResumeContent>> violations = validator.validate(next);
        if (!violations.isEmpty()) {
            ConstraintViolation<ResumeContent> first = violations.stream()
                    .min(Comparator.comparing(v -> v.getPropertyPath().toString())).orElseThrow();
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "INVALID_CONTENT",
                    first.getPropertyPath() + ": " + first.getMessage());
        }
        ResumeExperienceLinker.Result linked = linker.link(resume.getOwnerId(), before.normalized(), next, refresh);
        Set<UUID> removed = linked.removed();
        if (allowedRemovals != null && !allowedRemovals.containsAll(removed)) {
            removed.removeAll(allowedRemovals);
            throw new ApiRuleException(HttpStatus.CONFLICT, "LINKED_ROWS_MISSING",
                    "경험이 연결된 행이 빠졌습니다: " + removed + ". 지우려는 것이면 removedExperienceIds 에 모두 적고, "
                            + "아니면 resume_get 으로 다시 읽어 그 행을 포함해 보내세요. 일부만 고칠 때는 resume_row_* 를 씁니다.");
        }
        ResumeContent stored = mapper.treeToValue(linked.root(), ResumeContent.class).normalized();
        resume.update(name, mapper.writeValueAsString(stored));
        Resume saved = repository.saveAndFlush(resume);
        // 규칙 B(설계 6.9): 새로 적는 첨부 id 를 for key share 로 잡는다. 생성도 insert 를 먼저 flush 한 뒤다.
        // 복제는 before 가 원본 문서라 잡을 것이 없다 — 원본을 잠그고 읽으므로(lockOwned) 원본이 든 첨부는 삭제 판정에 계속 보인다.
        attachments.lockNewReferences(saved.getOwnerId(), before.normalized().attachmentIds(), stored.attachmentIds());
        sync(saved, linked.links());
        return new Saved(saved, stored, removed);
    }

    /** 이력서 JSON 앵커(4개 경험 섹션의 experienceId·experienceRevision) — 대조 질의와 rebuild 가 같은 정의를 쓴다. */
    private static final String JSON_ANCHORS = """
            select r.owner_id, r.id as resume_id, (e->>'experienceId')::uuid as eid, (e->>'experienceRevision')::int as rev
              from resumes r,
                   lateral jsonb_array_elements(coalesce(r.content->'projects', '[]') || coalesce(r.content->'activities', '[]')
                                             || coalesce(r.content->'experiences', '[]') || coalesce(r.content->'trainings', '[]')) e
             where jsonb_typeof(e) = 'object' and e->>'experienceId' is not null and e->>'experienceRevision' is not null
            """;

    /**
     * 운영 점검·롤백 뒤 복구(설계 6.2 런북): 계정 이력서 JSON 의 앵커로 resume_experiences 를 지우고 다시 만든다. 멱등이다.
     * 호출자가 OwnerLock 을 잡은 트랜잭션 안에서 부른다. 같은 SQL 을 psql 로 계정마다 돌려도 된다(docs/experiences.md).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int rebuild(UUID ownerId) {
        jdbc.update("delete from resume_experiences where owner_id = ?", ownerId);
        int inserted = jdbc.update("insert into resume_experiences (owner_id, resume_id, experience_id, based_on_experience_id, "
                + "based_on_revision) select owner_id, resume_id, eid, eid, rev from (" + JSON_ANCHORS + ") j "
                + "where owner_id = ? on conflict do nothing", ownerId);
        jdbc.execute("set constraints all immediate");
        return inserted;
    }

    /** JSON↔표 대조(설계 6.2). 0 이어야 한다. 운영 DB 에서도 내용 없이 계정별 건수만 본다. */
    public long mismatches(UUID ownerId) {
        Long count = jdbc.queryForObject("with j as (" + JSON_ANCHORS + ") select count(*) from ("
                + "(select owner_id, resume_id, eid, rev from j except "
                + " select owner_id, resume_id, based_on_experience_id, based_on_revision from resume_experiences)"
                + " union all "
                + "(select owner_id, resume_id, based_on_experience_id, based_on_revision from resume_experiences except "
                + " select owner_id, resume_id, eid, rev from j)) d where owner_id = ?", Long.class, ownerId);
        return count == null ? 0 : count;
    }

    /** resume_experiences 를 문서의 앵커에 맞춘다. 바뀐 것만 지우고·고치고·넣는다. */
    private void sync(Resume resume, Map<UUID, Integer> links) {
        Map<UUID, Integer> current = new HashMap<>();
        jdbc.query("select experience_id, based_on_revision from resume_experiences where resume_id = ? and owner_id = ?",
                rs -> { current.put(rs.getObject(1, UUID.class), rs.getInt(2)); }, resume.getId(), resume.getOwnerId());
        if (current.isEmpty() && links.isEmpty()) return;
        for (Map.Entry<UUID, Integer> entry : current.entrySet()) {
            Integer wanted = links.get(entry.getKey());
            if (wanted == null) {
                jdbc.update("delete from resume_experiences where resume_id = ? and experience_id = ?",
                        resume.getId(), entry.getKey());
            } else if (!wanted.equals(entry.getValue())) {
                jdbc.update("update resume_experiences set based_on_revision = ? where resume_id = ? and experience_id = ?",
                        wanted, resume.getId(), entry.getKey());
            }
        }
        List<Object[]> inserts = links.entrySet().stream().filter(entry -> !current.containsKey(entry.getKey()))
                .map(entry -> new Object[]{resume.getOwnerId(), resume.getId(), entry.getKey(), entry.getKey(), entry.getValue()})
                .toList();
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("insert into resume_experiences (owner_id, resume_id, experience_id, based_on_experience_id, "
                    + "based_on_revision) values (?, ?, ?, ?, ?)", inserts);
        }
        jdbc.execute("set constraints all immediate");
    }
}
