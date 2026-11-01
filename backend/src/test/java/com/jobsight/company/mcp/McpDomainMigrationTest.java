package com.jobsight.company.mcp;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.user.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(McpOAuthTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "app.public-base-url=https://job.donyk.link")
class McpDomainMigrationTest {
    private static final String BASE = "https://job.donyk.link";
    private static final String RESOURCE = BASE + "/mcp";
    private static final String OLD_RESOURCE = "https://job.donhse.duckdns.org/mcp";
    private static final String CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";
    private static final String VERIFIER = "v".repeat(64);
    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired McpTools tools;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired OAuth2AuthorizationConsentService consents;
    @Autowired RegisteredClientRepository clients;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;
    private AppUser account;

    @BeforeEach void setUp() {
        reset(users, tools);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account = AppUser.signUpWithGoogle("domain@example.com", UUID.randomUUID().toString(), "Domain", UserStatus.ACTIVE);
        when(users.findById(account.getId())).thenReturn(Optional.of(account));
        when(tools.list(any())).thenReturn(List.of());
        consents.save(OAuth2AuthorizationConsent.withId(clients.findByClientId("jobsight-plugin").getId(),
                account.getId().toString()).scope(McpOAuthConfig.READ).build());
    }

    @Test void metadataChallengeConfigAndArchiveUseOneCanonicalOrigin() throws Exception {
        for (String path : List.of(ApiPaths.MCP_METADATA, ApiPaths.MCP_METADATA + ApiPaths.MCP)) {
            mvc.perform(get(path).header("Host", "evil.example").header("X-Forwarded-Host", "evil.example"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.resource").value(RESOURCE))
                    .andExpect(jsonPath("$.authorization_servers[0]").value(BASE))
                    .andExpect(jsonPath("$.resource_documentation").value(BASE + "/plugin"));
        }
        mvc.perform(get(ApiPaths.OAUTH_METADATA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(BASE))
                .andExpect(jsonPath("$.authorization_endpoint").value(BASE + ApiPaths.MCP_AUTHORIZE))
                .andExpect(jsonPath("$.token_endpoint").value(BASE + ApiPaths.MCP_TOKEN));
        mvc.perform(post(ApiPaths.MCP).header("Origin", BASE).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", context.getBean(McpOAuthConfig.class).challenge()));
        mvc.perform(post(ApiPaths.MCP).header("Origin", "https://job.donhse.duckdns.org")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(ApiPaths.PLUGIN_CONFIG)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpUrl").value(RESOURCE))
                .andExpect(jsonPath("$.authorizationUrl").value(BASE + ApiPaths.MCP_AUTHORIZE))
                .andExpect(jsonPath("$.tokenUrl").value(BASE + ApiPaths.MCP_TOKEN));
        byte[] archive = mvc.perform(get(ApiPaths.PLUGIN_DOWNLOAD)).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            boolean found = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(".mcp.json")) {
                    assertThat(mapper.readTree(zip.readAllBytes()).at("/mcpServers/jobsight/url").asText()).isEqualTo(RESOURCE);
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test void newDomainCodeAndRefreshWorkButOldAccessAndRefreshNeverMigrate() throws Exception {
        String code = issueCode();
        var response = exchange(code, RESOURCE).andExpect(status().isOk()).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString());
        String access = json.get("access_token").asText();
        String refresh = json.get("refresh_token").asText();
        mvc.perform(post(ApiPaths.MCP).header("Authorization", "Bearer " + access).contentType("application/json")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk());
        refresh(refresh).andExpect(status().isOk());
        refresh(refresh).andExpect(status().isBadRequest());

        var client = clients.findByClientId("jobsight-plugin");
        var now = Instant.now();
        var oldAccess = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, UUID.randomUUID().toString(),
                now, now.plusSeconds(3600), Set.of(McpOAuthConfig.READ));
        var oldRefresh = new OAuth2RefreshToken(UUID.randomUUID().toString(), now, now.plusSeconds(3600));
        var old = OAuth2Authorization.withRegisteredClient(client).principalName(account.getId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of(McpOAuthConfig.READ))
                .token(oldAccess, meta -> meta.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, Map.of("aud", List.of(OLD_RESOURCE))))
                .refreshToken(oldRefresh).build();
        authorizations.save(old);
        mvc.perform(post(ApiPaths.MCP).header("Authorization", "Bearer " + oldAccess.getTokenValue())
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        refresh(oldRefresh.getTokenValue()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(authorizations.findById(old.getId()).getRefreshToken().getToken().getTokenValue())
                .isEqualTo(oldRefresh.getTokenValue());
    }

    @Test void oldOrMissingCodeResourceIsRejectedBeforeTokenIssuance() throws Exception {
        for (boolean missing : List.of(false, true)) {
            String code = issueCode();
            var stored = authorizations.findByToken(code, new OAuth2TokenType("code"));
            OAuth2AuthorizationRequest original = stored.getAttribute(OAuth2AuthorizationRequest.class.getName());
            var parameters = new HashMap<>(original.getAdditionalParameters());
            if (missing) parameters.remove("resource"); else parameters.put("resource", OLD_RESOURCE);
            var replaced = OAuth2AuthorizationRequest.from(original).additionalParameters(p -> {
                p.clear(); p.putAll(parameters);
            }).build();
            authorizations.save(OAuth2Authorization.from(stored)
                    .attribute(OAuth2AuthorizationRequest.class.getName(), replaced).build());
            exchange(code, RESOURCE).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
            assertThat(authorizations.findById(stored.getId()).getAccessToken()).isNull();
        }
        exchange(issueCode(), OLD_RESOURCE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test void missingMalformedOrMultipleRefreshAudiencesAreRejected() throws Exception {
        for (Object audience : List.of("missing", "wrong-type", List.of(), List.of(RESOURCE, OLD_RESOURCE))) {
            var now = Instant.now();
            var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, UUID.randomUUID().toString(),
                    now.minusSeconds(120), now.minusSeconds(60), Set.of(McpOAuthConfig.READ));
            var refresh = new OAuth2RefreshToken(UUID.randomUUID().toString(), now, now.plusSeconds(3600));
            authorizations.save(OAuth2Authorization.withRegisteredClient(clients.findByClientId("jobsight-plugin"))
                    .principalName(account.getId().toString()).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .token(token, meta -> meta.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                            audience.equals("missing") ? Map.of() : Map.of("aud", audience)))
                    .refreshToken(refresh).build());
            refresh(refresh.getTokenValue()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }
    }

    private String issueCode() throws Exception {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(account.getId().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var response = mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(session)
                .queryParam("response_type", "code").queryParam("client_id", "jobsight-plugin").queryParam("redirect_uri", CALLBACK)
                .queryParam("scope", McpOAuthConfig.READ).queryParam("state", UUID.randomUUID().toString()).queryParam("resource", RESOURCE)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        return UriComponentsBuilder.fromUri(URI.create(response.getRedirectedUrl())).build().getQueryParams().getFirst("code");
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String code, String resource) throws Exception {
        return mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "authorization_code")
                .param("client_id", "jobsight-plugin").param("redirect_uri", CALLBACK).param("code", code)
                .param("code_verifier", VERIFIER).param("resource", resource));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
        return mvc.perform(post(ApiPaths.MCP_TOKEN).param("grant_type", "refresh_token")
                .param("client_id", "jobsight-plugin").param("refresh_token", token).param("resource", RESOURCE));
    }
}
