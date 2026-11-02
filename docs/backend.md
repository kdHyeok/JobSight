# backend — Spring Boot 4.1 / Java 21

## 진입점

| 무엇 | 어디 |
|---|---|
| 모든 HTTP 경로 상수 | `common/ApiPaths.java` — 컨트롤러와 `SecurityConfig` 가 같은 상수를 쓴다 |
| 인가 규칙, CSRF, OAuth 실패 처리 | `auth/SecurityConfig.java` |
| Google 로그인 → 계정 해석 | `auth/GoogleOidcUserService.java` → `user/AppUserService.resolveGoogleUser` |
| 로그인 principal | Google `auth/AppOidcUser.java`, 갱신 세션 `auth/AppSessionUser.java`, MCP 위임 `mcp/McpPrincipal.java` |
| 브라우저 세션 갱신 | `auth/BrowserRefreshTokenService.java` — HttpOnly opaque 토큰 회전·해시 저장·폐기 |
| 현재 사용자 읽기 | `auth/CurrentUser.java` — 서비스가 직접 호출, 컨트롤러는 소유자를 넘기지 않는다 |
| Google 클라이언트 등록 (조건부) | `config/GoogleOAuthConfig.java` |
| 에러 응답 형식 | `common/GlobalExceptionHandler.java`, `common/ApiError`, `common/ApiRuleException`. 도메인 예외 → 상태·코드는 `common/ApiErrors.translate` 하나가 REST 와 MCP 에 같이 정한다 — docs/mcp-plugin.md |
| 스키마 | `resources/db/migration/V1~V29` — Flyway, `ddl-auto: validate` |
| 의미 검색(5단계) | `embedding/` — `EmbedderClient`(embedder 호출), `EmbeddingSweeper`(dirty 스위퍼, 유일한 `@Scheduled`), `SystemEmbeddingSweepRepository`(소유자 조건 없는 유일한 저장소, 패키지 전용), `DenseIndex`(소유자로 거른 벡터·cosine) — docs/experience-graph-search.md |
| 계정 단위 직렬화(T9) | `common/OwnerLock.acquire(owner)` — 경험 관계를 바꾸는 쓰기의 맨 앞. 아래 「경험 관계 쓰기의 잠금과 관문」 |
| 이력서 content 쓰기 관문 | `resume/ResumeWriter` — 이력서 JSON 을 쓰는 유일한 곳(검증 → 링커 → 저장 → `resume_experiences` → `SET CONSTRAINTS ALL IMMEDIATE`) — docs/experiences.md |
| 설정 | `resources/application.yml` — 비밀값은 `configtree:/run/secrets/` |

DB 연결 획득은 3초 안에 실패시킨다. PostgreSQL 행 잠금(직무 삭제·공고 수정의 공고 행, 이력서 쓰기의 이력서 행)은 `SET LOCAL lock_timeout = '3s'`로 현재 트랜잭션에 한정해 요청이 매달리지 않게 한다.
잠금 대기 초과(SQLState 55P03)나 교착(40P01)은 어디서 나든 409 `BUSY` 다(`ApiErrors`). 커밋 시점 실패는 `TransactionSystemException`·`JpaSystemException` 으로 감싸져 오므로 원인 체인의 SQLState 까지 본다. 잠금 조회 자리에서 바로 잡는 `RESUME_BUSY`·`POSITION_DELETE_BUSY` 는 그대로 둔다. `/actuator/health`의 DB 지표와 로그인 옵션 조회가 장애 감지 경로다.

## 경험 관계 쓰기의 잠금과 관문 (4단계, docs/experiences.md)

- **`OwnerLock`**: `SET LOCAL lock_timeout = '3s'` **바로 다음 문장**에서 `pg_advisory_xact_lock(hashtextextended('exp-graph:' || owner, 0))`.
  순서를 바꾸면 권고 잠금 대기에 상한이 없어 연결 풀이 마를 수 있다. 대기 초과는 55P03 → 409 `BUSY`. `Propagation.MANDATORY` 라 트랜잭션 밖에서 부르면 실패한다.
  부르는 곳: 이력서 create·update·copy·delete·basic·행 도구·`addExperiences`, 경험 저장·검토·삭제·가져오기, 자소서 create·update.
- **행 잠금 순서**: 권고 잠금 → 이력서(id 오름차순, `findOwnedForUpdate`·`findAllOwnedForUpdate`) → 경험. JPA `PESSIMISTIC_WRITE` 는 PostgreSQL 에서 `for no key update` 다. **예외는 첨부 삭제 하나**: `AttachmentService.delete` 는 네이티브 `for update` 로 첨부 행을 잡아, 첨부 id 를 새로 적는 쓰기의 `for key share`(규칙 B, `AttachmentRepository.lockForReference`)와 일부러 충돌시킨다. 삭제는 계정 잠금을 잡지 않고 첨부 행 하나만 잠근다 — docs/attachments.md 불변 조건 7·8.
- **`ResumeWriter` 단일 관문**: `ResumeRepository.save*` 는 이 클래스 밖에서 부르지 않고 `Resume.update` 는 패키지 전용이다. `ResumeWriterGateTest` 가 소스를 검사한다
  (ArchUnit 을 들이지 않았다). JPA 엔티티 저장 뒤 같은 트랜잭션에서 `JdbcTemplate` 으로 `resume_experiences` 를 맞춘다 — 같은 연결이므로 저장을 먼저 flush 한다.
- **`Persistable`**: 생성자에서 UUID 를 채우고 `@Version` 이 primitive 인 엔티티는 `save` 가 merge(+SELECT)가 된다. 대량 insert 하는 `Experience` 는 `isNew` 를 직접 답한다.
- **`TEXT[]` 매핑**: `@JdbcTypeCode(SqlTypes.ARRAY)` + `List<String>` + `columnDefinition = "text[]"` 로 `ddl-auto: validate` 를 통과한다(V25, 통합 테스트 기동으로 확인).
- **`REAL[]` 벡터는 엔티티에 매핑하지 않는다**(5단계). 조각 테이블(`self_introduction_chunks`·`experience_chunks`)은 JDBC 로만 쓰고 읽는다 — 쓰기는
  `createArrayOf("float4", Float[])`, 읽기는 `rs.getArray(...).getArray()` 가 `Float[]` 다. 그래서 `ddl-auto: validate` 와 무관하다.
  엔티티(`SelfIntroduction`·`Experience`)에는 `embedding_dirty`·`source_revision` 만 있고 원문이 바뀔 때만 올린다. 엔티티 저장은 이 두 열을 다시 쓰므로,
  스위퍼가 그사이 dirty 를 내렸다면 낡은 true 가 다시 써질 수 있다 — 한 번 더 도는 것뿐이라 무해하다(해시가 같아 embedder 를 부르지 않는다).
- **PostgreSQL 드라이버는 `implementation`** 이다(전에는 `runtimeOnly`). `ApiErrors` 가 FK 위반의 제약 이름·메시지를 `PSQLException` 에서 읽는다.

## 배치 페치와 SQL 수 상한

`spring.jpa.properties.hibernate.default_batch_fetch_size: 100` 은 컬렉션이나 지연 프록시를 처음 읽을 때, 같은 영속성 컨텍스트에서 아직 읽지 않은 같은 종류의 소유 엔티티를 **최대 100개까지 묶어 SQL 한 번**으로 읽는다.
100은 컬렉션 안의 원소 수가 아니라 SQL 하나에 묶는 소유 엔티티(직무·공고·기업 등) 수다.
EAGER 컬렉션(`Position` 의 `techStack`·`referenceIds`, `JobPosting.steps`, `Company` 의 `industries`·`businesses`, `Resume.positionIds`)만이 아니라 LAZY 컬렉션(`SelfIntroduction.resumeIds`)도 이 설정을 탄다.

- 실측(`PositionListQueryCountIntegrationTest`, 기업 5·공고 5·직무 10): 직무 목록(`GET /api/positions`) 1회 SQL **39 → 9**.
  9회 = 직무·공고·기업·참고 정보 조회 4회 + EAGER 컬렉션 5종 각 1회.
- 이 테스트가 상한 9를 단언한다. 목록 경로에 SQL 을 더하면 여기서 깨진다. 늘어난 이유가 정당하면 실측해서 상한과 이 줄을 함께 고친다.
- `hibernate.generate_statistics` 는 그 테스트의 `@SpringBootTest(properties=…)` 에서만 켠다. `application.yml` 에 넣지 않는다.
- 함정: 같은 종류의 소유 엔티티가 100개를 넘으면(예: 직무 101개) 그 컬렉션의 SQL 이 묶음 수만큼 는다(101개면 2회). 컬렉션 하나에 원소가 많은 것과는 무관하다. 새 컬렉션은 EAGER 든 LAZY 든 이 설정을 자동으로 탄다.
- MCP 자소서 `self_intro_get`·`self_intro_list`(`SelfIntroductionAgentService`)는 계정 직무 상세(`PositionService.findAll`)와 이력서 엔티티(content JSONB)를
  읽지 않고, 연결된 것의 이름만 constructor projection(`PositionRepository.findLabels`·`findAllLabels`, `ResumeRepository.findPositionRows`)으로 읽는다.
  `SelfIntroductionRelationsIntegrationTest.agentReadsOnlyLabelsWithinStatementLimit` 가 상한(실측 상세 6·목록 6·직무 필터 7·기업 필터 7, 4단계에서 작성 근거 컬렉션 1회가 더해져 7·7·8·8. 작성 근거가 있는 문항이 쪽에 있으면 경험 이름·개정 이력 2회가 더 붙는다)과
  `Resume`·`Position`·`JobPosting`·`Company` 엔티티 로드 0을 단언한다. 공고·기업 소유 확인은 `existsByIdAndOwnerId` 다.
  함정: 이 상한은 Hibernate 통계(`getPrepareStatementCount`)라 `JdbcTemplate` 문장은 세지 않는다. 제출·작성 기준 트랙에서 쪽이 비어 있지 않으면
  `SubmissionService.confirmedByPosting` 의 JDBC 1회(사용자 확인 제출 기록, alreadyUsedFor)가 더 돈다 — 실제 SQL 은 상한보다 1 많다.

## 조회 정리 (성능 트랙, 설계 1.3 P3·P4·P6~P11, 1.5)

회귀 방지는 `common/QueryCleanupIntegrationTest`(Testcontainers, 통계는 이 테스트에서만 켠다)다. 응답 내용과 함께 읽은 엔티티 수·SQL 수·컬렉션 재생성 수를 단언한다.
전·후 값은 같은 테스트를 고치기 전 코드와 고친 뒤 코드에서 돌려 읽은 것이다(`MEASURE` 출력, Hibernate `getPrepareStatementCount` 라 `JdbcTemplate` 문장은 세지 않는다).

| # | 무엇 | 바꾼 것 | 전 → 후 (실측) |
|---|---|---|---|
| P3 | `GET /api/self-introductions?resumeId=` | `SelfIntroductionRepository.findAllLinkedToResume`(연결 표 조인). 소유자 전체를 읽고 자바에서 거르지 않는다 | 자소서 6 중 2 연결: 읽은 자소서 엔티티 **6 → 2**, SQL 5 → 5(1d 배치 페치로 이미 묶여 있었다) |
| P4 | `GET /api/resumes`(`resume_list`) | `ResumeRepository.findListRows` — 이력서×직무 constructor projection 한 번, 서비스가 묶는다 | SQL **2 → 1**, 이력서 엔티티(content JSONB) 로드 4 → **0** |
| P6 | `GET /api/positions?view=summary` (새 쿼리 파라미터, 경로 표는 그대로) | `PositionRepository.findOptions` → `PositionOption`(id·이름·공고·기업·`updatedAt`). 이력서 편집기(`ResumeBoard`)만 쓴다 | SQL **9 → 1**(9 는 전체 목록 `PositionListQueryCountIntegrationTest`), 엔티티 로드 0 |
| P7 | 이력서 저장·자소서 생성/수정의 소유 확인 | `ResumeRepository.findOwnedIds`·`PositionRepository.findOwnedIds` — id 만 읽는다. 없거나 남의 id 는 그대로 404 | `resume.update`(직무 3): SQL **10 → 8**, 엔티티 로드 4 → 1(이력서 자신). 자소서 생성(이력서 1·직무 3): 직무·이력서 엔티티 로드 0(후 SQL 9) |
| P8 | 기업·직무 저장 | `Company` 업종은 빠진 것만 빼고 새것만 더하고, 주요 사업(`@OrderColumn`)은 자리별로 바뀐 칸만 고친다(`BusinessArea` 에 값 `equals`). `Position` 스택·참고 정보도 같은 방식. `Resume` 은 4a 에서 했다 | 같은 값으로 기업+직무 저장: SQL **28 → 19**, 컬렉션 재생성(전체 DELETE·INSERT) 1 → **0** |
| P10 | MCP 토큰 조회 `findByToken(…, ACCESS_TOKEN)` | `V29__indexes` 의 hash 인덱스 2개 | 아래 |
| 1.5 | ON DELETE 참조 쪽 | `V29__indexes`: `job_postings(target_position_id)`·`reference_items(related_position_id)` 부분 인덱스, `position_references(reference_id)` | 테스트가 인덱스 5개와 토큰 둘의 `USING hash` 를 확인 |

P10 실측: 워크트리 스택(`jobsite-wt-graph`)의 `oauth2_authorization` 은 0행이었다. 그래서 트랜잭션 안에 합성 5,000행을 넣고 `ANALYZE` 뒤 `EXPLAIN ANALYZE` 를 보고 ROLLBACK 했다.
access 조회 Seq Scan 0.358ms(4,999행 버림) → Index Scan 0.031ms, refresh 조회 0.535ms → 0.017ms. **배포 서버의 행 수와 만료 행 누적은 보지 못했다**(접근 경로 없음).
만료 행이 쌓여 있으면 정리 작업이 먼저인지 판단해야 한다는 설계 1.3 P10 의 조건은 배포 서버에서 `select count(*), count(*) filter (where access_token_expires_at < now()) from oauth2_authorization` 으로 확인한다.

함정:
- hash 인덱스인 이유: 토큰 문자열이 btree 행 크기 상한(약 2.7KB)을 넘을 수 있고, 조회는 등호뿐이다. 타입 없는 revoke·introspect 는 여러 컬럼을 OR 로 묶어 seq scan 이 남을 수 있다(빈도가 낮아 허용, 설계 1.5).
- P3 의 인덱스 사용(`idx_self_introduction_resumes_resume`)은 EXPLAIN 으로 확인하지 않았다. 계정당 수백 행이면 플래너가 seq scan 을 고를 수 있고, 이득은 읽는 행 수다.
- P6 의 `view=summary` 는 `position_list` MCP 에 노출되지 않는다. MCP 는 `PositionService.findAll` 을 이름으로 등록하고, 요약은 별도 메서드(`findOptions`)·별도 핸들러(`@GetMapping(params = "view=summary")`)다. 그래서 MCP 도구 설명은 바꾸지 않았다.
- 자소서 화면(`SelfIntroductionBoard`)과 직무 화면은 여전히 전체 목록을 받는다. 직무 화면은 스택·참고 정보가 필요하고, 자소서 화면은 이번 범위 밖이다.
- P8: 컬렉션 필드에 새 컬렉션을 대입하면 Hibernate 는 통째 재생성(전체 DELETE·INSERT)한다. 같은 인스턴스를 고친다. `@OrderColumn` 목록은 자리별로 비교해 바뀐 칸만 `set` 하는데, 원소에 값 `equals` 가 없으면 같은 값도 바뀐 것으로 본다. 새 `@Embeddable` 목록 원소에는 `equals`·`hashCode` 를 둔다.
- `ResumeSummary.of(Resume)` 는 지웠다. 목록을 엔티티에서 만들면 content 를 다시 읽는다.

## 요청 한 번의 흐름

```
nginx → SecurityFilterChain
          ├ CsrfFilter (+CsrfCookieFilter: 토큰을 항상 쿠키로 내림)
          ├ OAuth2 필터: /oauth2/authorization/google, /login/oauth2/code/google
          └ AuthorizationFilter: ApiPaths 매처 → 401 / 403 JSON
        → Controller (@Valid) → Service (@Transactional, CurrentUser.id()) → Repository → PostgreSQL
```

## 계정 모델

- `app_users(id, email, google_sub, display_name, role, status, admin_request_*)` — 식별자는 `google_sub`. 이메일은 검증된 경우에만 기존 계정 연결에 쓴다. `display_name` 만 사용자·관리자가 편집한다(첫 로그인 때 Google `name` 으로 초기화). `admin_request_*`는 삭제로 우회할 수 없는 요청 생성 제한 상태다.
- `browser_refresh_tokens(id, owner_id, token_hash, expires_at, created_at, last_used_at)` — 브라우저 장기 로그인용이다. 원문은 저장하지 않고 30일 절대 만료 안에서 매 갱신마다 해시를 회전한다(V17).
- 계정 삭제(`DELETE /api/admin/users/{id}`)는 `companies.owner_id` FK 의 `ON DELETE CASCADE` 로 소유 기업까지 지운다. 자기 자신·마지막 활성 관리자는 거부.
- 상태 `PENDING/ACTIVE/SUSPENDED/REJECTED`, 권한 `USER/ADMIN`. 로그인은 ACTIVE 만.
- 첫 Google 로그인의 초기 상태는 `app_settings.auto_approve_signup` 이 정한다: true → ACTIVE(바로 이용), false → PENDING(승인 대기). 가입을 막는 상태는 없다(V8).
- `APP_ADMIN_EMAIL` 계정은 `resolveGoogleUser` 가 매 로그인마다 ADMIN/ACTIVE 로 맞춘다. 새 배포에서는 V4 의 고정 행(`BOOTSTRAP_ADMIN_ID`)을 넘겨받아 시드 기업을 소유한다.
- `companies.owner_id` NOT NULL. 조회는 반드시 `…AndOwnerId`.

## 상태 검사는 트랜잭션 밖에서

`GoogleOidcUserService` 가 `resolveGoogleUser`(커밋됨) 뒤에 상태를 검사해 예외를 던진다. 안에서 던지면 방금 만든 PENDING 계정이 롤백되어 관리자 화면에 신청이 안 보인다.

## 버전 함정 (Spring Boot 4.1 / Spring Security 7.1)

- Jackson 3 → `import tools.jackson.databind.ObjectMapper`, 의존성 `spring-boot-starter-jackson`. `com.fasterxml.jackson.databind` 는 컴파일 클래스패스에 없다.
- CSRF → `CsrfTokenRequestAttributeHandler` 명시 (기본 `XorCsrfTokenRequestAttributeHandler` 는 쿠키 원본 토큰을 거부해 모든 POST 가 403).
- 브라우저 세션은 30분 유휴 만료다. `POST /api/auth/refresh`는 CSRF 검증 뒤 ACTIVE 계정의 토큰만 회전하고 `SecurityContextRepository`에 새 세션을 저장한다. 자세한 계약은 `docs/auth-session.md`.
- OAuth2 등록은 프로퍼티가 아니라 `GoogleOAuthConfig` 빈. `spring.security.oauth2.client.registration.*` 에 빈 client-id 가 있으면 기동 실패.
- springdoc 은 3.x (`springdoc-openapi-starter-webmvc-ui:3.1.0`). 2.x 는 Boot 4 에서 안 뜬다.
- `redirect_uri` 는 `app.public-base-url` 로 조립. `X-Forwarded-*` 추론 금지.

## 마이그레이션 규칙

적용된 `V*.sql` 은 수정하지 않는다. 새 번호로 추가한다. 데이터 삭제는 마이그레이션에 넣지 않고 사람이 결정한다(V6 참고).

기업별 뉴스·유튜브 자료는 `V11__company_contents.sql`과 `companycontent/*`가 담당한다. 회사·콘텐츠 조회 모두 `owner_id` 조건을 쿼리에 포함한다.
이력서–직무 연결과 자기소개 문항은 `V19__resume_positions_and_self_introductions.sql`, `resume/*`, `selfintro/*`가 담당한다.
자기소개의 직접 출처(직무)·문항 유형 간선은 `V23__self_intro_relations.sql` 이다. 간선은 `@ElementCollection` 의 `@Embeddable` record
(`owner_id` 포함)로 매핑해 `(id, owner_id)` 복합 FK 를 Hibernate 가 그대로 채운다. 시스템 어휘 표 `competencies` 는 enum 과 기동 시 대조한다.

## 컴파일·테스트

컨테이너에서 `./gradlew compileJava compileTestJava`. 전체 `test` 는 Testcontainers 라 Docker 소켓이 필요하다(README). 종료코드는 `$?` 로 직접 읽는다.

MCP는 별도의 stateless Bearer 체인과 Spring OAuth Authorization Server를 사용한다.
`mcp/*`의 입력 검증·허용 도구·scope/현재 DB 역할 검사가 컨트롤러 직접 호출을 보호한다.
OAuth/MCP 테스트는 `./gradlew test --tests '*Mcp*Test'`로 Docker 소켓 없이 실행한다.
Spring Security 7의 AuthorizationServerConfigurer 패키지는 `security.config.annotation.web.configurers.oauth2.server.authorization`이다.
등록과 제한은 [mcp-plugin.md](mcp-plugin.md)를 따른다.

`ChatGptClients`는 고정 ChatGPT CIMD 경로만 조회하고 기존 사전 등록 클라이언트를 보존한다.
Codex native CIMD 경로도 지원하며 해당 문서 ID에 고정된 loopback 콜백만 등록한다.
임시 포트 허용은 Spring 기본 loopback IP 검증을 사용한다. 별도 리다이렉트 검증기를 만들지 않는다.
실제 공개 문서의 복수 인증 방식 중 none을 선택하고, grant_types 중 authorization_code만 발급한다.
스킬 리소스도 검증하려면 테스트 컨테이너에 저장소 루트를 `/workspace`로 마운트하고 `-w /workspace/backend`로 실행한다.
`processResources`는 `../plugins/jobsight`의 스킬·manifest·MCP 파일만 포함한다.
