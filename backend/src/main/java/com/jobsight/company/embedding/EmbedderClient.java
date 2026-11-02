package com.jobsight.company.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * embedder 컨테이너 호출(설계 4.4). 접두는 embedder 가 mode 로 붙인다 — 여기서는 mode 만 고른다.
 * 실패는 모두 {@link Unavailable} 로 던진다. 검색은 이것을 degraded:["dense"] 로, 스위퍼는 "다음 회차에"로 읽는다.
 * 그중 embedder 가 응답은 했지만 거부한 것(HTTP 비 200·응답 개수 불일치)은 {@link Rejected} 다 — 스위퍼는 그 행만 건너뛴다.
 * app.embedder.url 이 비면 의미 검색을 끈 것이다(테스트와 EMBEDDER_URL= 배포).
 */
@Component
public class EmbedderClient {
    public enum Mode { query, passage }

    /** model 은 embedder 가 실제로 쓴 모델 이름이다. 조각에 같이 저장해 다른 모델의 벡터와 섞지 않는다. */
    public record Embedded(String model, List<float[]> vectors, List<Boolean> truncated) {
    }

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** embedder 는 응답했지만 이 요청을 거부했다. 연결 실패·타임아웃과 달리 같은 입력을 다시 보내도 같은 결과일 수 있다. */
    public static class Rejected extends Unavailable {
        public Rejected(String message) {
            super(message, null);
        }
    }

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public EmbedderClient(@Value("${app.embedder.url:}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.mapper = mapper;
    }

    public boolean configured() {
        return !baseUrl.isEmpty();
    }

    /** GET /health 의 모델 이름. 스위퍼가 회차마다 한 번 묻는다(재사용할 조각의 모델 비교). */
    public String model() {
        String model = send(HttpRequest.newBuilder(uri("/health")).timeout(Duration.ofSeconds(3)).GET().build())
                .path("model").asString("");
        if (model.isBlank()) throw new Rejected("embedder /health 에 모델 이름이 없습니다");
        return model;
    }

    public Embedded embed(Mode mode, List<String> texts, Duration timeout) {
        byte[] body = mapper.writeValueAsBytes(Map.of("mode", mode.name(), "texts", texts));
        JsonNode json = send(HttpRequest.newBuilder(uri("/embed")).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode node : json.path("vectors")) {
            float[] vector = new float[node.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = node.get(i).floatValue();
            vectors.add(vector);
        }
        List<Boolean> truncated = new ArrayList<>();
        json.path("truncated").forEach(node -> truncated.add(node.asBoolean()));
        if (vectors.size() != texts.size()) throw new Rejected("embedder 응답 개수가 다릅니다");
        return new Embedded(json.path("model").asString(), vectors, truncated);
    }

    private URI uri(String path) {
        if (!configured()) throw new Unavailable("embedder 가 설정되지 않았습니다(app.embedder.url)", null);
        return URI.create(baseUrl + path);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new Rejected("embedder HTTP " + response.statusCode());
            return mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("embedder 호출이 중단됐습니다", interrupted);
        } catch (Unavailable unavailable) {
            throw unavailable;
        } catch (Exception failure) {
            throw new Unavailable("embedder 호출 실패: " + failure.getClass().getSimpleName(), failure);
        }
    }
}
