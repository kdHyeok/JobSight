package com.jobsight.company.mcp;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.auth.dto.MeResponse;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.companycontent.CompanyContentService;
import com.jobsight.company.companycontent.dto.CompanyContentResponse;
import com.jobsight.company.experience.ExperienceBankService;
import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.ExperienceSearch;
import com.jobsight.company.experience.ExperienceService;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceSearchRequest;
import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.position.PositionService;
import com.jobsight.company.position.dto.PositionRequest;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.reference.ReferenceService;
import com.jobsight.company.reference.dto.ReferenceResponse;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeSection;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeAgentUpdateRequest;
import com.jobsight.company.resume.dto.ResumeRequest;
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
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ExperienceService experiences;
    private final ExperienceSearch experienceSearch;
    private final ExperienceBankService bank;
    private final ObjectMapper mapper;
    public McpActions(CurrentUser current, AppUserService users, PositionService positions,
                      ReferenceService references, CompanyContentService contents, ResumeService resumes,
                      SelfIntroductionAgentService selfIntros, ExperienceService experiences,
                      ExperienceSearch experienceSearch, ExperienceBankService bank, ObjectMapper mapper) {
        this.current = current; this.users = users; this.positions = positions;
        this.references = references; this.contents = contents; this.resumes = resumes; this.selfIntros = selfIntros;
        this.experiences = experiences; this.experienceSearch = experienceSearch; this.bank = bank; this.mapper = mapper;
    }

    /** resume_get 의 보기. outline 은 목차·missing 대조용 개요다(설계 5.3). */
    public enum ResumeView { full, outline }

    @Operation(summary = "이력서 상세",
            description = "view 기본 full: content 전체(웹 REST 상세와 같은 필드)이고 응답 맨 앞에 missing(다른 이력서에만 있는 "
                    + "경험 행 묶음 — 이 이력서에 같은 (종류, 제목, 시작 연월) 행이 없는 것)을 붙인다. "
                    + "view=outline: {id, name, version, sections[{section, count, rows[{index, experienceId, title, period}]}], "
                    + "missing[], unlinkedRows[]} 개요다. 이력서를 고치거나 새로 만들기 전에 outline 의 missing 을 사용자와 대조한다. "
                    + "missing 항목 {kind, title, period, experienceId, maybeUnlinked, sources[{resumeId, resumeName, section, index, "
                    + "resumeVersion}]}: experienceId 가 있으면 이 이력서에 없는 은행 경험(resume_add_experiences 로 넣는다), "
                    + "null 이면 다른 이력서에만 있는 미연결 행 묶음이다. maybeUnlinked 는 이 이력서에 같은 종류·제목의 미연결 행이 있음. "
                    + "행의 experienceId·experienceRevision 은 지우거나 바꾸지 않는다. 타인 소유이거나 없으면 404.")
    public Object getResume(@PathVariable UUID id, @RequestParam(required = false) ResumeView view) {
        if (view == ResumeView.outline) return experiences.outline(id);
        return withMissing(resumes.findById(id));
    }

    @Operation(summary = "이력서 생성",
            description = "content 를 비우면 빈 문서로 만든다. 응답 맨 앞의 missing 은 다른 이력서에는 있는데 이 이력서에 없는 경험 행 묶음이다 — "
                    + "생성 때 빠뜨린 프로젝트가 없는지 사용자에게 확인한다(SKILL 이력서 절차).")
    public Map<String, Object> createResume(@Valid @RequestBody ResumeRequest request) {
        return withMissing(resumes.create(request));
    }

    @Operation(summary = "경험 목차",
            description = "응답은 늘 {counts, complete, nextCursor, importHint, experiences, unlinkedRows} 모양이다. "
                    + "counts{experiences(종류별 경험 수), aiRecords(검토 전 'AI 기록' 경험 수), unlinkedRows(미등록 행 수), groups(미등록 묶음 수), "
                    + "unknownSourceSelfIntros(직접 출처 없는 자소서 수)}. experiences 는 경험 원본 한 줄 {id, kind, title, period, revision, "
                    + "reviewed, skills, domains, inResumes[{id,name}], usedInSelfIntros}, unlinkedRows 는 경험 원본과 연결되지 않은 이력서 행을 "
                    + "(종류, 제목, 시작 연월)로 묶은 [{kind, title, period, sources[{resumeId, resumeName, section, index, resumeVersion}]}] 다. "
                    + "importHint 가 있으면 미등록 행을 웹 '경험 가져오기'로 등록하라고 사용자에게 안내한다(MCP 로 가져오지 않는다). "
                    + "정렬은 PROJECT → CAREER → ACTIVITY → TRAINING → OTHER, 경험 다음에 미등록 묶음. kinds 로 종류를 거르고, limit 기본·최대 60, "
                    + "complete:false 면 nextCursor 를 cursor 로 넘겨 다음 쪽을 읽는다. "
                    + "ids(10개 이하)를 주면 상세 모드: experiences 가 {id, kind, title, startYm, memo, version, revision, reviewed, "
                    + "dossierStale, skills, domains, facts, dossier, rows[{resumeId, resumeName, section, index, experienceRevision, needsReview}], "
                    + "selfIntros[{id, question, experienceRevision, trust, needsReview}], revisions[]} 다. version 은 experience_save 의 "
                    + "expectedVersion, needsReview 는 그 개정 뒤에 사실 정정이 있다는 '재검토 필요' 표시다. 이력서 작성 전 이 목차와 대조한다.")
    public ExperienceViews.Index indexExperiences(@RequestParam(required = false) List<ExperienceKind> kinds,
                                                  @RequestParam(required = false) List<UUID> ids,
                                                  @RequestParam(required = false) Integer cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return experiences.index(kinds, ids, cursor, limit);
    }

    @Operation(summary = "경험 원본 저장",
            description = "id 가 없으면 새 경험(kind·title 필수), 있으면 그 경험을 고친다(null 은 유지). 저장 내용은 'AI 기록'이고 사용자가 웹에서 검토해 확정한다. "
                    + "먼저 experience_index 로 겹치는 경험이 있는지 찾고, 있으면 새로 만들지 말고 그 경험에 저장한다. "
                    + "facts{period, role, measurement{environment, scope}, actions[], results[], evidence[], unverified[], conflicts[]}: "
                    + "배열 항목 {id, text, evidenceIds[]}. 기존 항목은 id 를 그대로 두고, 고친 항목은 같은 id 로, 새 항목은 id 없이 보낸다. "
                    + "evidence {id, sourceType RESUME_ROW|ATTACHMENT|URL|NOTE, ref{resumeId, section, index, resumeVersion | attachmentId | url(https)}, "
                    + "quote(필수, 1000자), context(3000자), location, hash, capturedAt}. 로컬 파일 경로는 근거가 아니다 — 필요한 발췌를 quote·context 에 넣는다. "
                    + "자소서 답변은 근거로 쓰지 않는다. dossier{narrative, questionUses[{competency, point}], phrasings[], cautions[]}. "
                    + "기존 경험에 facts·dossier 를 보내면 expectedVersion 필수(experience_index 상세의 version; 없으면 400 VERSION_REQUIRED, "
                    + "다르면 409 EXPERIENCE_STALE — 다시 읽고 확인한다). skills·domains 는 이름으로 보내면 정규화 키로 저장한다. "
                    + "응답 {id, version, revision, changeKind(INIT|ADD|CORRECT|NONE, 서버 판정), needsReview[](정정으로 재검토가 필요해진 "
                    + "이력서·자소서), normalizedSkills, warnings}. facts 가 같으면 개정을 만들지 않는다(NONE).")
    public ExperienceViews.Saved saveExperience(@RequestParam(required = false) UUID id,
                                                @Valid @RequestBody ExperienceRequests.Save request) {
        return bank.save(id, request);
    }

    @Operation(summary = "이력서에 경험 행 넣기",
            description = "items[{experienceId}] 마다 경험 원본의 사실로 중립 문구 행을 만들어 종류에 맞는 섹션(PROJECT→projects, "
                    + "CAREER→experiences, ACTIVITY→activities, TRAINING→trainings) 끝에 붙이고 행에 experienceId 와 지금 개정을 쓴다. "
                    + "이미 이 이력서에 있는 경험은 건너뛴다. 섹션 상한을 넘으면 하나도 넣지 않는다. OTHER 경험은 넣을 수 없다. "
                    + "expectedVersion 은 선택이다. 응답은 view=outline 과 같은 개요이고 version 을 다음 호출에 쓴다. "
                    + "기업에 맞게 문구를 다듬을 때는 resume_row_update 에 experienceId 로 그 행을 지목한다.")
    public ExperienceViews.Outline addResumeExperiences(@PathVariable UUID id,
                                                        @Valid @RequestBody ExperienceRequests.AddExperiences request,
                                                        @RequestParam(required = false) Long expectedVersion) {
        resumes.addExperiences(id, request.items(), expectedVersion);
        return experiences.outline(id);
    }

    @Operation(summary = "이력서 저장",
            description = "이름은 필수. content·positionIds·content 안의 빠진 섹션과 basic 은 null 이면 유지, [] 면 비움. "
                    + "expectedVersion 필수(조회한 version). 다르면 409 RESUME_STALE — 다시 읽고 고칠 대상을 확인한다. "
                    + "경험이 연결된 행(experienceId)이 새 문서에서 빠지면 409 LINKED_ROWS_MISSING 이다 — 일부러 지우는 것이면 그 경험 id 를 "
                    + "removedExperienceIds 에 모두 적는다. 섹션 일부 편집은 resume_row_*, 경험 행 추가는 resume_add_experiences 를 쓴다. "
                    + "행의 experienceId·experienceRevision 은 지우거나 바꾸지 않는다(개정 번호를 바꾸면 400).")
    public ResumeResponse updateResume(@PathVariable UUID id, @Valid @RequestBody ResumeAgentUpdateRequest request) {
        Set<UUID> removable = request.removedExperienceIds() == null ? Set.of() : Set.copyOf(request.removedExperienceIds());
        return resumes.update(id, request.toRequest(), removable);
    }

    @Operation(summary = "지원 맞춤 경험 찾기",
            description = "공고를 저장하지 않고 내 경험 행의 원문 출처를 찾는다. request 에 positionId(등록 직무) 또는 "
                    + "posting(저장하지 않는 공고의 다섯 칸: qualifications 지원자격, responsibilities 담당업무, requiredSkills 요구 역량, "
                    + "preferredSkills 우대 역량, techStack 기술 환경 목록, 그리고 companyName·positionName) 중 하나를 넣는다. "
                    + "고르기 전의 공고는 posting 으로 넘기고 posting_create 하지 않는다. requirements 는 공고 원문에서 뽑은 요구 "
                    + "[{text: 기술 이름, field: 칸, kind: SKILL|QUALIFICATION, quote: 공고 문장}] 다 — 내 자료에 없는 기술도 넣어야 "
                    + "uncovered 에 나온다. 빼면 서버가 기술 환경과 일반 사전으로 뽑고 warnings 에 REQUIREMENTS_PARTIAL 을 준다. "
                    + "QUALIFICATION(경력 연수·학력·고용 조건)은 점수에 넣지 않고 qualifications[] 로 돌려준다. "
                    + "results[] 는 이력서 행 묶음(type RESUME_ROW)이고 source{resumeId, resumeName, section, index, resumeVersion, "
                    + "textHash, excerpt} 가 원문 위치, alsoIn 은 같은 묶음의 다른 이력서 행, why 는 맞은 신호(REQUIREMENT 의 키·칸·인용, "
                    + "LEXICAL 의 순위·단어)다. uncovered 는 지원자격·요구 역량 칸의 요구 중 받쳐 줄 행이 없는 것(우대·기술 환경 나열은 "
                    + "결핍으로 보지 않는다). coverageIncomplete:true 면 결핍을 단정하지 않는다. limit 기본 8, 최대 20.")
    public ExperienceSearchResult searchExperiences(@Valid @RequestBody ExperienceSearchRequest request) {
        return experienceSearch.search(request);
    }

    /** 응답 맨 앞에 missing 요약을 붙인다(설계 5.3·5.5). REST 응답은 바꾸지 않는다 — 웹은 이 필드를 모른다. */
    private Map<String, Object> withMissing(ResumeResponse resume) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("missing", experiences.missing(resume.id()));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = mapper.convertValue(resume, Map.class);
        body.putAll(fields);
        return body;
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
                    + "경험 섹션(projects·experiences·activities·trainings) 행에는 experienceId 로 경험 원본을 이을 수 있다(개정은 서버가 정한다). "
                    + "경험 원본에서 행을 만들 때는 resume_add_experiences 가 낫다. "
                    + "expectedVersion 은 선택이다. 응답은 {id, version, section, count, index, row} 개요이고, "
                    + "version 을 다음 행 도구의 expectedVersion 으로 쓴다.")
    public ResumeRowResult addResumeRow(@PathVariable UUID id, @PathVariable ResumeSection section,
                                        @Valid @RequestBody ResumeRowRequest request,
                                        @RequestParam(required = false) Long expectedVersion) {
        return resumes.addRow(id, section, request, expectedVersion);
    }

    @Operation(summary = "이력서 섹션 행 수정",
            description = "대상은 index(0부터) 또는 experienceId(그 경험이 연결된 행) 중 하나다 — 경험 행은 experienceId 로 지목하면 "
                    + "행 번호가 밀려도 같은 행을 고친다. 보낸 필드만 덮고 빠진 필드는 유지한다(\"\" 는 그 필드를 비움). "
                    + "expectedVersion 필수: resume_get 이나 직전 행 도구 응답의 version. 다르면 RESUME_STALE — "
                    + "같은 index 로 재시도하지 말고 이력서를 다시 읽어 고칠 행을 제목·기간으로 다시 찾는다.")
    public ResumeRowResult updateResumeRow(@PathVariable UUID id, @PathVariable ResumeSection section,
                                           @RequestParam(required = false) Integer index,
                                           @RequestParam(required = false) UUID experienceId,
                                           @Valid @RequestBody ResumeRowRequest request,
                                           @RequestParam Long expectedVersion) {
        return resumes.updateRow(id, section, index, experienceId, request, expectedVersion);
    }

    @Operation(summary = "이력서 섹션 행 삭제",
            description = "대상은 index(0부터) 또는 experienceId 중 하나다. 뒤 행이 앞으로 당겨진다. expectedVersion 필수이고, 응답의 version 을 "
                    + "다음 호출에 쓴다. 다르면 RESUME_STALE — 같은 index 로 재시도하지 말고 다시 읽는다.")
    public ResumeRowResult deleteResumeRow(@PathVariable UUID id, @PathVariable ResumeSection section,
                                           @RequestParam(required = false) Integer index,
                                           @RequestParam(required = false) UUID experienceId,
                                           @RequestParam Long expectedVersion) {
        return resumes.deleteRow(id, section, index, experienceId, expectedVersion);
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
                    + "alreadyUsedFor 의 otherCompanySpecific:true 는 다른 회사용 지원동기·포부·직무 이해 답변이라 본문을 재사용하지 않는다. "
                    + "experiences[{experienceId, experienceRevision, title, trust, needsReview}] 는 이 답변의 작성 근거(쓴 경험과 그때 개정)다.")
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
