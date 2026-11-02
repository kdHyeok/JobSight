package com.jobsight.company.experience;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 경험 원본 REST — docs/experiences.md. 웹 화면(4b)이 쓴다. 검토 확정·삭제·가져오기는 웹 전용이라 MCP 도구가 없다(설계 5.2).
 * 모든 응답은 로그인 계정이 소유한 경험으로 한정된다. 타인 소유 id 는 404.
 */
@Tag(name = "experiences", description = "경험 원본(사실·소재집·개정 이력)과 이력서 행 가져오기. 인증 필수.")
@RestController
@RequestMapping(ApiPaths.EXPERIENCES)
public class ExperienceController {
    private final ExperienceService views;
    private final ExperienceBankService bank;
    private final ExperienceImportService imports;

    public ExperienceController(ExperienceService views, ExperienceBankService bank, ExperienceImportService imports) {
        this.views = views;
        this.bank = bank;
        this.imports = imports;
    }

    @Operation(summary = "경험 목차", description = "MCP experience_index 와 같은 모양 {counts, complete, nextCursor, importHint, "
            + "experiences, unlinkedRows}. ids(10개 이하)를 주면 상세 모드다.")
    @GetMapping
    public ExperienceViews.Index index(@RequestParam(required = false) List<ExperienceKind> kinds,
                                       @RequestParam(required = false) List<UUID> ids,
                                       @RequestParam(required = false) Integer cursor,
                                       @RequestParam(required = false) Integer limit) {
        return views.index(kinds, ids, cursor, limit);
    }

    @Operation(summary = "경험 상세", description = "사실·소재집·개정 이력, 연결된 이력서 행과 자소서(재검토 필요 표시). version 은 다음 저장의 expectedVersion.")
    @GetMapping("/{id}")
    public ExperienceViews.Detail detail(@PathVariable UUID id) {
        return views.detail(id);
    }

    @Operation(summary = "경험 만들기", description = "kind·title 필수. 웹에서 만든 경험은 사용자 검토가 끝난 것으로 본다.")
    @PostMapping
    public ResponseEntity<ExperienceViews.Saved> create(@Valid @RequestBody ExperienceRequests.Save request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bank.save(null, request));
    }

    @Operation(summary = "경험 고치기", description = "null 은 유지. facts·dossier 를 보내면 expectedVersion 필수(없으면 400 VERSION_REQUIRED, "
            + "다르면 409 EXPERIENCE_STALE). facts 배열 항목은 기존 id 를 그대로 두고, 고친 항목은 같은 id, 새 항목은 id 없이 보낸다. "
            + "개정 종류(ADD·CORRECT)는 서버가 판정해 changeKind 로 준다. facts 가 같으면 개정을 만들지 않는다(NONE).")
    @PutMapping("/{id}")
    public ExperienceViews.Saved update(@PathVariable UUID id, @Valid @RequestBody ExperienceRequests.Save request) {
        return bank.save(id, request);
    }

    @Operation(summary = "검토 확정", description = "웹 전용. 보고 있던 revision 을 보낸다. 그사이 개정이 올랐으면 409 EXPERIENCE_STALE.")
    @PostMapping("/{id}/review")
    public ExperienceViews.Detail review(@PathVariable UUID id, @Valid @RequestBody ExperienceRequests.Review request) {
        return bank.review(id, request);
    }

    @Operation(summary = "경험 삭제", description = "웹 전용. 이력서가 쓰면 409 EXPERIENCE_IN_USE(이력서 이름). 자소서 작성 근거가 가리키면 "
            + "detach=true 없이는 409, detach=true 면 근거를 끊고 지운다. 개정 이력도 함께 지워진다.")
    @DeleteMapping("/{id}")
    public ExperienceViews.Deleted delete(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean detach) {
        return bank.delete(id, detach);
    }

    @Operation(summary = "가져오기 계획", description = "앵커가 없는 이력서 경험 행을 (종류, 제목, 시작 연월)로 묶은 계획. 저장하지 않는다.")
    @GetMapping("/import-plan")
    public ExperienceViews.ImportPlan importPlan() {
        return imports.plan();
    }

    @Operation(summary = "가져오기 적용", description = "묶음마다 CREATE·SKIP·LINK_TO. 계획의 resumeVersions 와 다른 이력서가 있으면 "
            + "409 IMPORT_PLAN_STALE — 계획을 다시 만든다. 행 텍스트는 바꾸지 않고 앵커만 쓴다.")
    @PostMapping("/import")
    public ExperienceViews.ImportResult importApply(@Valid @RequestBody ExperienceRequests.Import request) {
        return imports.apply(request);
    }
}
