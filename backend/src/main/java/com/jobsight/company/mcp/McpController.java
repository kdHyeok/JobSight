package com.jobsight.company.mcp;

import com.jobsight.company.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Stateless Streamable HTTP: JSON POST responses, no server-initiated SSE/session. */
@RestController
public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private final McpTools tools;
    private final McpOAuthConfig oauth;
    private final ObjectMapper mapper;
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    public McpController(McpTools tools, McpOAuthConfig oauth, ObjectMapper mapper) {
        this.tools = tools; this.oauth = oauth; this.mapper = mapper;
    }
    @GetMapping({ApiPaths.MCP_METADATA, ApiPaths.MCP_METADATA + ApiPaths.MCP})
    public Map<String, Object> metadata() {
        return Map.of("resource", oauth.resource(), "authorization_servers", List.of(oauth.base()),
                "scopes_supported", McpOAuthConfig.SCOPES, "bearer_methods_supported", List.of("header"),
                "resource_documentation", oauth.base() + ApiPaths.PLUGIN_GUIDE);
    }

    @GetMapping(ApiPaths.MCP)
    public ResponseEntity<Void> stream() { return ResponseEntity.status(405).build(); }

    @PostMapping(value = ApiPaths.MCP, consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> rpc(@RequestBody Map<String, Object> message,
                                @RequestHeader(value = "MCP-Protocol-Version", required = false) String version) {
        Object id = message.get("id");
        if (!"2.0".equals(message.get("jsonrpc")) || !(message.get("method") instanceof String method)
                || (id != null && !(id instanceof String || id instanceof Number))) return error(id, -32600, "Invalid Request");
        if (version != null && !VERSIONS.contains(version)) return ResponseEntity.badRequest().body(Map.of("error", "Unsupported MCP protocol version"));
        if (id == null) {
            if (method.startsWith("notifications/")) return ResponseEntity.accepted().build();
            return error(null, -32600, "A request id is required");
        }
        Map<String, Object> params;
        try {
            params = object(message.getOrDefault("params", Map.of()));
        } catch (IllegalArgumentException e) { return error(id, -32602, e.getMessage()); }
        var principal = (McpPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        switch (method) {
            case "initialize":
                return result(id, Map.of("protocolVersion", params.get("protocolVersion") != null && VERSIONS.contains(params.get("protocolVersion"))
                        ? params.get("protocolVersion") : "2025-11-25", "capabilities", Map.of("tools", Map.of(), "resources", Map.of()),
                        "serverInfo", Map.of("name", "jobsight", "version", "0.2.0"),
                        "instructions", PluginGuideController.skillText()));
            case "ping": return result(id, Map.of());
            case "resources/list": return result(id, Map.of("resources", List.of(Map.of("uri", PluginGuideController.SKILL_URI,
                    "name", "JobSight 사용 지침", "description", "기업·공고·직무·참고·뉴스/유튜브·이력서·자기소개·경험 찾기 도구와 안전한 수정 규칙", "mimeType", "text/markdown"))));
            case "resources/read":
                if (!PluginGuideController.SKILL_URI.equals(params.get("uri"))) return error(id, -32002, "Resource not found");
                return result(id, Map.of("contents", List.of(Map.of("uri", PluginGuideController.SKILL_URI,
                        "mimeType", "text/markdown", "text", PluginGuideController.skillText()))));
            case "tools/list": return result(id, Map.of("tools", tools.list(principal)));
            case "tools/call": return callTool(id, params, principal);
            default: return error(id, -32601, "Method not found");
        }
    }

    /**
     * 도구 호출 하나마다 INFO 한 줄: 도구 이름·대상 id·ms·결과 코드. 인자·응답 본문은 남기지 않는다(개인 자료).
     * 0단계 측정(설계 1.2, 7절 운영 메모 R54)과 6단계 "쓰이지 않는 도구 정리"의 사용률 근거다.
     */
    private ResponseEntity<?> callTool(Object id, Map<String, Object> params, McpPrincipal principal) {
        long started = System.nanoTime();
        Object rawName = params.get("name");
        String target = "-";
        String code = "OK";
        try {
            if (!(rawName instanceof String name)) throw new IllegalArgumentException("Missing tool name");
            Map<String, Object> arguments = object(params.getOrDefault("arguments", Map.of()));
            target = target(arguments);
            Object data = tools.call(name, arguments, principal);
            return result(id, Map.of("content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(data))),
                    "structuredContent", Map.of("data", data), "isError", false));
        } catch (IllegalArgumentException e) {
            code = "INVALID_ARGUMENT";
            return toolError(id, code, e.getMessage());
        } catch (tools.jackson.core.JacksonException e) {
            code = "INVALID_ARGUMENT";
            return toolError(id, code, "입력 형식이나 enum 값이 올바르지 않습니다.");
        } catch (RuntimeException e) {
            // 도메인 예외와 500(로그 포함)은 REST 와 같은 변환을 쓴다 — 코드가 갈리지 않게.
            ApiError error = ApiErrors.translate(e);
            code = error.code();
            return toolError(id, code, error.message());
        } finally {
            log.info("mcp tool={} target={} ms={} result={}", toolName(rawName), target,
                    (System.nanoTime() - started) / 1_000_000, code);
        }
    }

    /** 로그에 남길 도구 이름. 클라이언트가 보낸 문자열이라 이름 문자만 남겨 로그 줄을 깨지 못하게 한다. */
    static String toolName(Object raw) {
        if (!(raw instanceof String name)) return "-";
        String safe = name.replaceAll("[^A-Za-z0-9_]", "?");
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    /** 대상 id: 맨 위 인자 중 처음 보이는 UUID(id → positionId → postingId → companyId → resumeId). 없으면 "-". */
    static String target(Map<String, Object> arguments) {
        for (String key : List.of("id", "positionId", "postingId", "companyId", "resumeId")) {
            if (arguments.get(key) instanceof String value) {
                try {
                    return UUID.fromString(value).toString();
                } catch (IllegalArgumentException ignored) {
                    return "-";
                }
            }
        }
        return "-";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected object");
        return (Map<String, Object>) map;
    }
    private ResponseEntity<?> result(Object id, Object result) {
        return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }
    private ResponseEntity<?> toolError(Object id, String code, String message) {
        return result(id, Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", code + ": " + message))));
    }
    private ResponseEntity<?> error(Object id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0"); body.put("id", id); body.put("error", Map.of("code", code, "message", message));
        return ResponseEntity.ok(body);
    }
}
