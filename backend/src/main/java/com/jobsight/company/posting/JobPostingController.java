package com.jobsight.company.posting;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.StatusUpdateRequest;
import com.jobsight.company.posting.dto.StepResultUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 모든 응답은 로그인 계정이 소유한 공고로 한정된다. 타인 소유 id 는 404. */
@Tag(name = "postings", description = "채용공고. 상태별 진행 관리, 마감된 미지원 공고와 탈락 공고 자동 보관, 절차 단계. 인증 필수.")
@RestController
@RequestMapping(ApiPaths.POSTINGS)
public class JobPostingController {
    private final JobPostingService service;

    public JobPostingController(JobPostingService service) {
        this.service = service;
    }

    @Operation(summary = "공고 목록",
            description = "기본은 진행 중 공고를 마감 임박 순(상시채용은 맨 뒤)으로. archived=true 면 보관함. "
                    + "조회 시점에 마감 지난 관심·작성 중 공고와 탈락 공고를 보관함으로 옮긴다.")
    @GetMapping
    public List<JobPostingResponse> findAll(
            @RequestParam(name = "archived", defaultValue = "false") boolean archived) {
        return archived ? service.findArchived() : service.findOpen();
    }

    @Operation(summary = "공고 상세", description = "타인 소유이거나 없으면 404.")
    @GetMapping("/{id}")
    public JobPostingResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Operation(summary = "공고 생성",
            description = "companyId 가 없으면 companyName 으로 기업을 찾거나 새로 만든다. "
                    + "positions 가 비면 제목 이름의 직무 하나를 만든다. steps 순서가 절차 순서.")
    @PostMapping
    public ResponseEntity<JobPostingResponse> create(@Valid @RequestBody JobPostingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "공고 수정",
            description = "positions·steps 를 빼거나 null 로 보내면 기존 값을 그대로 둔다. 배열을 보내면 그 목록으로 맞춘다: "
                    + "[] 면 절차는 비우고 직무는 제목 이름 하나만 남긴다. 직무는 id 로, id 가 없으면 이름(공백·대소문자 무시)으로 "
                    + "기존 직무와 맞춰 id 와 이력서·자소서 연결을 유지하고, 어느 항목과도 맞지 않은 기존 직무는 삭제한다. "
                    + "그 밖의 필드는 보낸 값으로 바뀌므로 먼저 조회해 유지할 값도 보낸다.")
    @PutMapping("/{id}")
    public JobPostingResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody JobPostingRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "지원 상태 변경",
            description = "탈락 상태는 즉시 보관하고, 진행 상태로 바꾸면 보관함에서 자동으로 꺼낸다.")
    @PatchMapping("/{id}/status")
    public JobPostingResponse changeStatus(@PathVariable UUID id,
                                           @Valid @RequestBody StatusUpdateRequest request) {
        return service.changeStatus(id, request.status());
    }

    @Operation(summary = "절차 단계 결과 변경", description = "seq 는 0-based. 없는 단계면 404.")
    @PatchMapping("/{id}/steps/{seq}")
    public JobPostingResponse changeStepResult(@PathVariable UUID id,
                                               @PathVariable int seq,
                                               @Valid @RequestBody StepResultUpdateRequest request) {
        return service.changeStepResult(id, seq, request.result());
    }

    @Operation(summary = "보관/복원", description = "archived=true 면 보관함으로, false 면 목록으로 되돌린다.")
    @PatchMapping("/{id}/archive")
    public JobPostingResponse setArchived(@PathVariable UUID id,
                                          @RequestParam(name = "archived", defaultValue = "true") boolean archived) {
        return service.setArchived(id, archived);
    }

    @Operation(summary = "공고 삭제", description = "직무·절차도 함께 삭제된다. 타인 소유이거나 없으면 404.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
