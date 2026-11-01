package com.jobsight.company.mcp;

import com.jobsight.company.auth.AppOidcUser;
import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.user.AppUser;
import com.jobsight.company.user.AppUserRepository;
import com.jobsight.company.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = "app.public-base-url=https://job.donyk.link")
class McpOAuthJdbcIntegrationTest {
    private static final String RESOURCE = "https://job.donyk.link/mcp";
    private static final String CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired OAuth2AuthorizationConsentService consents;
    @Autowired RegisteredClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private MockMvc mvc;
    private AppUser account;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String unique = UUID.randomUUID().toString();
        account = users.save(AppUser.signUpWithGoogle(
                "mcp-jdbc-" + unique + "@example.com", "mcp-jdbc-" + unique, "MCP JDBC", UserStatus.ACTIVE));
    }

    @Test
    void googlePrincipalSurvivesJdbcAuthorizationRoundTrip() throws Exception {
        String state = "jdbc-app-oidc-user";
        MockHttpSession session = googleSession();
        assertThat(authorizations).isInstanceOf(JdbcOAuth2AuthorizationService.class);

        mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "jobsight-plugin")
                        .queryParam("redirect_uri", CALLBACK)
                        .queryParam("scope", McpOAuthConfig.READ)
                        .queryParam("state", state)
                        .queryParam("resource", RESOURCE)
                        .queryParam("code_challenge", "a".repeat(43))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("consent")));

        String authorizationId = jdbc.queryForObject(
                "select id from oauth2_authorization where principal_name = ?", String.class,
                account.getId().toString());
        var restored = authorizations.findById(authorizationId);
        assertThat(restored).isNotNull();
        assertThat(restored.getPrincipalName()).isEqualTo(account.getId().toString());
        var sessionContext = (org.springframework.security.core.context.SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(sessionContext.getAuthentication()).isInstanceOf(OAuth2AuthenticationToken.class);
        assertThat(sessionContext.getAuthentication().getPrincipal()).isInstanceOf(AppOidcUser.class);
    }

    @Test
    void issuedAccessAndRefreshTokensSurviveJdbcRoundTrip() throws Exception {
        var client = clients.findByClientId("jobsight-plugin");
        consents.save(OAuth2AuthorizationConsent.withId(client.getId(), account.getId().toString())
                .scope(McpOAuthConfig.READ).build());
        String verifier = "b".repeat(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        var authorize = mvc.perform(get(ApiPaths.MCP_AUTHORIZE).session(googleSession())
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "jobsight-plugin")
                        .queryParam("redirect_uri", CALLBACK)
                        .queryParam("scope", McpOAuthConfig.READ)
                        .queryParam("state", "jdbc-token-round-trip")
                        .queryParam("resource", RESOURCE)
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        String code = UriComponentsBuilder.fromUri(URI.create(authorize.getRedirectedUrl()))
                .build().getQueryParams().getFirst("code");

        var tokenResponse = mvc.perform(post(ApiPaths.MCP_TOKEN)
                        .param("grant_type", AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
                        .param("client_id", "jobsight-plugin")
                        .param("redirect_uri", CALLBACK)
                        .param("code", code)
                        .param("code_verifier", verifier)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        var tokenJson = mapper.readTree(tokenResponse.getContentAsString());
        String accessToken = tokenJson.get("access_token").asText();
        String refreshToken = tokenJson.get("refresh_token").asText();

        mvc.perform(post(ApiPaths.MCP)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").exists());
        mvc.perform(post(ApiPaths.MCP)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").isArray())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'self_intro_list')].title")
                        .value(org.hamcrest.Matchers.hasItem("자기소개 문항 검색")));

        var refreshResponse = mvc.perform(post(ApiPaths.MCP_TOKEN)
                        .param("grant_type", AuthorizationGrantType.REFRESH_TOKEN.getValue())
                        .param("client_id", "jobsight-plugin")
                        .param("refresh_token", refreshToken)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn().getResponse();
        String rotatedAccessToken = mapper.readTree(refreshResponse.getContentAsString()).get("access_token").asText();
        mvc.perform(post(ApiPaths.MCP)
                        .header("Authorization", "Bearer " + rotatedAccessToken)
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'self_intro_list')].title")
                        .value(org.hamcrest.Matchers.hasItem("자기소개 문항 검색")));
    }

    private MockHttpSession googleSession() {
        Instant now = Instant.now();
        var idToken = new OidcIdToken("id-token", now, now.plusSeconds(300),
                Map.of("sub", account.getGoogleSub(), "email", account.getEmail()));
        var oidcUser = new AppOidcUser(new DefaultOidcUser(
                java.util.List.of(), idToken, "sub"), account);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }
}
