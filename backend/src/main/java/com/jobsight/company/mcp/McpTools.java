package com.jobsight.company.mcp;

import com.jobsight.company.admin.AdminController;
import com.jobsight.company.auth.AuthController;
import com.jobsight.company.company.CompanyController;
import com.jobsight.company.companycontent.CompanyContentController;
import com.jobsight.company.position.PositionController;
import com.jobsight.company.posting.JobPostingController;
import com.jobsight.company.reference.ReferenceController;
import com.jobsight.company.resume.ResumeController;
import com.jobsight.company.selfintro.SelfIntroductionController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Validator;
import org.springframework.http.ResponseEntity;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.lang.reflect.*;
import java.util.*;

/** Explicit method allowlist. DTOs and @Operation remain the API source of truth. */
@Component
public class McpTools {
    private record Tool(Object bean, Method method, Operation operation, String scope,
                        boolean destructive, boolean readOnly) {}
    private final Map<String, Tool> tools = new TreeMap<>();
    private final ObjectMapper mapper;
    private final Validator validator;

    public McpTools(CompanyController companies, JobPostingController postings, PositionController positions,
                    ReferenceController references, CompanyContentController contents, ResumeController resumes,
                    SelfIntroductionController selfIntroductions, AdminController admin, AuthController auth,
                    McpActions actions, ObjectMapper mapper, Validator validator) {
        this.mapper = mapper; this.validator = validator;
        register("company_list", companies, "findAll", true, false);
        register("company_get", companies, "findById", true, false);
        register("company_create", companies, "create", false, false);
        register("company_update", companies, "update", false, true);
        register("company_delete", companies, "delete", false, true);
        // Listing triggers the same auto-archive as the website; do not advertise it as read-only.
        register("posting_list", postings, "findAll", false, false);
        register("posting_get", postings, "findById", true, false);
        register("posting_create", postings, "create", false, false);
        register("posting_update", postings, "update", false, true);
        register("posting_delete", postings, "delete", false, true);
        register("posting_set_status", postings, "changeStatus", false, false);
        register("posting_set_step_result", postings, "changeStepResult", false, false);
        register("posting_set_archived", postings, "setArchived", false, false);
        register("position_list", positions, "findAll", true, false);
        register("position_get", positions, "findById", true, false);
        register("position_create", actions, "createPosition", false, false);
        register("position_update", positions, "update", false, true);
        register("position_delete", positions, "delete", false, true);
        register("position_replace_references", positions, "replaceReferences", false, true);
        register("reference_list", references, "findAll", true, false);
        register("reference_get", actions, "getReference", true, false);
        register("reference_create", references, "create", false, false);
        register("reference_update", references, "update", false, true);
        register("reference_delete", references, "delete", false, true);
        register("company_content_list", contents, "findAll", true, false);
        register("company_content_get", actions, "getContent", true, false);
        register("company_content_create", contents, "create", false, false);
        register("company_content_update", contents, "update", false, true);
        register("company_content_delete", contents, "delete", false, true);
        register("resume_list", resumes, "findAll", true, false);
        // 상세·생성은 응답 머리에 missing 을 붙이는 래퍼다(설계 5.3, 3단계). 웹 REST 응답은 그대로다.
        register("resume_get", actions, "getResume", true, false);
        register("resume_create", actions, "createResume", false, false);
        // 저장은 LINKED_ROWS_MISSING 가드와 removedExperienceIds 가 있는 래퍼다(설계 5.3, 4단계). 웹 PUT 은 가드가 없다.
        register("resume_update", actions, "updateResume", false, true);
        register("resume_copy", resumes, "copy", false, false);
        register("resume_delete", resumes, "delete", false, true);
        // 행 도구: 서버가 조회→수정→저장을 한 트랜잭션으로 묶는다. 문서 전체를 되돌려 보내지 않아도 된다.
        register("resume_basic_update", actions, "updateResumeBasic", false, true);
        register("resume_row_add", actions, "addResumeRow", false, false);
        register("resume_row_update", actions, "updateResumeRow", false, true);
        register("resume_row_delete", actions, "deleteResumeRow", false, true);
        register("resume_add_experiences", actions, "addResumeExperiences", false, false);
        // 목록·상세는 미리보기·출처·trust 를 싣는 MCP 전용 모양이다. 웹 REST 목록(답변 전문)은 그대로 둔다(설계 5.3).
        // 확정(confirm)은 웹 전용이라 등록하지 않는다.
        register("self_intro_list", actions, "listSelfIntros", true, false);
        register("self_intro_get", actions, "getSelfIntro", true, false);
        register("self_intro_create", selfIntroductions, "create", false, false);
        register("self_intro_update", selfIntroductions, "update", false, true);
        register("self_intro_delete", selfIntroductions, "delete", false, true);
        // 경험 목차·검색·저장(4단계): 경험 원본과 미연결 행 묶음이 자료원이다. 검토 확정·삭제·가져오기는 웹 전용이라 등록하지 않는다.
        register("experience_index", actions, "indexExperiences", true, false);
        register("experience_search", actions, "searchExperiences", true, false);
        register("experience_save", actions, "saveExperience", false, false);
        register("account_get", actions, "me", true, false);
        register("account_update", auth, "updateMe", false, true);
        register("admin_users_list", admin, "findUsers", true, false);
        register("admin_user_counts", admin, "findUserDataCounts", true, false);
        register("admin_user_set_status", admin, "changeStatus", false, true);
        register("admin_user_set_role", admin, "changeRole", false, true);
        register("admin_user_set_name", admin, "changeName", false, true);
        register("admin_user_delete", admin, "deleteUser", false, true);
        register("admin_settings_get", admin, "findSettings", true, false);
        register("admin_settings_update", admin, "updateSettings", false, true);
    }

    private void register(String name, Object bean, String methodName, boolean readOnly, boolean destructive) {
        Method method = Arrays.stream(AopUtils.getTargetClass(bean).getMethods()).filter(m -> m.getName().equals(methodName))
                .findFirst().orElseThrow();
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
        if (operation == null) throw new IllegalStateException(
                "MCP tool method is missing @Operation: " + method.getDeclaringClass().getName() + "." + methodName);
        tools.put(name, new Tool(bean, method, operation, name.startsWith("admin_") ? McpOAuthConfig.ADMIN
                : readOnly ? McpOAuthConfig.READ : McpOAuthConfig.WRITE, destructive, readOnly));
    }

    private boolean permitted(Tool tool, McpPrincipal principal) {
        if (!principal.scopes().contains(tool.scope)) return false;
        if (!tool.scope.equals(McpOAuthConfig.ADMIN)) return true;
        return principal.scopes().contains(tool.readOnly ? McpOAuthConfig.READ : McpOAuthConfig.WRITE)
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public List<Map<String, Object>> list(McpPrincipal principal) {
        return tools.entrySet().stream().filter(entry -> permitted(entry.getValue(), principal)).map(entry -> {
            var tool = entry.getValue();
            var operation = tool.operation;
            String description = operation.summary() + ". " + operation.description();
            // 이력서와 공고 수정은 null=유지라 이 문구가 틀린다 — 규칙은 ResumeController·JobPostingController 설명에 있다
            // (docs/resumes.md 불변 조건 9, docs/job-postings.md 불변 조건 9).
            if (tool.method.getName().equals("update") && !entry.getKey().startsWith("resume_")
                    && !entry.getKey().equals("posting_update")) {
                description += " 전체 교체: 먼저 조회하고 유지할 필드도 request에 포함하세요.";
            }
            List<String> scopes = new ArrayList<>(List.of(tool.scope));
            if (tool.scope.equals(McpOAuthConfig.ADMIN)) scopes.add(tool.readOnly ? McpOAuthConfig.READ : McpOAuthConfig.WRITE);
            return Map.<String, Object>of("name", entry.getKey(), "title", operation.summary(),
                    "description", description, "inputSchema", inputSchema(tool.method),
                    "annotations", Map.of("readOnlyHint", tool.readOnly, "destructiveHint", tool.destructive,
                            "idempotentHint", tool.readOnly, "openWorldHint", false),
                    "securitySchemes", List.of(Map.of("type", "oauth2", "scopes", scopes)));
        }).toList();
    }

    public Object call(String name, Map<String, Object> arguments, McpPrincipal principal) {
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        if (!permitted(tool, principal)) throw new AccessDeniedException("이 도구의 OAuth scope와 계정 권한이 필요합니다.");
        var parameters = tool.method.getParameters();
        var names = Arrays.stream(parameters).map(Parameter::getName).toList();
        if (!names.containsAll(arguments.keySet())) throw new IllegalArgumentException("Unknown arguments");
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            var p = parameters[i];
            Object raw = arguments.get(p.getName());
            var query = p.getAnnotation(RequestParam.class);
            if (raw == null && query != null && !query.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                raw = p.getType() == boolean.class ? Boolean.valueOf(query.defaultValue()) : query.defaultValue();
            }
            if (raw == null && (query == null || query.required())) throw new IllegalArgumentException("Missing argument: " + p.getName());
            McpSchema.validateShape(raw, p.getParameterizedType());
            values[i] = mapper.readerFor(mapper.constructType(p.getParameterizedType()))
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(mapper.writeValueAsString(raw));
            if (values[i] != null && p.getType().isRecord()) {
                var violations = validator.validate(values[i]);
                if (!violations.isEmpty()) throw new IllegalArgumentException(violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList().toString());
            }
        }
        try {
            Object result = tool.method.invoke(tool.bean, values);
            if (result instanceof ResponseEntity<?> response) result = response.getBody();
            return result == null ? Map.of("success", true) : result;
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private Map<String, Object> inputSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var p : method.getParameters()) {
            var schema = new LinkedHashMap<>(McpSchema.of(p.getParameterizedType()));
            // 문자열 인자의 길이 상한을 스키마에 드러낸다(self_intro_list 의 q 100자).
            var size = p.getAnnotation(jakarta.validation.constraints.Size.class);
            if (size != null && p.getType() == String.class) schema.put("maxLength", size.max());
            properties.put(p.getName(), schema);
            var query = p.getAnnotation(RequestParam.class);
            if (query == null || (query.required() && query.defaultValue().equals(ValueConstants.DEFAULT_NONE))) required.add(p.getName());
        }
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
