package com.jobsight.company.attachment;

import com.jobsight.company.attachment.dto.AttachmentListItem;
import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 이력서 증빙 파일. 모든 조회는 소유자로 한정하고, 타인 것은 404 다. */
@Service
@Transactional(readOnly = true)
public class AttachmentService {
    /** 10MB. application.yml 의 multipart 상한·nginx client_max_body_size 와 함께 움직인다. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    /** 서랍에서 지울 수 있게 되기까지의 여유. 다른 탭에서 올리고 아직 저장하지 않은 파일을 지우지 않으려는 것이다(설계 6.9). */
    static final Duration DRAWER_GRACE = Duration.ofDays(7);

    /**
     * 사용처 판정(설계 6.9 체크리스트). 첫 인자는 소유자, %s 는 대상 첨부 조건이다(한 건이면 "and id = ?", 서랍이면 "").
     * 이력서·경험은 JSON 텍스트 포함 검사다 — 계정 이력서 몇 건이라 GIN 없이 한다(설계 1.5). 그래서 첨부 칸에는
     * 정규형(소문자 36자) UUID 만 새로 적게 한다(lockNewReferences). 참조원이 늘면 여기에 UNION ALL 절을 더한다.
     * submission_attachments 는 FK 가 삭제를 막지만(방어선) 판정에도 넣어 같은 409 문구를 준다.
     */
    private static final String USAGE = """
            with t as (select id, owner_id from attachments where owner_id = ? %s)
            select * from (
              select t.id, 'RESUME' as type, r.id as ref_id, r.name
                from t join resumes r on r.owner_id = t.owner_id and strpos(r.content::text, t.id::text) > 0
              union all
              select t.id, case when strpos(e.facts::text, t.id::text) > 0 then 'EXPERIENCE' else 'EXPERIENCE_REVISION' end,
                     e.id, e.title
                from t join experiences e on e.owner_id = t.owner_id
               where strpos(e.facts::text, t.id::text) > 0
                  or exists (select 1 from experience_revisions v
                              where v.experience_id = e.id and v.owner_id = t.owner_id and strpos(v.facts::text, t.id::text) > 0)
              union all
              select t.id, 'SUBMISSION', s.id, p.title
                from t join submission_attachments sa on sa.attachment_id = t.id and sa.owner_id = t.owner_id
                       join submissions s on s.id = sa.submission_id
                       join job_postings p on p.id = s.posting_id
            ) u order by u.type, u.name, u.ref_id
            """;

    private final AttachmentRepository repository;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    public AttachmentService(AttachmentRepository repository, CurrentUser currentUser, JdbcTemplate jdbc) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @Transactional
    public AttachmentResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "빈 파일은 올릴 수 없습니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiRuleException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "파일은 10MB 이하여야 합니다.");
        }
        byte[] data = read(file);
        Attachment saved = repository.save(
                new Attachment(currentUser.id(), safeName(file.getOriginalFilename()), sniff(data), data));
        return meta(saved);
    }

    public AttachmentResponse findMeta(UUID id) {
        return repository.findMeta(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /** 바이트까지 필요한 다운로드·미리보기 경로. */
    public Attachment findOwned(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /** 서랍 목록. 사용처는 삭제 판정과 같은 질의를 계정 단위로 한 번 돈다(첨부 A × 이력서 R 텍스트 검사, 수백 건 규모에서 충분하다). */
    public List<AttachmentListItem> list() {
        UUID owner = currentUser.id();
        Map<UUID, List<AttachmentListItem.UsedBy>> used = new HashMap<>();
        for (Usage usage : usages(owner, null)) {
            used.computeIfAbsent(usage.attachmentId(), id -> new ArrayList<>()).add(usage.usedBy());
        }
        Instant cutoff = Instant.now().minus(DRAWER_GRACE);
        return jdbc.query("select id, filename, content_type, size_bytes, created_at from attachments "
                + "where owner_id = ? order by created_at desc, id", (rs, i) -> {
            UUID id = rs.getObject(1, UUID.class);
            Instant createdAt = rs.getTimestamp(5).toInstant();
            List<AttachmentListItem.UsedBy> usedBy = used.getOrDefault(id, List.of());
            return new AttachmentListItem(id, rs.getString(2), rs.getString(3), rs.getInt(4), createdAt, usedBy,
                    usedBy.isEmpty() && !createdAt.isAfter(cutoff));
        }, owner);
    }

    /**
     * 삭제(설계 6.9 서버 규약 1). 첨부 행을 네이티브 for update 로 잡고 → 사용처를 판정해 → 쓰는 곳이 있으면 409 ATTACHMENT_IN_USE.
     * JPA PESSIMISTIC_WRITE(for no key update)는 쓰지 않는다 — 규칙 B 의 for key share 와 충돌해야 판정과 새 참조 쓰기의 순서가 정해진다.
     * 쓰기가 먼저면 여기서 기다렸다가 새 content 를 보고 409, 삭제가 먼저면 쓰기가 행을 못 찾고 400 이다.
     * 7일 조건은 서랍 화면이 건다. 서버는 저장 뒤 묻기와 서랍을 구분하지 않는다.
     */
    @Transactional
    public void delete(UUID id) {
        UUID owner = currentUser.id();
        jdbc.execute("set local lock_timeout = '3s'");
        if (jdbc.queryForList("select id from attachments where id = ? and owner_id = ? for update", UUID.class, id, owner)
                .isEmpty()) {
            throw new ResourceNotFoundException(id);
        }
        List<Usage> usages = usages(owner, id);
        if (!usages.isEmpty()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "ATTACHMENT_IN_USE", inUseMessage(usages));
        }
        jdbc.update("delete from attachments where id = ? and owner_id = ?", id, owner);
    }

    /**
     * 규칙 B(설계 6.9 서버 규약 2): 첨부 id 를 새로 적는 쓰기는 그 행을 for key share 로 잡는다. 이미 저장돼 있던 값(before)은 보지 않는다 —
     * 예전에 지워진 id 를 든 문서도 계속 저장된다. 새 값이 내 파일이 아니거나 정규형 UUID 가 아니면 400 ATTACHMENT_NOT_FOUND.
     * 정규형만 받는 이유: 사용처 판정이 텍스트 포함 검사라 대문자·축약형으로 적힌 id 는 못 찾고, 쓰는 파일이 지워질 수 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockNewReferences(UUID owner, Collection<String> before, Collection<String> after) {
        Set<String> stored = new HashSet<>(before);
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : after) {
            if (raw == null || raw.isBlank() || stored.contains(raw)) continue;
            UUID id = canonical(raw);
            if (id == null) throw notFound();
            ids.add(id);
        }
        if (!ids.isEmpty() && repository.lockForReference(ids, owner).size() != ids.size()) throw notFound();
    }

    private static UUID canonical(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            return id.toString().equals(raw) ? id : null;
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private static ApiRuleException notFound() {
        return new ApiRuleException(HttpStatus.BAD_REQUEST, "ATTACHMENT_NOT_FOUND",
                "첨부 파일을 찾을 수 없습니다. 지워졌거나 내 파일이 아닙니다. 파일을 다시 올려 주세요.");
    }

    private record Usage(UUID attachmentId, AttachmentListItem.UsedBy usedBy) {
    }

    private List<Usage> usages(UUID owner, UUID id) {
        String sql = USAGE.formatted(id == null ? "" : "and id = ?");
        Object[] args = id == null ? new Object[]{owner} : new Object[]{owner, id};
        return jdbc.query(sql, (rs, i) -> new Usage(rs.getObject(1, UUID.class),
                new AttachmentListItem.UsedBy(rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4))), args);
    }

    /** 참조원 종류별 문구. 무엇을 하면 지울 수 있는지까지 알려 준다. */
    private static String inUseMessage(List<Usage> usages) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (Usage usage : usages) {
            AttachmentListItem.UsedBy by = usage.usedBy();
            String label = switch (by.type()) {
                case "RESUME" -> "이력서 '" + by.name() + "'";
                case "EXPERIENCE" -> "경험 '" + by.name() + "'";
                case "EXPERIENCE_REVISION" -> "경험 '" + by.name() + "'의 지난 개정";
                default -> "'" + by.name() + "' 제출 기록";
            };
            names.computeIfAbsent(by.type(), type -> new ArrayList<>()).add(label);
        }
        List<String> hints = new ArrayList<>();
        if (names.containsKey("RESUME")) hints.add("이력서는 그 첨부 칸을 비우고 저장하면 됩니다");
        if (names.containsKey("EXPERIENCE")) hints.add("경험은 그 근거를 빼고 저장하면 됩니다");
        if (names.containsKey("EXPERIENCE_REVISION")) hints.add("경험의 지난 개정이 가리키는 파일은 그 경험을 지워야 지울 수 있습니다");
        if (names.containsKey("SUBMISSION")) hints.add("제출 기록이 가리키는 파일은 지울 수 없습니다");
        return "다른 곳에서 쓰고 있어 지울 수 없습니다: "
                + names.values().stream().flatMap(List::stream).distinct().collect(Collectors.joining(", "))
                + ". " + String.join(". ", hints) + ".";
    }

    /**
     * 파일 앞머리로 타입을 정한다. 확장자와 Content-Type 헤더는 클라이언트가 하는 말이라 믿지 않는다 —
     * 이 값이 그대로 다운로드 응답의 Content-Type 이 되기 때문이다.
     */
    static String sniff(byte[] data) {
        if (startsWith(data, PNG)) return "image/png";
        if (startsWith(data, JPEG)) return "image/jpeg";
        if (startsWith(data, PDF)) return "application/pdf";
        throw new ApiRuleException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                "jpg, png, pdf 만 첨부할 수 있습니다.");
    }

    /** 표시용 이름. 경로 구분자를 떼고 자른다 — 어떤 경로 조립에도 쓰지 않는다. */
    static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return "첨부파일";
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) return "첨부파일";
        return name.length() > 260 ? name.substring(0, 260) : name;
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AttachmentResponse meta(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), attachment.getFilename(),
                attachment.getContentType(), attachment.getSizeBytes());
    }
}
