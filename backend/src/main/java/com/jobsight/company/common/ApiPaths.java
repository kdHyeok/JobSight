package com.jobsight.company.common;

/**
 * 백엔드가 소유하는 모든 HTTP 경로 상수.
 *
 * 컨트롤러 @RequestMapping 과 SecurityConfig 매처가 같은 상수를 참조하므로 두 곳이 어긋날 수 없다.
 * nginx 는 이 파일을 읽지 못하므로 frontend/nginx.conf 상단의 접두사 표를 함께 갱신한다.
 * 프런트엔드 대응 상수: frontend/src/routes.ts
 */
public final class ApiPaths {
    private ApiPaths() {
    }

    public static final String API = "/api";
    public static final String MCP = "/mcp";
    public static final String PLUGIN_GUIDE = "/plugin";
    public static final String PLUGIN_CONFIG = PLUGIN_GUIDE + "/config";
    public static final String PLUGIN_SKILL = PLUGIN_GUIDE + "/skill";
    public static final String PLUGIN_DOWNLOAD = PLUGIN_GUIDE + "/download";
    public static final String MCP_METADATA = "/.well-known/oauth-protected-resource";
    public static final String OAUTH_METADATA = "/.well-known/oauth-authorization-server";
    public static final String MCP_AUTHORIZE = "/oauth2/authorize";
    public static final String MCP_TOKEN = "/oauth2/token";
    public static final String MCP_REVOKE = "/oauth2/revoke";
    public static final String MCP_INTROSPECT = "/oauth2/introspect";

    public static final String AUTH = API + "/auth";
    public static final String AUTH_ME = "/me";
    public static final String AUTH_LOGIN_OPTIONS = "/login-options";
    public static final String AUTH_REFRESH = "/refresh";
    public static final String AUTH_ADMIN_CHECK = "/admin-check";
    public static final String AUTH_LOGOUT = AUTH + "/logout";

    public static final String ADMIN = API + "/admin";
    public static final String COMPANIES = API + "/companies";
    public static final String COMPANY_CONTENTS = COMPANIES + "/{companyId}/contents";
    public static final String POSTINGS = API + "/postings";
    public static final String POSITIONS = API + "/positions";
    public static final String REFERENCES = API + "/references";
    public static final String RESUMES = API + "/resumes";
    public static final String SELF_INTRODUCTIONS = API + "/self-introductions";
    public static final String EXPERIENCES = API + "/experiences";
    public static final String ATTACHMENTS = API + "/attachments";
    public static final String REQUESTS = API + "/requests";
    public static final String ADMIN_REQUESTS = ADMIN + "/requests";

    /** Spring Security OAuth2 클라이언트가 소유하는 경로. registrationId = google. */
    public static final String OAUTH_AUTHORIZATION = "/oauth2/authorization/google";
    public static final String OAUTH_CALLBACK = "/login/oauth2/code/google";

    public static final String ACTUATOR_HEALTH = "/actuator/health";

    /** springdoc 기본 경로. ADMIN 전용으로 보호한다. */
    public static final String[] SWAGGER = {"/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"};
}
