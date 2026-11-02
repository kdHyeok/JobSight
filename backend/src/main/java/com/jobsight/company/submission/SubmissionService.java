package com.jobsight.company.submission;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.OwnerLock;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.posting.JobPostingRepository;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.submission.dto.SubmissionRequest;
import com.jobsight.company.submission.dto.SubmissionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 제출 기록(설계 6.6). 고치지 않고 쌓는다. 확인 수준은 셋이다.
 * <ul>
 *   <li>LINKED — 공고가 SUBMITTED 가 될 때 JobPostingService 의 상태 전이 한 곳(웹 드래그·공고 수정·공고 생성)에서 남긴다.
 *   그 공고 직무에 연결된 이력서와 written_for 자소서의 "기록 당시 연결 자료"다. 제출본이라 부르지 않는다.</li>
 *   <li>USER_CONFIRMED — 사용자가 웹에서 실제로 낸 이력서·자소서를 골라 확인한 제출본.</li>
 *   <li>RECEIPT_VERIFIED — 접수 메일 발췌·접수 번호·첨부 같은 근거를 붙인 제출본. 자동 대조는 하지 않는다.</li>
 * </ul>
 * 스냅샷은 OwnerLock(T9) 아래에서 읽는다 — 이력서·자소서 저장과 엇갈리지 않게. 내용의 해시(content_hash)가 같으면
 * 새로 쓰지 않고 기존 기록을 돌려준다(재시도 멱등). 기록이 가리키는 첨부는 submission_attachments 로 남기고,
 * 새로 적는 첨부 행은 FOR KEY SHARE 로 잡는다(규칙 B, 설계 6.9) — 동시에 들어온 첨부 삭제와 순서가 정해진다.
 */
@Service
@Transactional(readOnly = true)
public class SubmissionService {
    public enum Level { LINKED, USER_CONFIRMED, RECEIPT_VERIFIED }

    /** 공고 응답·posting_set_status 에 싣는 기록 머리. */
    public record Ref(UUID id, Level level) {}

    /**
     * 사용자 확인 이상인 최신 기록 하나(공고별): 그때 낸 자소서 id → 그 공고 안의 written_for 직무.
     * 회사·공고 제목은 그 직무가 지워졌을 때 alreadyUsedFor 에 쓸 이름이다.
     */
    public record Confirmed(UUID postingId, Level level, UUID companyId, String company, String postingTitle,
                            Map<UUID, List<UUID>> selfIntros) {}

    /** 확인 수준을 부르는 이름(설계 6.6). LINKED 는 제출본이 아니다. */
    public static String label(Level level) {
        return switch (level) {
            case LINKED -> "기록 당시 연결 자료";
            case USER_CONFIRMED -> "사용자가 확인한 제출본";
            case RECEIPT_VERIFIED -> "접수 근거와 대조한 제출본";
        };
    }

    private static final String RESUMES = """
            select coalesce(jsonb_agg(jsonb_build_object('resumeId', r.id, 'name', r.name, 'version', r.version,
                   'content', r.content) order by r.id), '[]'::jsonb)::text
              from resumes r
             where r.owner_id = ? and %s
            """;
    private static final String SELF_INTROS = """
            select coalesce(jsonb_agg(jsonb_build_object('id', si.id, 'question', si.question, 'answer', si.answer,
                   'updatedAt', si.updated_at, 'version', si.version,
                   'positionIds', (select coalesce(jsonb_agg(sp.position_id order by sp.position_id), '[]'::jsonb)
                                     from self_introduction_positions sp join positions p on p.id = sp.position_id
                                    where sp.self_introduction_id = si.id and p.posting_id = ?),
                   'experienceRevisions', (select coalesce(jsonb_agg(jsonb_build_object('experienceId', e.experience_id,
                                                  'experienceRevision', e.experience_revision) order by e.experience_id), '[]'::jsonb)
                                             from self_introduction_experiences e where e.self_introduction_id = si.id)
                   ) order by si.id), '[]'::jsonb)::text
              from self_introductions si
             where si.owner_id = ? and %s
            """;
    /** LINKED: 이 공고 직무를 지원하는 이력서(resume_positions)와 이 공고 직무용으로 쓴 자소서(written_for). */
    private static final String LINKED_RESUMES = """
            r.id in (select rp.resume_id from resume_positions rp join positions p on p.id = rp.position_id
                      where p.posting_id = ? and p.owner_id = r.owner_id)""";
    private static final String LINKED_SELF_INTROS = """
            si.id in (select sp.self_introduction_id from self_introduction_positions sp join positions p on p.id = sp.position_id
                       where p.posting_id = ? and p.owner_id = si.owner_id)""";

    private final JdbcTemplate jdbc;
    private final OwnerLock ownerLock;
    private final CurrentUser currentUser;
    private final JobPostingRepository postings;
    private final ObjectMapper mapper;

    public SubmissionService(JdbcTemplate jdbc, OwnerLock ownerLock, CurrentUser currentUser,
                             JobPostingRepository postings, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.ownerLock = ownerLock;
        this.currentUser = currentUser;
        this.postings = postings;
        this.mapper = mapper;
    }

    /**
     * 공고가 SUBMITTED 인 결과마다 JobPostingService 가 부른다(호출자가 이미 OwnerLock 을 잡았다 — 다시 잡아도 같은 트랜잭션이라 무해하다).
     * 이번에 SUBMITTED 로 바뀌었거나 이 공고에 LINKED 기록이 아직 없으면 지금 연결 자료로 남긴다. 이미 SUBMITTED 였던 공고에
     * 같은 상태를 다시 보내면 새로 쓰지 않고 최신 LINKED 를 돌려준다 — 제출 뒤 고친 문서가 "기록 당시"로 섞이지 않게.
     * 호출자는 JPA 변경(직무 동기화)을 flush 한 뒤 부른다. 스냅샷은 JDBC 로 읽어 영속성 컨텍스트를 거치지 않는다.
     */
    @Transactional
    public Ref recordLinked(UUID postingId, boolean transitioned) {
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        if (!transitioned) {
            List<Ref> latest = jdbc.query("select id from submissions where posting_id = ? and owner_id = ? and level = 'LINKED' "
                    + "order by recorded_at desc limit 1", (rs, i) -> new Ref(rs.getObject(1, UUID.class), Level.LINKED), postingId, owner);
            if (!latest.isEmpty()) return latest.get(0);
        }
        String resumes = jdbc.queryForObject(RESUMES.formatted(LINKED_RESUMES), String.class, owner, postingId);
        String selfIntros = jdbc.queryForObject(SELF_INTROS.formatted(LINKED_SELF_INTROS), String.class, postingId, owner, postingId);
        // 지난 문서에 남은 지워진 첨부 id 는 건너뛴다(규칙 B: 이미 저장된 값은 막지 않는다). 있는 것만 잡고 남긴다.
        List<UUID> attachments = lockAttachments(owner, resumeAttachmentIds(resumes));
        return save(owner, postingId, Level.LINKED, resumes, selfIntros, attachments, null);
    }

    /**
     * 웹의 "실제로 낸 것" 확인(USER_CONFIRMED)과 접수 근거 첨부(RECEIPT_VERIFIED). 고른 이력서·자소서의 지금 내용을 스냅샷으로 남긴다.
     * 이력서 안 첨부는 LINKED 와 같이 남은 것만, 접수 근거 첨부는 모두 내 것이어야 한다(아니면 400 ATTACHMENT_NOT_FOUND).
     */
    @Transactional
    public SubmissionResponse confirm(UUID postingId, SubmissionRequest request) {
        UUID owner = currentUser.id();
        ownerLock.acquire(owner);
        if (!postings.existsByIdAndOwnerId(postingId, owner)) throw new ResourceNotFoundException(postingId);
        if (request.level() == Level.LINKED) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "LEVEL_NOT_ALLOWED",
                    "기록 당시 연결 자료(LINKED)는 공고가 제출 완료로 바뀔 때만 남습니다. USER_CONFIRMED 나 RECEIPT_VERIFIED 를 고르세요.");
        }
        List<UUID> resumeIds = distinct(request.resumeIds());
        List<UUID> selfIntroIds = distinct(request.selfIntroIds());
        if (resumeIds.isEmpty() && selfIntroIds.isEmpty()) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "SUBMISSION_EMPTY", "실제로 낸 이력서나 자기소개를 하나 이상 고르세요.");
        }
        Map<String, Object> receipt = receipt(request);
        requireOwned("resumes", resumeIds, owner);
        requireOwned("self_introductions", selfIntroIds, owner);

        String resumes = jdbc.queryForObject(RESUMES.formatted("r.id = any(?::uuid[])"), String.class, owner, array(resumeIds));
        String selfIntros = jdbc.queryForObject(SELF_INTROS.formatted("si.id = any(?::uuid[])"), String.class,
                postingId, owner, array(selfIntroIds));
        List<UUID> attachments = new ArrayList<>(lockAttachments(owner, resumeAttachmentIds(resumes)));
        if (receipt != null) {
            @SuppressWarnings("unchecked")
            List<UUID> evidence = (List<UUID>) receipt.get("attachmentIds");
            if (lockAttachments(owner, evidence).size() != evidence.size()) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "ATTACHMENT_NOT_FOUND", "접수 근거 첨부를 찾을 수 없습니다. 다시 올려 주세요.");
            }
            evidence.stream().filter(id -> !attachments.contains(id)).forEach(attachments::add);
        }
        Ref ref = save(owner, postingId, request.level(), resumes, selfIntros, attachments,
                receipt == null ? null : mapper.writeValueAsString(receipt));
        return list(postingId).stream().filter(value -> value.id().equals(ref.id())).findFirst().orElseThrow();
    }

    /** 공고의 기록을 최신순으로. 목록에는 이력서 본문·답변 원문을 싣지 않는다(이름·버전·질문만). */
    public List<SubmissionResponse> list(UUID postingId) {
        UUID owner = currentUser.id();
        if (!postings.existsByIdAndOwnerId(postingId, owner)) throw new ResourceNotFoundException(postingId);
        return jdbc.query("""
                select s.id, s.level, s.recorded_at, s.receipt::text,
                       (select coalesce(jsonb_agg(jsonb_build_object('resumeId', x->'resumeId', 'name', x->'name', 'version', x->'version')),
                               '[]'::jsonb) from jsonb_array_elements(s.resumes) x)::text,
                       (select coalesce(jsonb_agg(jsonb_build_object('id', x->'id', 'question', x->'question')), '[]'::jsonb)
                          from jsonb_array_elements(s.self_intros) x)::text,
                       (select coalesce(array_agg(a.attachment_id order by a.attachment_id), '{}') from submission_attachments a
                         where a.submission_id = s.id)
                  from submissions s
                 where s.posting_id = ? and s.owner_id = ?
                 order by s.recorded_at desc, s.id
                """, (rs, i) -> new SubmissionResponse(rs.getObject(1, UUID.class), Level.valueOf(rs.getString(2)),
                rs.getTimestamp(3).toInstant(), json(rs.getString(5)), json(rs.getString(6)),
                List.of((UUID[]) rs.getArray(7).getArray()), rs.getString(4) == null ? null : json(rs.getString(4))),
                postingId, owner);
    }

    /**
     * 공고별 사용자 확인 이상 최신 기록(설계 6.6: alreadyUsedFor 는 USER_CONFIRMED 이상이 있으면 그 스냅샷을 쓴다).
     * 자소서 id 와 그때의 written_for 직무만 꺼낸다 — 답변 원문은 읽지 않는다.
     */
    public Map<UUID, Confirmed> confirmedByPosting(UUID owner) {
        Map<UUID, Confirmed> byPosting = new HashMap<>();
        jdbc.query("""
                select s.posting_id, s.level, j.company_id, c.name, j.title, e->>'id', (e->'positionIds')::text
                  from (select distinct on (posting_id) posting_id, level, self_intros from submissions
                         where owner_id = ? and level <> 'LINKED' order by posting_id, recorded_at desc) s
                  join job_postings j on j.id = s.posting_id
                  left join companies c on c.id = j.company_id and c.owner_id = j.owner_id
                  left join lateral jsonb_array_elements(s.self_intros) e on true
                """, rs -> {
            UUID postingId = rs.getObject(1, UUID.class);
            Confirmed confirmed = byPosting.get(postingId);
            if (confirmed == null) {
                confirmed = new Confirmed(postingId, Level.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), new LinkedHashMap<>());
                byPosting.put(postingId, confirmed);
            }
            String selfIntro = rs.getString(6);
            if (selfIntro != null) {
                List<UUID> positions = new ArrayList<>();
                json(rs.getString(7)).forEach(node -> positions.add(UUID.fromString(node.asString())));
                confirmed.selfIntros().put(UUID.fromString(selfIntro), positions);
            }
        }, owner);
        return byPosting;
    }

    private Ref save(UUID owner, UUID postingId, Level level, String resumes, String selfIntros, List<UUID> attachments,
                     String receipt) {
        String hash = sha256(resumes + "\n" + selfIntros + "\n" + new TreeSet<>(attachments) + "\n" + receipt);
        List<UUID> existing = jdbc.queryForList("select id from submissions where posting_id = ? and content_hash = ? and level = ?",
                UUID.class, postingId, hash, level.name());
        if (!existing.isEmpty()) return new Ref(existing.get(0), level);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into submissions (id, owner_id, posting_id, level, content_hash, resumes, self_intros, receipt, recorded_at) "
                + "values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, now())", id, owner, postingId, level.name(), hash,
                resumes, selfIntros, receipt);
        for (UUID attachment : attachments) {
            jdbc.update("insert into submission_attachments (owner_id, submission_id, attachment_id) values (?, ?, ?)",
                    owner, id, attachment);
        }
        return new Ref(id, level);
    }

    /** RECEIPT_VERIFIED 의 근거. 근거가 하나도 없으면 400, USER_CONFIRMED 에 근거를 붙이면 400(조용히 버리지 않는다). */
    private static Map<String, Object> receipt(SubmissionRequest request) {
        String excerpt = trimmed(request.receiptExcerpt());
        String number = trimmed(request.receiptNumber());
        List<UUID> files = distinct(request.receiptAttachmentIds());
        boolean any = excerpt != null || number != null || !files.isEmpty();
        if (request.level() == Level.USER_CONFIRMED) {
            if (any) throw new ApiRuleException(HttpStatus.BAD_REQUEST, "RECEIPT_NEEDS_LEVEL",
                    "접수 근거를 붙이려면 확인 수준을 RECEIPT_VERIFIED 로 고르세요.");
            return null;
        }
        if (!any) throw new ApiRuleException(HttpStatus.BAD_REQUEST, "RECEIPT_REQUIRED",
                "접수 메일 발췌, 접수 번호, 근거 파일 중 하나 이상을 붙이세요.");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("excerpt", excerpt);
        receipt.put("receiptNumber", number);
        receipt.put("attachmentIds", files);
        return receipt;
    }

    private void requireOwned(String table, List<UUID> ids, UUID owner) {
        if (ids.isEmpty()) return;
        List<UUID> found = jdbc.queryForList("select id from " + table + " where owner_id = ? and id = any(?::uuid[])",
                UUID.class, owner, array(ids));
        ids.stream().filter(id -> !found.contains(id)).findFirst().ifPresent(id -> {
            throw new ResourceNotFoundException(id);
        });
    }

    /** 규칙 B(설계 6.9): 새로 적는 첨부 행을 FOR KEY SHARE 로 잡는다. 있는 것만 id 순으로 돌려준다. */
    private List<UUID> lockAttachments(UUID owner, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.queryForList("select id from attachments where owner_id = ? and id = any(?::uuid[]) order by id for key share",
                UUID.class, owner, array(ids));
    }

    /** 스냅샷 이력서들의 content 가 가리키는 첨부 id. UUID 가 아닌 값은 건너뛴다. */
    private List<UUID> resumeAttachmentIds(String resumes) {
        TreeSet<UUID> ids = new TreeSet<>();
        for (JsonNode resume : json(resumes)) {
            for (String value : mapper.treeToValue(resume.get("content"), ResumeContent.class).attachmentIds()) {
                if (value == null || value.isBlank()) continue;
                try {
                    ids.add(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                    // 첨부 칸에 UUID 가 아닌 값이 있으면 첨부가 아니다.
                }
            }
        }
        return List.copyOf(ids);
    }

    private JsonNode json(String value) {
        return mapper.readTree(value);
    }

    private static String array(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
