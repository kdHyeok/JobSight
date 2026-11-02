package com.jobsight.company.embedding;

import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.mcp.McpOAuthConfig;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.mcp.McpTools;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 5단계 의미 검색 — 설계 7절 5단계 검증 칸. embedder 는 가짜(같은 개념 묶음의 단어를 같은 축에 두는 벡터)로 바꾸고,
 * 스위퍼는 예약 실행을 끈 채 sweepOnce 를 직접 부른다(회차를 테스트가 정한다). 테스트마다 새 계정을 만든다.
 */
@Testcontainers
@SpringBootTest(properties = {"app.embedder.url=http://embedder.invalid", "app.embedder.sweep=false"})
class SemanticSearchIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static final String MODEL = "fake-e5";
    /** 같은 묶음의 단어는 같은 축이다 — "팀워크" 질의가 "협업" 본문을 찾는 것은 의미 검색만 할 수 있다. */
    static final List<List<String>> CONCEPTS = List.of(List.of("협업", "팀워크"), List.of("갈등", "충돌"),
            List.of("kafka", "카프카"), List.of("정산"), List.of("리더십"));

    @MockitoBean EmbedderClient embedder;
    @Autowired EmbeddingSweeper sweeper;
    @Autowired McpTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired DenseIndex dense;

    private McpPrincipal principal;
    private UUID owner;

    static float[] fake(String text) {
        float[] vector = new float[384];
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CONCEPTS.size(); i++) {
            for (String word : CONCEPTS.get(i)) if (lower.contains(word)) vector[i] += 1;
        }
        vector[383] = 0.1f;   // 아무 개념도 없는 글도 0 벡터가 아니게
        double norm = 0;
        for (float v : vector) norm += v * v;
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) Math.sqrt(norm);
        return vector;
    }

    /** embedder 가 살아 있다. doReturn 형식이라 앞에서 던지게 바꾼 스텁도 덮는다. */
    void embedderUp() {
        doReturn(true).when(embedder).configured();
        doReturn(MODEL).when(embedder).model();
        doAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<float[]> vectors = new ArrayList<>();
            texts.forEach(text -> vectors.add(fake(text)));
            return new EmbedderClient.Embedded(MODEL, vectors, texts.stream().map(text -> false).toList());
        }).when(embedder).embed(any(), anyList(), any());
    }

    @BeforeEach
    void signIn() {
        embedderUp();
        owner = UUID.randomUUID();
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now())", owner, owner + "@example.com", "sub-" + owner);
        principal = new McpPrincipal(owner, Set.copyOf(McpOAuthConfig.SCOPES));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        // 다른 테스트가 남긴 dirty 행을 이 회차에 섞지 않는다(스위퍼는 계정과 무관하게 돈다).
        jdbc.update("update self_introductions set embedding_dirty = false where owner_id <> ?", owner);
        jdbc.update("update experiences set embedding_dirty = false where owner_id <> ?", owner);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Object call(String tool, Map<String, Object> arguments) {
        return tools.call(tool, arguments, principal);
    }

    @SuppressWarnings("unchecked")
    private UUID resume() {
        Map<String, Object> created = (Map<String, Object>) call("resume_create", Map.of("request", Map.of("name", "기본",
                "content", Map.of())));
        return UUID.fromString(created.get("id").toString());
    }

    private SelfIntroductionResponse selfIntro(UUID resumeId, String question, String answer) {
        return (SelfIntroductionResponse) call("self_intro_create", Map.of("request", Map.of(
                "resumeIds", List.of(resumeId.toString()), "question", question, "answer", answer)));
    }

    private UUID experience(String title, String action) {
        return ((ExperienceViews.Saved) call("experience_save", Map.of("request", Map.of("kind", "PROJECT", "title", title,
                "facts", Map.of("actions", List.of(Map.of("text", action))))))).id();
    }

    private ExperienceSearchResult search(String requiredSkills) {
        return (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of("positionName", "서버 개발", "requiredSkills", requiredSkills))));
    }

    private long chunks(String table, String key, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + key + " = ?", Long.class, id);
    }

    private boolean dirty(String table, UUID id) {
        return jdbc.queryForObject("select embedding_dirty from " + table + " where id = ?", Boolean.class, id);
    }

    /** embedder 가 죽어 있어도 저장은 성공하고 검색은 degraded 로 동작한다. 재기동 뒤 한 회차에 dirty 0 으로 수렴한다. */
    @Test
    void embedderDownKeepsSavingAndDegradesThenCatchesUp() {
        doThrow(new EmbedderClient.Unavailable("down", null)).when(embedder).model();
        doThrow(new EmbedderClient.Unavailable("down", null)).when(embedder).embed(any(), anyList(), any());
        UUID resumeId = resume();
        UUID intro = selfIntro(resumeId, "협업 경험", "팀 프로젝트에서 협업했다").id();
        UUID experienceId = experience("결제 정산 배치", "정산 배치를 개선했다");

        assertThatThrownBy(() -> sweeper.sweepOnce()).isInstanceOf(EmbedderClient.Unavailable.class);
        ExperienceSearchResult result = search("정산");
        assertThat(result.degraded()).containsExactly("dense");
        assertThat(result.context().embeddingModel()).isNull();
        assertThat(result.context().indexBehind()).isTrue();
        assertThat(result.results()).extracting(ExperienceSearchResult.Result::experienceId).containsExactly(experienceId);
        SelfIntroductionList list = (SelfIntroductionList) call("self_intro_list", Map.of("q", "협업"));
        assertThat(list.degraded()).containsExactly("dense");
        assertThat(list.results()).extracting(SelfIntroductionList.Item::id).containsExactly(intro);

        embedderUp();
        assertThat(sweeper.sweepOnce()).isEqualTo(2);
        assertThat(dirty("self_introductions", intro)).isFalse();
        assertThat(dirty("experiences", experienceId)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from self_introductions where owner_id = ? and embedding_dirty",
                Long.class, owner)).isZero();
        ExperienceSearchResult caughtUp = search("정산");
        assertThat(caughtUp.degraded()).isEmpty();
        assertThat(caughtUp.context().embeddingModel()).isEqualTo(MODEL);
        assertThat(caughtUp.context().indexBehind()).isFalse();
        assertThat(caughtUp.context().indexGeneration()).isNotNull();
    }

    /** DENSE: 단어가 달라도 뜻이 같으면 찾는다. why 에 DENSE 와 맞은 조각의 필드가 실린다. */
    @Test
    void denseFindsSynonymsInExperienceSearchAndSelfIntroList() {
        UUID resumeId = resume();
        UUID teamwork = experience("사내 해커톤", "다섯 명과 협업해 일정 안에 배포했다");
        experience("결제 정산 배치", "정산 배치를 개선했다");
        UUID collaboration = selfIntro(resumeId, "가장 힘들었던 경험", "팀원과 협업하며 일정을 맞췄다").id();
        selfIntro(resumeId, "지원 동기", "정산 도메인에 관심이 있다");
        sweeper.sweepOnce();

        ExperienceSearchResult result = search("팀워크 역량");
        ExperienceSearchResult.Result top = result.results().get(0);
        assertThat(top.experienceId()).isEqualTo(teamwork);
        assertThat(top.why()).anySatisfy(why -> {
            assertThat(why.signal()).isEqualTo("DENSE");
            assertThat(why.rank()).isEqualTo(1);
            assertThat(why.sourceField()).isEqualTo("facts.actions");
        });
        assertThat(top.why()).noneMatch(why -> why.signal().equals("LEXICAL"));

        SelfIntroductionList list = (SelfIntroductionList) call("self_intro_list", Map.of("q", "팀워크"));
        assertThat(list.degraded()).isEmpty();
        assertThat(list.results().get(0).id()).isEqualTo(collaboration);
    }

    /** 해시가 같은 조각은 다시 계산하지 않는다 — 답변만 바꾸면 답변 조각만 embedder 로 간다. 연결만 바꾼 저장은 dirty 를 세우지 않는다. */
    @Test
    void onlyChangedChunksAreRecomputed() {
        UUID resumeId = resume();
        SelfIntroductionResponse created = selfIntro(resumeId, "지원 동기", "정산 도메인에 관심이 있다");
        sweeper.sweepOnce();
        long before = jdbc.queryForObject("select source_revision from self_introductions where id = ?", Long.class, created.id());

        clearInvocations(embedder);
        SelfIntroductionResponse updated = (SelfIntroductionResponse) call("self_intro_update", Map.of("id", created.id().toString(),
                "request", Map.of("resumeIds", List.of(resumeId.toString()), "question", "지원 동기",
                        "answer", "카프카 경험을 살리고 싶다", "expectedVersion", created.version())));
        assertThat(dirty("self_introductions", created.id())).isTrue();
        assertThat(sweeper.sweepOnce()).isEqualTo(1);
        verify(embedder).embed(eq(EmbedderClient.Mode.passage), eq(List.of("카프카 경험을 살리고 싶다")), any(Duration.class));
        verify(embedder, never()).embed(eq(EmbedderClient.Mode.query), anyList(), any(Duration.class));
        assertThat(chunks("self_introduction_chunks", "self_introduction_id", created.id())).isEqualTo(2);

        // 연결(문항 유형)만 더한 저장: 원문 버전이 그대로라 다시 적재하지 않는다.
        call("self_intro_update", Map.of("id", created.id().toString(), "request", Map.of("resumeIds",
                List.of(resumeId.toString()), "question", "지원 동기", "answer", "카프카 경험을 살리고 싶다",
                "questionTypes", List.of("MOTIVATION"), "expectedVersion", updated.version())));
        assertThat(jdbc.queryForObject("select source_revision from self_introductions where id = ?", Long.class, created.id()))
                .isEqualTo(before + 1);
        assertThat(dirty("self_introductions", created.id())).isFalse();
    }

    /** 스위퍼가 읽은 뒤 원문이 바뀌면 그 회차는 쓰지 않는다(CAS 0 행). 다음 회차에 새 원문으로 채운다. */
    @Test
    void sourceChangedAfterReadIsNotWritten() {
        UUID resumeId = resume();
        UUID intro = selfIntro(resumeId, "협업 경험", "협업했다").id();
        doAnswer(invocation -> {
            // embedder 를 기다리는 사이에 다른 저장이 원문을 바꿨다
            jdbc.update("update self_introductions set source_revision = source_revision + 1 where id = ?", intro);
            List<String> texts = invocation.getArgument(1);
            return new EmbedderClient.Embedded(MODEL, texts.stream().map(SemanticSearchIntegrationTest::fake).toList(),
                    texts.stream().map(text -> false).toList());
        }).when(embedder).embed(any(), anyList(), any());
        assertThat(sweeper.sweepOnce()).isZero();
        assertThat(dirty("self_introductions", intro)).isTrue();
        assertThat(chunks("self_introduction_chunks", "self_introduction_id", intro)).isZero();

        embedderUp();
        assertThat(sweeper.sweepOnce()).isEqualTo(1);
        assertThat(chunks("self_introduction_chunks", "self_introduction_id", intro)).isEqualTo(2);
    }

    /** 벡터 열 모양: 384 차원이 아니면 DB 가 거부한다. 경험을 지우면 조각도 지워진다. */
    @Test
    void vectorColumnRejectsWrongDimensionAndCascades() {
        UUID experienceId = experience("결제 정산 배치", "정산 배치를 개선했다");
        sweeper.sweepOnce();
        assertThat(chunks("experience_chunks", "experience_id", experienceId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into experience_chunks (owner_id, experience_id, chunk_no, field, text_hash, "
                + "embedding_model, embedding, embedded_at) values (?, ?, 9, 'x', 'h', 'm', '{0.1,0.2}'::real[], now())",
                owner, experienceId)).hasMessageContaining("experience_chunks_dim_ck");
        jdbc.update("delete from experiences where id = ?", experienceId);
        assertThat(chunks("experience_chunks", "experience_id", experienceId)).isZero();
    }

    /**
     * D11 실측: REAL[] 로 계정 조각 1000개를 읽어 cosine 을 재는 비용과 전송량. 값은 docs/experience-graph-search.md 에 옮겼다.
     * 상한은 회귀 감시용으로 넉넉하게 둔다(CI 기계 편차).
     */
    @Test
    void realArrayReadCostForThousandChunks() {
        UUID experienceId = experience("측정용", "측정");
        StringBuilder vector = new StringBuilder("{");
        for (int i = 0; i < 384; i++) vector.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.7f", Math.sin(i) / 14));
        vector.append('}');
        jdbc.update("insert into experience_chunks (owner_id, experience_id, chunk_no, field, text_hash, embedding_model, "
                + "embedding, embedded_at) select ?, ?, g, 'facts.actions', 'h' || g, ?, ?::real[], now() "
                + "from generate_series(1, 1000) g", owner, experienceId, MODEL, vector.toString());
        Map<String, Object> size = jdbc.queryForMap("select sum(pg_column_size(embedding)) as stored, "
                + "sum(octet_length(embedding::text)) as text from experience_chunks where owner_id = ?", owner);
        DenseIndex.Query query = new DenseIndex.Query(MODEL, fake("협업"));
        dense.experiences(owner, query);   // 준비 실행(JIT·드라이버 준비)
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            assertThat(dense.experiences(owner, query)).containsKey(experienceId);
            best = Math.min(best, System.nanoTime() - start);
        }
        // 같은 행을 문자열로만 받는 시간 — 차이가 배열 파싱·변환과 cosine 비용이다.
        long textOnly = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            jdbc.query("select embedding::text from experience_chunks where owner_id = ?", rs -> {
                rs.getString(1);
            }, owner);
            textOnly = Math.min(textOnly, System.nanoTime() - start);
        }
        System.out.printf("D11 REAL[] 1000 chunks: stored=%s bytes, text=%s bytes, read+cosine best=%.1f ms, text-only fetch best=%.1f ms%n",
                size.get("stored"), size.get("text"), best / 1e6, textOnly / 1e6);
        assertThat(best).isLessThan(Duration.ofSeconds(2).toNanos());
    }
}
