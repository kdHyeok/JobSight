package com.jobsight.company.submission;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.submission.dto.SubmissionRequest;
import com.jobsight.company.submission.dto.SubmissionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 공고별 제출 기록(설계 6.6). 확인은 웹 전용이라 MCP 도구로 등록하지 않는다. */
@RestController
@RequestMapping(ApiPaths.POSTING_SUBMISSIONS)
@Tag(name = "submissions", description = "공고별 제출 기록과 확인 수준. 인증 필수.")
public class SubmissionController {
    private final SubmissionService service;

    public SubmissionController(SubmissionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "제출 기록 목록", description = "최신순. LINKED 는 기록 당시 연결 자료, USER_CONFIRMED 는 사용자가 확인한 제출본, "
            + "RECEIPT_VERIFIED 는 접수 근거와 대조한 제출본이다.")
    public List<SubmissionResponse> findAll(@PathVariable UUID postingId) {
        return service.list(postingId);
    }

    @PostMapping
    @Operation(summary = "제출본 확인", description = "고른 이력서·자기소개의 지금 내용을 USER_CONFIRMED 또는 RECEIPT_VERIFIED 로 남긴다. "
            + "같은 내용이면 기존 기록을 돌려준다.")
    public SubmissionResponse confirm(@PathVariable UUID postingId, @Valid @RequestBody SubmissionRequest request) {
        return service.confirm(postingId, request);
    }
}
