package com.jobsight.company.config;

import com.jobsight.company.common.ApiPaths;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthConfigTest {
    @Test void callbackComesFromConfiguredOriginNotForwardedHeaders() {
        for (String base : new String[]{"https://job.donyk.link", "http://127.0.0.1:8088"}) {
            var clients = new GoogleOAuthConfig().clientRegistrationRepository("test-id", "test-secret", base);
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
            var request = new MockHttpServletRequest("GET", ApiPaths.OAUTH_AUTHORIZATION);
            request.setServletPath(ApiPaths.OAUTH_AUTHORIZATION);
            request.setServerName("untrusted.example");
            request.addHeader("Host", "untrusted.example");
            request.addHeader("X-Forwarded-Host", "another.example");
            request.addHeader("X-Forwarded-Proto", "http");
            var authorization = resolver.resolve(request);
            assertThat(authorization).isNotNull();
            assertThat(authorization.getRedirectUri()).isEqualTo(base + ApiPaths.OAUTH_CALLBACK);
        }
    }
}
