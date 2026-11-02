package com.jobsight.company.selfintro;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.selfintro.dto.SelfIntroductionConfirmRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping(ApiPaths.SELF_INTRODUCTIONS)
@Tag(name = "self-introductions", description = "이력서별 자기소개 질문과 답변. 인증 필수.")
public class SelfIntroductionController {
    private final SelfIntroductionService service;

    public SelfIntroductionController(SelfIntroductionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "자기소개 문항 검색", description = "질문·답변의 동일 단어를 BM25 점수순으로 찾는다. 답변은 전문이다.")
    public List<SelfIntroductionResponse> findAll(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID resumeId) {
        return service.findAll(q, resumeId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "자기소개 문항 상세 조회")
    public SelfIntroductionResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @Operation(summary = "자기소개 문항 추가",
            description = "이력서(resumeIds) 또는 지원 직무(positionIds, 10개 이하)를 1개 이상 연결한다. "
                    + "positionIds 는 이 답변을 어느 지원용으로 썼나(직접 출처), questionTypes 는 문항 유형 코드(3개 이하)다. "
                    + "MCP 로 만든 직무·문항 유형 연결은 'AI 기록'이고, 확정은 사용자가 웹에서만 한다.")
    public ResponseEntity<SelfIntroductionResponse> create(@Valid @RequestBody SelfIntroductionCreateRequest request) {
        SelfIntroductionResponse created = service.create(request);
        return ResponseEntity.created(URI.create(ApiPaths.SELF_INTRODUCTIONS + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "자기소개 문항 변경",
            description = "resumeIds·question·answer 는 보낸 값으로 바꾼다(연결 0개 허용). "
                    + "positionIds·questionTypes 는 null 이면 유지, 배열이면 그 목록으로 맞춘다([] 는 비움). "
                    + "남는 연결은 출처(확정·AI 기록)를 유지하고 새 연결만 요청 주체의 출처로 단다. MCP 연결은 'AI 기록'이다.")
    public SelfIntroductionResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody SelfIntroductionUpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "AI 기록 연결 확정",
            description = "웹 전용(MCP 도구 없음). 이미 연결된 직무·문항 유형의 AI 기록을 사용자 확정으로 바꾼다. "
                    + "연결되지 않은 대상이 있으면 404 LINK_NOT_FOUND.")
    public SelfIntroductionResponse confirm(@PathVariable UUID id,
                                            @Valid @RequestBody SelfIntroductionConfirmRequest request) {
        return service.confirm(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "자기소개 문항 삭제")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
