package com.jobsight.company.writingguide;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 계정당 작성 기준 Markdown 하나(설계 6.7). 공통 절차는 SKILL 에, "내가 선호하는 표현"·"이 성과를 설명할 때 지킬 범위"는 여기에 둔다.
 * MCP 는 읽기만 하고(writing_guide_get), 수정은 웹에서 한다. 아직 쓰지 않았으면 version 0, 빈 본문이다.
 */
@Service
@Transactional(readOnly = true)
public class WritingGuideService {
    public static final int MAX_BODY = 20000;

    public record Guide(int version, String body, Instant updatedAt) {}

    public record UpdateRequest(
            @NotNull(message = "본문은 필수입니다(비우려면 \"\").")
            @Size(max = MAX_BODY, message = "작성 기준은 20,000자 이하여야 합니다.") String body,
            /** 읽은 version(처음이면 0). 다르면 409 WRITING_GUIDE_STALE. */
            @NotNull(message = "expectedVersion 은 필수입니다.") Integer expectedVersion) {}

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public WritingGuideService(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    public Guide get() {
        return find(currentUser.id(), false);
    }

    /** 자소서 writing_context 에 남길 작성 기준 version(없으면 0). */
    public int version(UUID owner) {
        return find(owner, false).version();
    }

    /** 행을 잠근 뒤 version 을 확인한다. 같은 계정이 처음 두 번 동시에 쓰면 둘째는 PK 위반으로 409 CONFLICT 다. */
    @Transactional
    public Guide update(UpdateRequest request) {
        UUID owner = currentUser.id();
        Guide current = find(owner, true);
        if (current.version() != request.expectedVersion()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "WRITING_GUIDE_STALE",
                    "다른 곳에서 작성 기준을 먼저 저장했습니다. 다시 읽고 고치세요.");
        }
        if (current.version() == 0) {
            jdbc.update("insert into writing_guides (owner_id, body, version, updated_at) values (?, ?, 1, now())", owner, request.body());
        } else {
            jdbc.update("update writing_guides set body = ?, version = version + 1, updated_at = now() where owner_id = ?",
                    request.body(), owner);
        }
        return find(owner, false);
    }

    private Guide find(UUID owner, boolean lock) {
        List<Guide> rows = jdbc.query("select version, body, updated_at from writing_guides where owner_id = ?"
                        + (lock ? " for no key update" : ""),
                (rs, i) -> new Guide(rs.getInt(1), rs.getString(2), rs.getTimestamp(3).toInstant()), owner);
        return rows.isEmpty() ? new Guide(0, "", null) : rows.get(0);
    }
}
