package com.jobsight.company.mcp;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.auth.dto.MeResponse;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.companycontent.CompanyContentService;
import com.jobsight.company.companycontent.dto.CompanyContentResponse;
import com.jobsight.company.position.PositionService;
import com.jobsight.company.position.dto.PositionRequest;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.reference.ReferenceService;
import com.jobsight.company.reference.dto.ReferenceResponse;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeSection;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.resume.dto.ResumeRowRequest;
import com.jobsight.company.resume.dto.ResumeRowResult;
import com.jobsight.company.selfintro.Competency;
import com.jobsight.company.selfintro.SelfIntroductionAgentService;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.user.AppUserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Only the small operations that do not have an existing REST controller method. */
@Component
public class McpActions {
    private final CurrentUser current;
    private final AppUserService users;
    private final PositionService positions;
    private final ReferenceService references;
    private final CompanyContentService contents;
    private final ResumeService resumes;
    private final SelfIntroductionAgentService selfIntros;
    public McpActions(CurrentUser current, AppUserService users, PositionService positions,
                      ReferenceService references, CompanyContentService contents, ResumeService resumes,
                      SelfIntroductionAgentService selfIntros) {
        this.current = current; this.users = users; this.positions = positions;
        this.references = references; this.contents = contents; this.resumes = resumes; this.selfIntros = selfIntros;
    }
    @Operation(summary = "연결된 내 계정 조회")
    public MeResponse me() { return MeResponse.of(users.findById(current.id()).orElseThrow()); }

    @Operation(summary = "기존 공고에 모집 직무 추가", description = "다른 직무를 유지하면서 상세 필드를 가진 직무를 추가한다. 공고당 최대 30개.")
    public PositionResponse createPosition(@PathVariable UUID postingId, @Valid @RequestBody PositionRequest request) {
        return positions.create(postingId, request);
    }

    @Operation(summary = "참고 정보 상세 조회")
    public ReferenceResponse getReference(@PathVariable UUID id) {
        return references.findAll(null).stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Operation(summary = "이력서 기본정보 변경",
            description = "basic(성명·연락처·주소·링크)에 보낸 필드만 덮고 빠진 필드는 유지한다(\"\" 는 그 필드를 비움). "
                    + "다른 섹션은 그대로다. expectedVersion 은 선택이다(보내면 다를 때 RESUME_STALE).")
    public ResumeResponse updateResumeBasic(@PathVariable UUID id, @Valid @RequestBody ResumeContent.BasicInfo request,
                                            @RequestParam(required = false) Long expectedVersion) {
        return resumes.updateBasic(id, request, expectedVersion);
    }

    @Operation(summary = "이력서 섹션에 행 추가",
            description = "section 은 educations/trainings/activities/experiences/awards/certificates/skills/projects. "
                    + "request 에는 그 섹션의 필드만 넣는다(다른 섹션 필드는 UNKNOWN_ROW_FIELD). 맨 뒤에 붙는다. "
                    + "expectedVersion 은 선택이다. 응답은 {id, version, section, count, index, row} 개요이고, "
                    + "version 을 다음 행 도구의 expectedVersion 으로 쓴다.")
    public ResumeRowResult addResumeRow(@PathVariable UUID id, @PathVariable ResumeSection section,
                                        @Valid @RequestBody ResumeRowRequest request,
                                        @RequestParam(required = false) Long expectedVersion) {
        return resumes.addRow(id, section, request, expectedVersion);
    }

    @Operation(summary = "이력서 섹션 행 수정",
            description = "index 는 0부터. 보낸 필드만 덮고 빠진 필드는 유지한다(\"\" 는 그 필드를 비움). "
                    + "expectedVersion 필수: resume_get 이나 직전 행 도구 응답의 version. 다르면 RESUME_STALE — "
                    + "같은 index 로 재시도하지 말고 이력서를 다시 읽어 고칠 행을 제목·기간으로 다시 찾는다.")
    public ResumeRowResult updateResumeRow(@PathVariable UUID id, @PathVariable ResumeSection section,
                                           @PathVariable int index, @Valid @RequestBody ResumeRowRequest request,
                                           @RequestParam Long expectedVersion) {
        return resumes.updateRow(id, section, index, request, expectedVersion);
    }

    @Operation(summary = "이력서 섹션 행 삭제",
            description = "index 는 0부터. 뒤 행이 앞으로 당겨진다. expectedVersion 필수이고, 응답의 version 을 "
                    + "다음 호출에 쓴다. 다르면 RESUME_STALE — 같은 index 로 재시도하지 말고 다시 읽는다.")
    public ResumeRowResult deleteResumeRow(@PathVariable UUID id, @PathVariable ResumeSection section,
                                           @PathVariable int index, @RequestParam Long expectedVersion) {
        return resumes.deleteRow(id, section, index, expectedVersion);
    }

    /** 웹 REST 목록(답변 전문)을 바꾸지 않도록 MCP 전용 모양을 따로 둔다 — 설계 5.1·5.3. */
    @Operation(summary = "자기소개 문항 검색",
            description = "필터는 모두 AND 다. positionId·postingId·companyId 는 그 직무용으로 쓴 답변(직접 출처 written_for)을 찾는다. "
                    + "직접 출처가 0건이면 fallback:\"indirect\" 로 연결 이력서가 그 직무를 지원하는 '출처 미상' 답변을 준다. "
                    + "questionType 은 문항 유형(사용자 확정 태그, 없으면 정규식 추정. AI 기록 태그는 추정을 지우지 않고 덧붙는다), "
                    + "unlinked:true 는 직접 출처가 없는 답변만. "
                    + "q 는 100자 이하, 질문·답변의 같은 단어를 BM25 점수순으로. limit 기본 10(최대 50), complete:false 면 잘린 것이다. "
                    + "답변은 300자 미리보기(answerPreview)다. 원문은 self_intro_get. "
                    + "trust 는 '확정'(사용자가 웹에서 연결)·'AI 기록'(확정 전)·'추정'이며 관계 확인 여부일 뿐 성과 검증이 아니다. "
                    + "alreadyUsedFor 의 otherCompanySpecific:true 는 다른 회사용 지원동기·포부·직무 이해 답변이라 본문을 재사용하지 않는다.")
    public SelfIntroductionList listSelfIntros(@RequestParam(required = false) @Size(max = 100) String q,
                                               @RequestParam(required = false) UUID resumeId,
                                               @RequestParam(required = false) UUID positionId,
                                               @RequestParam(required = false) UUID postingId,
                                               @RequestParam(required = false) UUID companyId,
                                               @RequestParam(required = false) Competency questionType,
                                               @RequestParam(required = false) Boolean unlinked,
                                               @RequestParam(required = false) Integer limit) {
        return selfIntros.list(q, resumeId, positionId, postingId, companyId, questionType, unlinked, limit);
    }

    @Operation(summary = "자기소개 문항 상세 조회",
            description = "답변 전문(answer)과 직접·간접 출처, 문항 유형, alreadyUsedFor 를 준다. 수정 전에 이것으로 원문을 읽는다. "
                    + "version 은 self_intro_update 의 request.expectedVersion 으로 보낸다(질문·답변·이력서를 바꿀 때 필수).")
    public SelfIntroductionList.Item getSelfIntro(@PathVariable UUID id) {
        return selfIntros.get(id);
    }

    @Operation(summary = "기업 뉴스·유튜브 상세 조회")
    public CompanyContentResponse getContent(@PathVariable UUID companyId, @PathVariable UUID id) {
        return contents.findAll(companyId).stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }
}
