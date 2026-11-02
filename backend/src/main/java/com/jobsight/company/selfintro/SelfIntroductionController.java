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
                    + "positionIds·questionTypes 는 null 이면 유지한다. 웹은 배열 목록으로 맞추고([] 는 비움), "
                    + "MCP 는 목록의 연결을 더하기만 한다 — 목록에 없는 기존 연결(확정·AI 기록)을 지우지 않으며, 빼는 것은 사용자가 웹에서 한다. "
                    + "더한 결과가 직무 10개·문항 유형 3개를 넘으면 TOO_MANY_LINKS. "
                    + "남는 연결은 출처(확정·AI 기록)를 유지하고 새 연결만 요청 주체의 출처로 단다. MCP 연결은 'AI 기록'이다. "
                    + "request.expectedVersion 은 직전에 읽은 응답(self_intro_get·목록 항목·직전 쓰기 응답)의 version 이다. "
                    + "웹은 필수, MCP 는 resumeIds·question·answer 를 바꿀 때 필수이고 연결만 더할 때는 선택이다. "
                    + "없으면 400 VERSION_REQUIRED, 다르면 409 SELF_INTRO_STALE — 그대로 재시도하지 말고 다시 읽어 바뀐 답변을 확인한 뒤 "
                    + "새 version 으로 보낸다. 다른 저장을 처리 중이면 409 SELF_INTRO_BUSY.")
    public SelfIntroductionResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody SelfIntroductionUpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "AI 기록 연결 확정",
            description = "웹 전용(MCP 도구 없음). 이미 연결된 직무·문항 유형의 AI 기록을 사용자 확정으로 바꾼다. "
                    + "연결되지 않은 대상이 있으면 404 LINK_NOT_FOUND. version 이 오르므로 다음 PUT 은 응답의 version 을 쓴다.")
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
