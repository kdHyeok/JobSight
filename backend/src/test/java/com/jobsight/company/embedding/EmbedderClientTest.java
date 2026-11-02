package com.jobsight.company.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 HTTP 로 EmbedderClient 의 실패 변환을 고정한다(설계 7절 5단계 "죽으면·느리면"). 어떤 실패도 Unavailable 밖으로 새면
 * 검색 전체가 500 이 된다. 거부(HTTP 비 200·개수 불일치)는 Rejected — 스위퍼가 그 행만 건너뛰는 근거다.
 */
class EmbedderClientTest {
    private HttpServer server;

    private EmbedderClient serve(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new EmbedderClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesVectorsAndModel() throws IOException {
        EmbedderClient client = serve(200, "{\"model\":\"m\",\"dim\":2,\"vectors\":[[0.6,0.8]],\"truncated\":[true]}", 0);
        EmbedderClient.Embedded embedded = client.embed(EmbedderClient.Mode.query, List.of("a"), Duration.ofSeconds(2));
        assertThat(embedded.model()).isEqualTo("m");
        assertThat(embedded.vectors().get(0)).containsExactly(0.6f, 0.8f);
        assertThat(embedded.truncated()).containsExactly(true);
        assertThat(client.model()).isEqualTo("m");
    }

    @Test
    void httpErrorIsRejected() throws IOException {
        EmbedderClient client = serve(500, "{\"error\":\"INTERNAL\"}", 0);
        assertThatThrownBy(() -> client.embed(EmbedderClient.Mode.passage, List.of("a"), Duration.ofSeconds(2)))
                .isInstanceOf(EmbedderClient.Rejected.class).hasMessageContaining("500");
    }

    @Test
    void countMismatchIsRejected() throws IOException {
        EmbedderClient client = serve(200, "{\"model\":\"m\",\"vectors\":[[1.0]],\"truncated\":[false]}", 0);
        assertThatThrownBy(() -> client.embed(EmbedderClient.Mode.passage, List.of("a", "b"), Duration.ofSeconds(2)))
                .isInstanceOf(EmbedderClient.Rejected.class);
    }

    @Test
    void blankHealthModelIsRejected() throws IOException {
        EmbedderClient client = serve(200, "{\"status\":\"UP\"}", 0);
        assertThatThrownBy(client::model).isInstanceOf(EmbedderClient.Rejected.class);
    }

    @Test
    void nonJsonBodyIsUnavailable() throws IOException {
        EmbedderClient client = serve(200, "<html>", 0);
        assertThatThrownBy(() -> client.embed(EmbedderClient.Mode.query, List.of("a"), Duration.ofSeconds(2)))
                .isInstanceOf(EmbedderClient.Unavailable.class);
    }

    /** 질의 상한(2초)을 넘기면 기다리지 않고 Unavailable — 검색은 degraded:["dense"] 로 계속한다. 거부가 아니라 장애로 읽는다. */
    @Test
    void slowEmbedderTimesOutAsUnavailable() throws IOException {
        EmbedderClient client = serve(200, "{\"model\":\"m\",\"vectors\":[[1.0]],\"truncated\":[false]}", 3000);
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.embed(EmbedderClient.Mode.query, List.of("a"), DenseIndex.QUERY_TIMEOUT))
                .isInstanceOf(EmbedderClient.Unavailable.class).isNotInstanceOf(EmbedderClient.Rejected.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2900));
    }

    @Test
    void connectionRefusedIsUnavailable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EmbedderClient client = new EmbedderClient("http://127.0.0.1:" + port, JsonMapper.builder().build());
        assertThatThrownBy(client::model).isInstanceOf(EmbedderClient.Unavailable.class)
                .isNotInstanceOf(EmbedderClient.Rejected.class);
    }

    @Test
    void unconfiguredIsUnavailable() {
        EmbedderClient client = new EmbedderClient("", JsonMapper.builder().build());
        assertThat(client.configured()).isFalse();
        assertThatThrownBy(client::model).isInstanceOf(EmbedderClient.Unavailable.class);
    }
}
