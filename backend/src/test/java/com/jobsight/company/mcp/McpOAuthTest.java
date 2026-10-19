package com.jobsight.company.mcp;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(McpOAuthTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "app.public-base-url=http://127.0.0.1:8088")
class McpOAuthTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({McpOAuthConfig.class, McpController.class, PluginGuideController.class})
    static class Config {
        @Bean AppUserRepository users() { return mock(AppUserRepository.class); }
        @Bean McpTools tools() { return mock(McpTools.class); }
        @Bean ObjectMapper mapper() { return JsonMapper.builder().findAndAddModules().build(); }
    }
    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired RegisteredClientRepository clients;
    @Autowired OAuth2AuthorizationConsentService consents;
    @Autowired McpTools tools;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    AppUser account;
    static final String RESOURCE = "http://127.0.0.1:8088/mcp";
    static final String CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";
    @BeforeEach void setUp() {
        reset(users, tools);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account = AppUser.signUpWithGoogle("test@example.com", "test-sub", "Test", UserStatus.ACTIVE);
        when(users.findById(account.getId())).thenReturn(Optional.of(account));
        when(tools.list(any())).thenReturn(List.of());
    }
    @Test void metadataAndCookieIsolationAndOrigins() throws Exception {
        mvc.perform(get(ApiPaths.MCP_METADATA)).andExpect(status().isOk()).andExpect(jsonPath("$.resource").value(RESOURCE));
        mvc.perform(get(ApiPaths.OAUTH_METADATA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://127.0.0.1:8088"))
                .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
                .andExpect(jsonPath("$.grant_types_supported").value(org.hamcrest.Matchers.hasItem("refresh_token")))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
        mvc.perform(post(ApiPaths.MCP).session(session()).contentType("application/json")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(ApiPaths.MCP).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
    }
    @Test void pkceCodeExchangeReplayAudienceAndSuspension() throws Exception {
        // Consent persistence is pre-established here; the separate test checks first-use consent UI.
        var client = clients.findByClientId("jobsight-plugin");
        consents.save(OAuth2AuthorizationConsent.withId(client.getId(), account.getId().toString())
                .scope(McpOAuthConfig.READ).build());
        String verifier = "a".repeat(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var response = mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(session())
                .queryParam("response_type", "code").queryParam("client_id", "jobsight-plugin")
                .queryParam("redirect_uri", CALLBACK).queryParam("scope", McpOAuthConfig.READ)
                .queryParam("state", "test-state").queryParam("resource", RESOURCE)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
                .andDo(result -> { if (result.getResponse().getStatus() >= 400) throw new AssertionError(result.getResponse().getErrorMessage() + " " + result.getResponse().getContentAsString()); })
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        var query = UriComponentsBuilder.fromUri(URI.create(response.getRedirectedUrl())).build().getQueryParams();
        assertThat(query.getFirst("state")).isEqualTo("test-state");
        assertThat(query.getFirst("code")).isNotBlank();
        String code = query.getFirst("code");
        var tokenResponse = mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "authorization_code")
                .param("client_id", "jobsight-plugin").param("redirect_uri", CALLBACK).param("code", code)
                .param("code_verifier", verifier).param("resource", RESOURCE))
                .andExpect(status().isOk()).andReturn().getResponse();
        var issued = mapper.readTree(tokenResponse.getContentAsString());
        String token = issued.get("access_token").asText();
        String refresh = issued.get("refresh_token").asText();
        mvc.perform(post(ApiPaths.MCP).header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tools").isArray());
        var refreshed = mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "refresh_token")
                .param("client_id", "jobsight-plugin").param("refresh_token", refresh).param("resource", RESOURCE))
                .andExpect(status().isOk()).andReturn().getResponse();
        var rotated = mapper.readTree(refreshed.getContentAsString());
        assertThat(rotated.get("refresh_token").asText()).isNotEqualTo(refresh);
        mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "refresh_token")
                .param("client_id", "jobsight-plugin").param("refresh_token", refresh).param("resource", RESOURCE))
                .andExpect(status().isBadRequest());
        account.changeStatus(UserStatus.SUSPENDED);
        mvc.perform(get(ApiPaths.MCP).header("Authorization", "Bearer " + rotated.get("access_token").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "authorization_code")
                .param("client_id", "jobsight-plugin").param("redirect_uri", CALLBACK).param("code", code)
                .param("code_verifier", verifier).param("resource", RESOURCE)).andExpect(status().isBadRequest());
    }

    @Test void packagedGuideDownloadAndMcpInstructionsUseTheSameSkill() throws Exception {
        mvc.perform(get(ApiPaths.PLUGIN_CONFIG)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpUrl").value(RESOURCE));
        var skill = mvc.perform(get(ApiPaths.PLUGIN_SKILL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(skill).contains("position_replace_references", "company_content_create",
                "resume_create", "resume_update", "resume_delete",
                "self_intro_create", "self_intro_update", "self_intro_delete");
        var download = mvc.perform(get(ApiPaths.PLUGIN_DOWNLOAD)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var entries = new HashMap<String, String>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(download))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(entries).hasSize(3);
        assertThat(entries.get("skills/jobsight/SKILL.md")).isEqualTo(skill);
        assertThat(mapper.readTree(entries.get(".mcp.json")).at("/mcpServers/jobsight/url").asText()).isEqualTo(RESOURCE);
        var controller = new McpController(tools, context.getBean(McpOAuthConfig.class), mapper);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new McpPrincipal(account.getId(), Set.of(McpOAuthConfig.READ)), null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var init = controller.rpc(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize"), null);
            assertThat(mapper.valueToTree(init.getBody()).at("/result/instructions").asText()).isEqualTo(skill);
            var resource = controller.rpc(Map.of("jsonrpc", "2.0", "id", 2, "method", "resources/read",
                    "params", Map.of("uri", PluginGuideController.SKILL_URI)), null);
            assertThat(mapper.valueToTree(resource.getBody()).at("/result/contents/0/text").asText()).isEqualTo(skill);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void cimdClientCompletesSpringPkceFlow() throws Exception {
        cimdPkceFlow(McpCimdTest.CLIENT, McpCimdTest.CALLBACK, McpCimdTest.document());
    }
    @Test void codexClientCompletesPkceFlowWithEphemeralLoopbackPort() throws Exception {
        cimdPkceFlow(McpCimdTest.CODEX, "http://127.0.0.1:62861/callback/dIUUlcNtfXHU", McpCimdTest.codexDocument());
    }
    private void cimdPkceFlow(String clientId, String callback, Map<String, Object> document) throws Exception {
        var resolver = new ChatGptClients(McpCimdTest.manual(), url -> document, java.time.Clock.systemUTC());
        var client = resolver.findByClientId(clientId);
        clients.save(client); // verified remote document fixture; production resolves via the same repository contract
        consents.save(OAuth2AuthorizationConsent.withId(client.getId(), account.getId().toString())
                .scope(McpOAuthConfig.READ).build());
        String verifier = "b".repeat(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var response = mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(session())
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", callback).queryParam("scope", McpOAuthConfig.READ)
                .queryParam("state", "cimd-test").queryParam("resource", RESOURCE)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        var query = UriComponentsBuilder.fromUri(URI.create(response.getRedirectedUrl())).build().getQueryParams();
        assertThat(query.getFirst("state")).isEqualTo("cimd-test");
        var exchanged = mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "authorization_code")
                .param("client_id", clientId).param("redirect_uri", callback)
                .param("code", query.getFirst("code")).param("code_verifier", verifier).param("resource", RESOURCE))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = mapper.readTree(exchanged.getContentAsString()).get("access_token").asText();
        mvc.perform(post(ApiPaths.MCP).header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.capabilities.resources").exists());
    }
    @Test void consentAndInvalidPkceResourceRedirect() throws Exception {
        mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(session())
                .queryParam("response_type", "code").queryParam("client_id", "jobsight-plugin")
                .queryParam("redirect_uri", CALLBACK).queryParam("scope", McpOAuthConfig.READ)
                .queryParam("state", "first-use").queryParam("resource", RESOURCE)
                .queryParam("code_challenge", "a".repeat(43)).queryParam("code_challenge_method", "S256"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("consent")));
        mvc.perform(get(ApiPaths.MCP_AUTHORIZE).param("resource", "https://wrong.example/mcp"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ApiPaths.MCP_AUTHORIZE).param("resource", RESOURCE).param("code_challenge_method", "plain"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(session()).queryParam("resource", RESOURCE)
                .queryParam("code_challenge_method", "S256").queryParam("code_challenge", "a".repeat(43))
                .queryParam("response_type", "code").queryParam("client_id", "jobsight-plugin")
                .queryParam("redirect_uri", "https://evil.example/callback").queryParam("scope", McpOAuthConfig.READ))
                .andExpect(status().isBadRequest());
    }
    @Test void wrongAudienceAndExpiredTokensRejected() throws Exception {
        for (boolean expired : List.of(false, true)) {
            var client = clients.findByClientId("jobsight-plugin");
            var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, UUID.randomUUID().toString(),
                    Instant.now().minusSeconds(120), Instant.now().plusSeconds(expired ? -60 : 3600), Set.of(McpOAuthConfig.READ));
            authorizations.save(OAuth2Authorization.withRegisteredClient(client).principalName(account.getId().toString())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of(McpOAuthConfig.READ))
                    .token(access, metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                            Map.of("aud", List.of(expired ? RESOURCE : "https://wrong.example/mcp")))).build());
            mvc.perform(get(ApiPaths.MCP).header("Authorization", "Bearer " + access.getTokenValue())).andExpect(status().isUnauthorized());
        }
    }
    MockHttpSession session() {
        var session = new MockHttpSession();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(account.getId().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }
}
