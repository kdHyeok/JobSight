package com.jobsight.company.mcp;

import com.jobsight.company.common.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Stateless Streamable HTTP: JSON POST responses, no server-initiated SSE/session. */
@RestController
public class McpController {
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
                    "name", "JobSight 사용 지침", "description", "기업·공고·직무·참고·뉴스/유튜브 도구와 안전한 수정 규칙", "mimeType", "text/markdown"))));
            case "resources/read":
                if (!PluginGuideController.SKILL_URI.equals(params.get("uri"))) return error(id, -32002, "Resource not found");
                return result(id, Map.of("contents", List.of(Map.of("uri", PluginGuideController.SKILL_URI,
                        "mimeType", "text/markdown", "text", PluginGuideController.skillText()))));
            case "tools/list": return result(id, Map.of("tools", tools.list(principal)));
            case "tools/call":
                try {
                    if (!(params.get("name") instanceof String name)) throw new IllegalArgumentException("Missing tool name");
                    Object data = tools.call(name, object(params.getOrDefault("arguments", Map.of())), principal);
                    return result(id, Map.of("content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(data))),
                            "structuredContent", Map.of("data", data), "isError", false));
                } catch (IllegalArgumentException e) {
                    return toolError(id, "INVALID_ARGUMENT", e.getMessage());
                } catch (tools.jackson.core.JacksonException e) {
                    return toolError(id, "INVALID_ARGUMENT", "입력 형식이나 enum 값이 올바르지 않습니다.");
                } catch (RuntimeException e) {
                    // 도메인 예외와 500(로그 포함)은 REST 와 같은 변환을 쓴다 — 코드가 갈리지 않게.
                    ApiError error = ApiErrors.translate(e);
                    return toolError(id, error.code(), error.message());
                }
            default: return error(id, -32601, "Method not found");
        }
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
