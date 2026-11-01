package com.jobsight.company.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class McpCimdTest {
    static final String CLIENT = "https://chatgpt.com/oauth/SXflabHme2Pl/client.json";
    static final String CALLBACK = "https://chatgpt.com/connector/oauth/SXflabHme2Pl";
    static final String CODEX = "https://chatgpt.com/oauth/codex/dIUUlcNtfXHU/client.json";
    static Map<String, Object> codexDocument() {
        var doc = document();
        doc.put("client_id", CODEX);
        doc.put("application_type", "native");
        doc.put("redirect_uris", List.of("http://127.0.0.1/callback/dIUUlcNtfXHU", "http://localhost/callback/dIUUlcNtfXHU"));
        return doc;
    }
    @Test void resolvesCodexAndRejectsUntrustedCallbacks() {
        var doc = codexDocument();
        var client = new ChatGptClients(manual(), url -> doc, Clock.systemUTC()).findByClientId(CODEX);
        assertThat(client).isNotNull();
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        for (String callback : List.of("http://evil.test/callback/dIUUlcNtfXHU", "http://127.0.0.1/callback/other",
                "http://127.0.0.1/callback/dIUUlcNtfXHU?next=evil", "http://127.0.0.1:1234/callback/dIUUlcNtfXHU")) {
            doc.put("redirect_uris", List.of(callback, "http://localhost/callback/dIUUlcNtfXHU"));
            assertThat(new ChatGptClients(manual(), url -> doc, Clock.systemUTC()).findByClientId(CODEX)).isNull();
        }
        doc.putAll(codexDocument()); doc.put("application_type", "web");
        assertThat(new ChatGptClients(manual(), url -> doc, Clock.systemUTC()).findByClientId(CODEX)).isNull();
    }
    static RegisteredClient manual() {
        return RegisteredClient.withId("manual").clientId("manual")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://example.com/cb").build();
    }
    static Map<String, Object> document() {
        return new HashMap<>(Map.of("client_id", CLIENT, "redirect_uris", List.of(CALLBACK),
                "token_endpoint_auth_methods_supported", List.of("none", "private_key_jwt"),
                "token_endpoint_auth_method", "private_key_jwt", "grant_types", List.of("authorization_code", "refresh_token")));
    }
    @Test void resolvesVerifiedCimdWithPkceAndCachesWithoutChangingIdentity() {
        var calls = new AtomicInteger();
        var repository = new ChatGptClients(manual(), url -> { calls.incrementAndGet(); return document(); }, Clock.systemUTC());
        var client = repository.findByClientId(CLIENT);
        assertThat(client).isNotNull();
        assertThat(client.getId()).isEqualTo(CLIENT);
        assertThat(client.getRedirectUris()).containsExactly(CALLBACK);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(90));
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(repository.findById(CLIENT)).isSameAs(client);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(repository.findByClientId("manual")).isNotNull();
    }
    @Test void refusesSsrfAndRedirectUriSubstitution() {
        var calls = new AtomicInteger();
        var repository = new ChatGptClients(manual(), url -> { calls.incrementAndGet(); return document(); }, Clock.systemUTC());
        for (String id : List.of("http://chatgpt.com/oauth/client.json", "https://127.0.0.1/oauth/client.json",
                "https://chatgpt.com.evil.test/oauth/client.json", "https://chatgpt.com@evil.test/oauth/client.json",
                "https://chatgpt.com/oauth/client.json?x=1", "https://chatgpt.com/oauth/../client.json"))
            assertThat(repository.findByClientId(id)).isNull();
        assertThat(calls.get()).isZero();
        var doc = document(); doc.put("redirect_uris", List.of("https://evil.test/callback"));
        assertThat(new ChatGptClients(manual(), url -> doc, Clock.systemUTC()).findByClientId(CLIENT)).isNull();
        doc.put("redirect_uris", List.of(CALLBACK)); doc.put("client_id", CLIENT + "wrong");
        assertThat(new ChatGptClients(manual(), url -> doc, Clock.systemUTC()).findByClientId(CLIENT)).isNull();
    }
    @Test void refusesUnsupportedAuthAndFailedFetch() {
        var doc = document(); doc.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
        assertThat(new ChatGptClients(manual(), url -> doc, Clock.systemUTC()).findByClientId(CLIENT)).isNull();
        assertThat(new ChatGptClients(manual(), url -> { throw new IllegalArgumentException(); }, Clock.systemUTC())
                .findByClientId(CLIENT)).isNull();
    }
}
