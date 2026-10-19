# 새 도메인 OAuth·플러그인 연동 설계

작성: 2026-10-19 (KST) · 기능: `custom-domain-oauth` · 상태: 설계 완료, 구현·배포 전
근거: [계획](../../01-plan/features/custom-domain-oauth.plan.md), [기존 MCP 계약](../../mcp-plugin.md), [인증 세션](../../auth-session.md), [배포](../../nginx-deploy.md), [기존 결정](../../decisions.md).

## 1. 구조와 확인 결과

정식 origin은 `https://job.donyk.link` 하나로 한다. 기존 Google 로그인과 MCP 인가 서버는 이미 `PUBLIC_BASE_URL`을 사용한다. 주소별 인증 구현을 복제하지 않고 환경·정적 배포 파일·전환 검증을 연결한다.

```text
PUBLIC_BASE_URL=https://job.donyk.link
  ├ GoogleOAuthConfig → Google redirect_uri
  ├ SecurityConfig → Google 로그인 후 저장된 MCP 인가 요청으로 복귀
  └ McpOAuthConfig → issuer · resource · 401 challenge · Origin 검증
       ├ McpController → protected-resource metadata
       └ PluginGuideController → 공개 config · ZIP의 .mcp.json

GitHub plugins/jobsight/.mcp.json → 동일한 공개 MCP URL (별도 정적 배포물)
```

실서비스를 읽기 전용으로 확인한 결과, 새 origin의 `/health`는 200이지만 `/plugin/config`와 두 검색 문서는 구 origin을 광고한다. Google 시작 응답의 `Location`에서도 구 callback을 확인했다. 배포 컨테이너와 `.env`의 base URL 역시 구 주소다.
외부 프록시에는 `41-job-donyk.conf`가 있고 새 인증서로 같은 앱 upstream을 제공한다. 이 파일의 `.pending` 주석은 실제 활성 상태와 달라 구현 시 운영 문서와 함께 정리한다.

현재 새 도메인 스모크: `timeout 55s bash scripts/smoke.sh https://job.donyk.link`, 실제 종료코드 **1**(시간 초과가 아님). issuer·authorization endpoint·token endpoint·resource·Google redirect_uri·새 MCP resource를 보낸 authorize 진입점의 Location, **6개 실패**다. HTTP 200만으로 연동 성공을 판단할 수 없다.

## 2. URL 계약

아래 값은 구현 후 기대값이며, 현재 배포값이 아니다. origin에는 끝 `/`를 두지 않는다.

| 대상 | 기대값 |
|---|---|
| `PUBLIC_BASE_URL`, OAuth `issuer` | `https://job.donyk.link` |
| Google 로그인 시작 | `https://job.donyk.link/oauth2/authorization/google` |
| Google Console에 등록할 callback | `https://job.donyk.link/login/oauth2/code/google` |
| MCP URL, 보호 리소스 `resource`, 토큰 metadata의 `aud` | `https://job.donyk.link/mcp` |
| OAuth authorization / token | `https://job.donyk.link/oauth2/authorize` / `https://job.donyk.link/oauth2/token` |
| OAuth revoke / introspect | 동일 origin의 `/oauth2/revoke` / `/oauth2/introspect` |
| OAuth 검색 문서 | `https://job.donyk.link/.well-known/oauth-authorization-server` |
| 보호 리소스 검색 문서 | `https://job.donyk.link/.well-known/oauth-protected-resource` 및 기존 `/mcp` 접미 별칭 |
| `authorization_servers` | `["https://job.donyk.link"]` |
| `resource_documentation` | `https://job.donyk.link/plugin` |
| challenge의 `resource_metadata` | `https://job.donyk.link/.well-known/oauth-protected-resource` |
| `/plugin/config`와 다운로드 ZIP | MCP·인증·토큰 주소가 위 값과 동일 |
| GitHub `.mcp.json` | `mcpServers.jobsight.url = https://job.donyk.link/mcp` |
| ChatGPT로 돌아가는 callback | **ChatGPT 연결 관리 화면의 정확한 HTTPS URI**; JobSight 도메인으로 바꾸지 않음 |

Google callback은 Google Console에, ChatGPT callback은 CIMD 검증 또는 `MCP_REDIRECT_URIS`에 속한다. 서로 다른 OAuth 클라이언트다. 기존 Google client ID·secret은 새 도메인이 같은 클라이언트에 등록됐다면 재사용한다.

현재 CIMD는 callback별 `https://chatgpt.com/oauth/{id}/client.json` 및 고정 문서 경로를 제한적으로 지원한다. 기본 연결은 CIMD, 기존 `jobsight-plugin` 연결은 public client/none + 정확한 callback allowlist로 유지한다. 새 연결을 만들면 callback ID가 달라질 수 있으므로 과거 대화의 ID를 재사용한다고 가정하지 않는다.

현재 공개 OAuth metadata에는 `authorization_response_iss_parameter_supported` 선언이 없다. 이번 전환에 RFC 9207 구현을 묶지 않는다. 성공·오류 응답 모두의 `iss`를 검증하지 않고 이 플래그만 true로 켜거나 고정 callback을 강제하지 않는다. [공식 인증 계약](https://developers.openai.com/plugins/build/auth)의 callback·issuer·resource 요구사항을 따른다.

## 3. 로그인과 데이터·토큰 처리

### Google·브라우저

1. 새 origin에서 로그인 시작 → 동일 origin의 host-only 세션 쿠키 생성 → Google로 이동한다.
2. Google은 새 callback으로 복귀한다. 같은 host의 쿠키로 저장된 요청을 찾는다.
3. 일반 로그인은 `/`, MCP에서 시작했다면 새 origin의 `/oauth2/authorize`로 복귀한다. 사용자 입력 redirect URL을 목적지로 채택하지 않는다.
4. 기존 Google sub·사용자 UUID·업무 데이터는 그대로 사용한다. 쿠키는 도메인을 넘을 수 없으므로 재로그인이 정상 절차다.

현재는 새 도메인에서 시작하고 구 callback으로 돌아가므로 세션 쿠키가 누락되어 `authorization_request_not_found` 등이 날 수 있다. 이는 코드·쿠키 구조로부터의 추론이며 실제 Google 왕복 오류는 이번 조사에서 재현하지 않았다.

### MCP·OAuth

1. 새 MCP URL로 연결 → metadata 검색 → 새 resource를 지정한 authorization code + S256 PKCE → Google 로그인 → JobSight 동의 → ChatGPT/Codex callback 순서다.
2. 새 access token의 `aud` metadata는 새 MCP resource와 정확히 일치한다. 현재 토큰은 JDBC에 저장되는 opaque/reference token이며 JWT 서명키 교체를 요구하지 않는다.
3. 구 access token은 새 resource에서 401 + 새 metadata challenge로 거부한다. 구 audience를 허용 목록에 추가하지 않는다.
4. **기존 refresh의 audience 승계를 명시적으로 차단한다.** 현재 resource 필터는 새 요청의 resource만 확인하고, refresh generator는 실행 시점의 resource로 access token을 만든다. 따라서 base URL 변경만으로는 구 refresh의 무효화를 보장할 수 없다.
5. `McpOAuthConfig`의 refresh 처리에서 토큰 생성 전 저장된 승인 행의 access-token claims `aud`가 현재 resource에 해당하는 승인인지 검증한다. refresh는 access token 만료 이후에도 가능하므로 만료 여부가 아니라 저장된 audience를 검사한다. 구 값·없음·잘못된 자료형·다른 resource의 승인은 `invalid_grant`로 거부하고 회전/신규 발급하지 않는다. 요청 자체의 잘못된 resource는 기존 `invalid_target` 검증을 유지한다.
6. 전환 직전에 구 origin에서 발급한 인가 코드도 새 token endpoint로 승계하지 않는다. 코드 교환 시 저장된 `OAuth2AuthorizationRequest`의 원래 resource와 현재 resource를 대조하고, 다르거나 누락됐으면 `invalid_grant`로 거부한다. 요청의 resource만 검사해 구 코드가 새 audience 토큰으로 바뀌는 틈을 막는다.
7. 새 audience refresh는 기존 90일 정책·회전·이전 refresh 재사용 거부를 유지한다. client 인증 방식 변경 없이 code/refresh grant 검증 경계에 가드를 넣는다.
8. 기존 승인·동의·사용자·브라우저 refresh 행은 일괄 삭제하지 않는다. DB 스키마 변경도 없다. 같은 클라이언트의 기존 동의가 남는 것은 허용하되 새 도메인에 유효한 인증 코드는 다시 발급받는다.

Host-only cookie·Secure=true·SameSite=Lax·REST CSRF·MCP Bearer 전용·소유자 404·ADMIN의 scope와 DB 역할 이중 검사를 유지한다. MCP Origin은 새 origin만 허용하며 Origin이 없는 서버 간 Bearer 호출은 기존 방식으로 처리한다. CORS 와일드카드나 쿠키 Domain 확대는 해결책으로 사용하지 않는다.

## 4. 구 도메인 정책

기본안은 단일 origin 전환 + 명시적 재연결이다. 두 issuer를 병행 운영하거나 무중단 토큰 이전은 이번 설계에 포함하지 않는다. 아래 정책은 `40-job.conf`에만 적용한다. 새 vhost도 사용하는 `inc/job-body.inc`를 통째로 바꾸지 않는다.

| 구 도메인 요청 | 전환 후 처리 |
|---|---|
| `/health`, ACME challenge | 기존 점검·인증서 갱신 유지 |
| 사이트 화면 `/`, `/plugin`, 업무 목록 등과 Google 로그인 **시작**의 GET/HEAD | 고정된 새 origin의 대응 경로로 302. 허용된 화면 검색 인자만 유지하고 OAuth code/state/token은 전달하지 않음 |
| `/login/oauth2/code/google` | 전환 전에 시작한 세션은 완료를 보장하지 않음. callback query를 버리고 `https://job.donyk.link/?authError=DOMAIN_CHANGED`로 302 후 재로그인 안내 |
| `/.well-known/oauth-*` | 전환기에는 새 canonical 값의 JSON 제공. 구 issuer를 광고하지 않음 |
| `/mcp` | 401 + 새 `resource_metadata` challenge. Bearer를 새 host로 30x 전달하지 않음 |
| `/oauth2/authorize`, `/oauth2/token`, `/oauth2/revoke`, `/oauth2/introspect` | 400 `invalid_target`; code·POST body를 새 host로 전달하지 않음 |
| 나머지 `/api/`·비안전 메서드 등 | 410 JSON과 새 주소 안내. 자동 재전송 금지 |

프록시의 도메인 이동 목적지는 고정 문자열 `https://job.donyk.link`로 쓴다. 요청 Host·forwarded 헤더로 목적지를 조립하지 않는다. 화면 경로 allowlist 밖의 요청은 프로토콜 경로보다 앞선 catch-all redirect에 잡히지 않게 한다. 새 `DOMAIN_CHANGED` 메시지는 기존 LoginView의 오류 계약에 추가한다.

## 5. 변경할 파일과 이유

| 영역 | 예정 변경 |
|---|---|
| 배포 `.env` (커밋 제외) | `PUBLIC_BASE_URL=https://job.donyk.link`, secure=true 유지; 실제 연결 callback 확인 |
| `compose.yaml`, `compose.mcp.yaml` | 현재 env 전달 구조 재사용. 키를 추가해야 할 근거는 없음; 최종 구성의 비밀값 제외 확인 |
| `.env.example` | 로컬 기본값 유지, 공개 배포 예시만 새 주소로 변경. compose·실제 `.env`·local-dev 문서와 동기 확인 |
| `plugins/jobsight/.mcp.json` | 새 공개 MCP URL로 교체 |
| `plugins/jobsight/.codex-plugin/plugin.json` | 배포 식별용 patch 버전 `0.3.0 → 0.3.1`; 구현 시 현재 버전 재확인 |
| `backend/.../mcp/McpOAuthConfig.java` | 위 code/refresh resource 가드 및 테스트를 추가. Google redirect·issuer 생성은 기존 설정 기반 코드 재사용 |
| `frontend/src/components/LoginView.vue` | `DOMAIN_CHANGED` 오류 문구. API base URL 하드코딩은 추가하지 않음 |
| `frontend/src/components/PluginGuide.vue` | 이전 연결은 새 URL로 재연결해야 한다는 짧은 안내. 현재 config 응답 재사용 |
| `frontend/src/routes.ts`, `ApiPaths.java`, 내부 nginx | 경로 신설 계획 없음. 경로 추가가 필요해지면 세 곳 동시 갱신 |
| 기존 MCP 테스트 + 새 도메인 회귀 테스트 | Google redirect·metadata·challenge·Origin·ZIP·구 code/refresh resource 거부 |
| `scripts/smoke.sh` | 사용 예시 새 주소, public config의 세 URL·challenge metadata·token auth none 검사 보완; host-only/Secure 검사 |
| `docs/local-dev.md`, `docs/nginx-deploy.md`, `docs/mcp-plugin.md`, `AGENTS.md` | 정식 운영 주소·전환·재연결·검증 결과 기록. 역사적 결정/과거 검증의 구 주소는 보존 |
| 저장소 밖 `/home/quincy/HDD/apps/reverse-proxy/conf.d/40-job.conf`, `41-job-donyk.conf`와 README | 구 주소 정책·새 vhost 활성 상태 문서화. 실제 구현 시 이 워크스페이스 밖 쓰기 권한 필요 |

백엔드 경로의 `...`는 `src/main/java/com/jobsight/company`를 뜻한다. 계획·설계 단계에서는 위 파일을 구현 변경하지 않는다.

## 6. 구현·배포·롤백 순서

1. 현재 커밋·정확한 코드 diff·배포 이미지 ID·비밀값 제외 base/callback 설정과 두 vhost를 기록한다. 운영 DB나 토큰 원문은 채팅·문서·Git에 저장하지 않는다.
2. 운영 컨테이너를 바꾸지 않는 테스트 컨테이너에서 새 HTTPS base URL 계약·구 refresh 거부·로컬 loopback 호환을 검증한다. 배포용 build와 smoke 개선도 이 단계에서 준비한다.
3. 새 도메인 TLS·두 metadata 경로 접근·Google Console callback 등록을 대조한다. Google Console 등록은 사용자 완료로 보고 값의 일치만 검증한다.
4. 짧은 전환 창에 구 vhost 정책과 새 base URL을 함께 반영한다. 프로토콜 경로는 일시적인 503 점검 창을 사용할 수 있고, 구 audience refresh 가드 없는 버전으로 base URL을 먼저 전환하지 않는다. 프록시 `nginx -t` 후 reload한다.
5. 백엔드와 플러그인 리소스를 재빌드·재생성한다. 프런트 안내를 변경했다면 프런트도 배포한다. 이 시점은 이미 공개 노출이며 운영 환경을 사전 테스트라고 부르지 않는다.
6. 컨테이너 healthy·로그 확인 후 새 origin smoke와 구 주소 정책 검증을 수행한다. `PUBLIC_BASE_URL` 변경이 컨테이너 환경까지 반영됐는지 읽어 확인한다.
7. 새 origin이 정상일 때 GitHub 플러그인 배포를 반영한다. GitHub 설치 사용자는 marketplace와 플러그인을 갱신하고 새 연결을 인증한다. 새 ZIP도 같은 URL인지 확인한다.
8. 브라우저 Google 왕복 → ChatGPT 새 MCP 연결 → Codex 갱신·OAuth → 읽기 도구 → refresh 회전 순서로 검증한다. 기존 연결의 URL 편집을 지원하지 않으면 새 연결을 만들어 성공 확인 후 구 연결을 제거한다. 사용자 대신 연결을 삭제하지 않는다.
9. 롤백이 필요하면 이전 이미지·구 base URL·구 vhost·플러그인 주소를 한 세트로 복원한다. 구 인증서와 Google 구 callback은 전환 검증 전 제거하지 않는다. 새 audience 토큰도 구 서버에서 거부되므로 롤백 시 재연결이 필요하다. 승인 행을 보존하므로 구 연결이 다시 유효해질 가능성도 함께 공지한다.

## 7. 테스트와 완료 조건

| 단계 | 반드시 확인할 계약 |
|---|---|
| Google 설정 테스트 | 새 HTTPS base에서 redirect_uri가 정확한 새 callback. Host/forwarded 헤더 변조가 값을 바꾸지 않음. 로컬 기본값 유지 |
| MCP 공개 응답 | 두 resource metadata 경로·OAuth metadata·`/plugin/config`·ZIP·challenge의 URL 일치, JSON content-type, S256·none·scope 유지 |
| 승인·토큰 | 새 resource code 교환·JDBC 재조회·도구 목록·refresh 회전 성공, 구 audience access 401, 구 code/refresh `invalid_grant`, code 재사용 거부 |
| 브라우저 경계 | 새 Origin 정상 처리, 다른 Origin 403, 새 세션 쿠키 host-only·Secure·Lax, CSRF 없는 REST 변경 403 |
| 프록시 | 구 주소 화면의 고정 목적지 302, callback code/state 미전달, token POST·Bearer 외부 redirect 없음, metadata JSON, 알 수 없는 Host가 URL을 바꾸지 않음 |
| 프런트 | 새 URL 표시·복사·재연결 안내·DOMAIN_CHANGED 표시. config 실패 시 주소 추측 없이 재시도 |
| 실제 사용 | Google 로그인/로그아웃/세션 갱신, ChatGPT 액션 새로 고침·계정/기업 목록, Codex 도구 조회, 동일 계정 데이터 유지 |

구현 완료 시 필수 명령은 컨테이너의 `./gradlew compileJava compileTestJava test --no-daemon`(전체 통합 테스트는 Docker 소켓 포함), 프런트 변경 시 `npm run type-check && npm test`, 프록시 `nginx -t`, `bash scripts/smoke.sh https://job.donyk.link`다. 종료코드와 Gradle `BUILD SUCCESSFUL`을 모두 확인한다. 설치된 플러그인의 실제 MCP URL·OAuth 연결과 재시작 후 토큰 유지도 기록한다.

새 도메인 baseline의 6개 실패가 사라지고 추가 회귀 검사·실제 로그인·클라이언트 검증까지 끝나야 서비스 전환을 완료로 표시한다. 이번 단계는 설계 문서 작성만 완료한 상태이며, Google 실제 왕복·ChatGPT/Codex 새 도메인 연결·token 회전은 미검증이다.
