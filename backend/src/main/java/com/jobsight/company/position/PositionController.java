package com.jobsight.company.position;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.position.dto.PositionReferencesRequest;
import com.jobsight.company.position.dto.PositionRequest;
import com.jobsight.company.position.dto.PositionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 직무는 공고 폼이 만든다(POST 없음). 여기서는 열람·검색·상세 편집·참고 정보 연결·삭제. */
@Tag(name = "positions", description = "모집 직무. 공고에 종속되며 참고 정보와 N:M. 인증 필수.")
@RestController
@RequestMapping(ApiPaths.POSITIONS)
public class PositionController {
    private final PositionService service;

    public PositionController(PositionService service) {
        this.service = service;
    }

    @Operation(summary = "직무 목록", description = "q 로 이름·팀·회사·기술 스택 부분 일치 검색.")
    @GetMapping
    public List<PositionResponse> findAll(@RequestParam(name = "q", required = false) String q) {
        return service.findAll(q);
    }

    @Operation(summary = "직무 상세", description = "타인 소유이거나 없으면 404.")
    @GetMapping("/{id}")
    public PositionResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Operation(summary = "직무 수정", description = "팀·역할·업무·역량·스택 등 상세. 참고 정보는 별도 엔드포인트.")
    @PutMapping("/{id}")
    public PositionResponse update(@PathVariable UUID id, @Valid @RequestBody PositionRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "참고 정보 연결 교체", description = "referenceIds 집합으로 통째로 바꾼다. 남의 참고 정보면 404.")
    @PutMapping("/{id}/references")
    public PositionResponse replaceReferences(@PathVariable UUID id,
                                              @Valid @RequestBody PositionReferencesRequest request) {
        return service.replaceReferences(id, request);
    }

    @Operation(summary = "직무 삭제", description = "공고의 마지막 직무면 소속 공고와 절차도 함께 삭제된다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
