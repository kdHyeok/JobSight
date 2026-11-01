package com.jobsight.company.mcp;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.auth.AppPrincipal;
import com.jobsight.company.user.AppUserRepository;
import com.jobsight.company.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Configuration
public class McpOAuthConfig {
    public static final String READ = "jobsight.read", WRITE = "jobsight.write", ADMIN = "jobsight.admin";
    public static final List<String> SCOPES = List.of(READ, WRITE, ADMIN);
    private final String base;

    public McpOAuthConfig(@Value("${app.public-base-url}") String base) {
        URI uri = URI.create(base);
        if (uri.getHost() == null || uri.getRawQuery() != null || uri.getFragment() != null
                || uri.getUserInfo() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())))) {
            throw new IllegalArgumentException("PUBLIC_BASE_URL must be an HTTPS origin (HTTP loopback for development)");
        }
        this.base = base.replaceAll("/$", "");
    }

    public String base() { return base; }
    public String resource() { return base + ApiPaths.MCP; }
    /**
     * 401 의 scope 힌트. 클라이언트는 이 값을 authorize 요청의 scope 로 그대로 쓴다 —
     * read 만 적으면 일반 사용자가 쓰기 도구를 영영 못 받는다(실제로 그랬다).
     * admin 은 계정 역할까지 필요해 기본 힌트에서 뺀다. 필요하면 리소스 메타데이터의
     * scopes_supported 를 보고 따로 요청한다.
     */
    public String challenge() {
        return "Bearer resource_metadata=\"" + base + ApiPaths.MCP_METADATA
                + "\", scope=\"" + READ + " " + WRITE + "\"";
    }

    @Bean
    RegisteredClientRepository mcpClients(
            @Value("${app.mcp.redirect-uris:https://chatgpt.com/connector_platform_oauth_redirect}") String redirects,
            tools.jackson.databind.ObjectMapper mapper) {
        var client = RegisteredClient.withId("jobsight-plugin")
                .clientId("jobsight-plugin").clientName("JobSight plugin")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(tokenSettings());
        SCOPES.forEach(client::scope);
        for (String redirect : redirects.split(",")) {
            URI uri = URI.create(redirect.trim());
            if (uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())))) {
                throw new IllegalArgumentException("MCP redirect URI must be HTTPS or HTTP loopback");
            }
            client.redirectUri(uri.toString());
        }
        return new ChatGptClients(client.build(), mapper);
    }

    @Bean
    OAuth2AuthorizationService mcpAuthorizations(ObjectProvider<JdbcTemplate> jdbc,
                                                  RegisteredClientRepository clients) {
        var template = jdbc.getIfAvailable();
        return template == null ? new InMemoryOAuth2AuthorizationService()
                : new JdbcOAuth2AuthorizationService(template, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService mcpConsents(ObjectProvider<JdbcTemplate> jdbc,
                                                   RegisteredClientRepository clients) {
        var template = jdbc.getIfAvailable();
        return template == null ? new InMemoryOAuth2AuthorizationConsentService()
                : new JdbcOAuth2AuthorizationConsentService(template, clients);
    }

    static TokenSettings tokenSettings() {
        return TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(Duration.ofHours(1)).refreshTokenTimeToLive(Duration.ofDays(90))
                .reuseRefreshTokens(false).authorizationCodeTimeToLive(Duration.ofMinutes(2)).build();
    }

    @Bean
    AuthorizationServerSettings mcpAuthorizationSettings() {
        return AuthorizationServerSettings.builder().issuer(base)
                .authorizationEndpoint(ApiPaths.MCP_AUTHORIZE).tokenEndpoint(ApiPaths.MCP_TOKEN)
                .tokenRevocationEndpoint(ApiPaths.MCP_REVOKE).tokenIntrospectionEndpoint(ApiPaths.MCP_INTROSPECT).build();
    }

    @Bean
    OAuth2TokenGenerator<?> mcpTokenGenerator() {
        var access = new OAuth2AccessTokenGenerator();
        // JDBC authorization metadata is polymorphically deserialized through Spring's Jackson allow-list.
        // List.of() produces java.util.ImmutableCollections$List12, which that allow-list rejects.
        access.setAccessTokenCustomizer(context -> context.getClaims().audience(new ArrayList<>(List.of(resource()))));
        var keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
        OAuth2TokenGenerator<OAuth2RefreshToken> refresh = context -> {
            if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
            var issuedAt = Instant.now();
            return new OAuth2RefreshToken(keys.generateKey(), issuedAt,
                    issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive()));
        };
        return new DelegatingOAuth2TokenGenerator(access, refresh);
    }

    @Bean @Order(1)
    SecurityFilterChain mcpAuthorizationChain(HttpSecurity http, RegisteredClientRepository clients,
                                               OAuth2AuthorizationService authorizations) throws Exception {
        var server = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(server.getEndpointsMatcher())
                .with(server, config -> {
                    config.clientAuthentication(auth -> auth
                            .authenticationConverters(list -> list.add(0, new PublicRefreshConverter()))
                            .authenticationProviders(list -> list.add(0, new PublicRefreshProvider(clients))));
                    config.authorizationServerMetadataEndpoint(metadata ->
                            metadata.authorizationServerMetadataCustomizer(builder -> builder
                                .claim("client_id_metadata_document_supported", true)
                                .claim("grant_types_supported", List.of("authorization_code", "refresh_token"))
                                // Spring 은 scopes_supported 를 기본으로 넣지 않는다.
                                // 힌트 대신 메타데이터를 읽는 클라이언트도 write 를 볼 수 있어야 한다.
                                .claim("scopes_supported", SCOPES)
                                .tokenEndpointAuthenticationMethods(methods -> { methods.clear(); methods.add("none"); })));
                })
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, error) -> response.sendRedirect(ApiPaths.OAUTH_AUTHORIZATION)))
                .addFilterAfter(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                               FilterChain chain) throws ServletException, IOException {
                        String path = request.getRequestURI();
                        // JDBC authorization rows must not contain the custom Google OIDC principal. Spring's
                        // default Jackson allow-list cannot deserialize AppOidcUser on the consent POST.
                        // Keep only the stable application user id and authorities for this authorization request.
                        Authentication original = SecurityContextHolder.getContext().getAuthentication();
                        boolean sanitized = path.equals(ApiPaths.MCP_AUTHORIZE) && original != null
                                && original.getPrincipal() instanceof AppPrincipal;
                        try {
                            if (sanitized) {
                                var principal = (AppPrincipal) original.getPrincipal();
                                SecurityContextHolder.getContext().setAuthentication(
                                        new UsernamePasswordAuthenticationToken(principal.appUserId().toString(), null,
                                                original.getAuthorities()));
                            }
                            // Consent POST uses Spring's stored request, not an untrusted resource parameter.
                            boolean initial = path.equals(ApiPaths.MCP_AUTHORIZE) && "GET".equals(request.getMethod());
                            if (initial || path.equals(ApiPaths.MCP_TOKEN)) {
                                String[] resources = request.getParameterValues("resource");
                                if (resources == null || resources.length != 1 || !resource().equals(resources[0])) {
                                    response.setStatus(400); response.setContentType("application/json");
                                    response.getWriter().write("{\"error\":\"invalid_target\"}"); return;
                                }
                                if (initial && !"S256".equals(request.getParameter("code_challenge_method"))) {
                                    response.setStatus(400); response.setContentType("application/json");
                                    response.getWriter().write("{\"error\":\"invalid_request\"}"); return;
                                }
                                if (path.equals(ApiPaths.MCP_TOKEN) && !grantMatchesResource(request, authorizations)) {
                                    response.setStatus(400); response.setContentType("application/json");
                                    response.getWriter().write("{\"error\":\"invalid_grant\"}"); return;
                                }
                            }
                            chain.doFilter(request, response);
                        } finally {
                            if (sanitized) SecurityContextHolder.getContext().setAuthentication(original);
                        }
                    }
                }, SecurityContextHolderFilter.class);
        return http.build();
    }

    // Check the stored grant before Spring can mint a token for the new deployment origin.
    // Client authentication, PKCE, expiration, and replay checks still belong to Spring.
    private boolean grantMatchesResource(HttpServletRequest request, OAuth2AuthorizationService authorizations) {
        String[] grants = request.getParameterValues(OAuth2ParameterNames.GRANT_TYPE);
        if (grants == null || grants.length != 1) return true;
        boolean refresh = AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grants[0]);
        boolean code = AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(grants[0]);
        if (!refresh && !code) return true;
        String[] values = request.getParameterValues(refresh ? OAuth2ParameterNames.REFRESH_TOKEN : OAuth2ParameterNames.CODE);
        if (values == null || values.length != 1 || values[0].isBlank()) return false;
        var stored = authorizations.findByToken(values[0], refresh ? OAuth2TokenType.REFRESH_TOKEN
                : new OAuth2TokenType(OAuth2ParameterNames.CODE));
        if (stored == null) return false;
        if (refresh) {
            var token = stored.getAccessToken();
            var claims = token == null ? null : token.getClaims();
            return claims != null && claims.get("aud") instanceof Collection<?> audience
                    && audience.size() == 1 && audience.contains(resource());
        }
        Object original = stored.getAttribute(OAuth2AuthorizationRequest.class.getName());
        return original instanceof OAuth2AuthorizationRequest authorizationRequest
                && resource().equals(authorizationRequest.getAdditionalParameters().get("resource"));
    }

    private static final class PublicRefreshConverter implements AuthenticationConverter {
        @Override public Authentication convert(HttpServletRequest request) {
            String[] grants = request.getParameterValues(OAuth2ParameterNames.GRANT_TYPE);
            String[] ids = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
            if (grants == null || grants.length != 1 || !"refresh_token".equals(grants[0])
                    || ids == null || ids.length != 1 || ids[0].isBlank()
                    || request.getHeader("Authorization") != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null) return null;
            return new OAuth2ClientAuthenticationToken(ids[0], ClientAuthenticationMethod.NONE, null,
                    Map.of(OAuth2ParameterNames.GRANT_TYPE, grants[0]));
        }
    }

    private static final class PublicRefreshProvider implements AuthenticationProvider {
        private final RegisteredClientRepository clients;
        private PublicRefreshProvider(RegisteredClientRepository clients) { this.clients = clients; }
        @Override public Authentication authenticate(Authentication authentication) {
            var token = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                    || !"refresh_token".equals(token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE))) return null;
            var client = clients.findByClientId(token.getPrincipal().toString());
            if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                    || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN))
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        }
        @Override public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    @Bean @Order(2)
    SecurityFilterChain mcpResourceChain(HttpSecurity http, OAuth2AuthorizationService authorizations,
                                        AppUserRepository users) throws Exception {
        http.securityMatcher(ApiPaths.MCP, ApiPaths.MCP_METADATA, ApiPaths.MCP_METADATA + ApiPaths.MCP,
                        ApiPaths.PLUGIN_CONFIG, ApiPaths.PLUGIN_SKILL, ApiPaths.PLUGIN_DOWNLOAD)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(ApiPaths.MCP_METADATA,
                        ApiPaths.MCP_METADATA + ApiPaths.MCP, ApiPaths.PLUGIN_CONFIG,
                        ApiPaths.PLUGIN_SKILL, ApiPaths.PLUGIN_DOWNLOAD).permitAll().anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> {
                    response.setHeader("WWW-Authenticate", challenge()); response.setStatus(401);
                }))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                               FilterChain chain) throws ServletException, IOException {
                        String origin = request.getHeader("Origin");
                        if (origin != null && !origin.equals(base)) { response.setStatus(403); return; }
                        String header = request.getHeader("Authorization");
                        if (header != null && header.startsWith("Bearer ")) {
                            var authorization = authorizations.findByToken(header.substring(7), OAuth2TokenType.ACCESS_TOKEN);
                            if (authorization != null && authorization.getAccessToken() != null
                                    && authorization.getAccessToken().isActive()) {
                                var claims = authorization.getAccessToken().getClaims();
                                if (claims != null && claims.get("aud") instanceof Collection<?> audience
                                        && audience.contains(resource())) {
                                    var user = users.findById(UUID.fromString(authorization.getPrincipalName())).orElse(null);
                                    if (user != null && user.getStatus() == UserStatus.ACTIVE) {
                                        var principal = new McpPrincipal(user.getId(), authorization.getAuthorizedScopes());
                                        SecurityContextHolder.getContext().setAuthentication(
                                                new UsernamePasswordAuthenticationToken(principal, null,
                                                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
                                    }
                                }
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
