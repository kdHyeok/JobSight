# 새 도메인 OAuth·플러그인 연동 계획

작성: 2026-10-19 (KST) · 기능: `custom-domain-oauth` · 상태: 계획 완료, 구현 전

## 목적과 진입점

정식 공개 주소를 `https://job.donyk.link`로 통일해 Google 로그인과 ChatGPT/Codex MCP 연결이 같은 origin에서 동작하게 한다.
Google Console의 새 redirect URI 등록은 사용자 완료 사항이다. 등록값 자체와 실제 로그인 왕복은 아직 대조하지 않았다.
진입점은 Google 로그인, `/plugin`, `/mcp`, OAuth 검색 문서, GitHub 플러그인 설치다.

## 확인된 현재 상태

| 영역 | 2026-10-19 읽기 전용 확인 |
|---|---|
| 새 도메인 TLS·라우팅 | 인증서 검증을 통과해 `/health` 200, 기존 서비스에 연결됨 |
| 백엔드 배포 설정 | `PUBLIC_BASE_URL=https://job.donhse.duckdns.org`, secure cookie=true |
| Google 로그인 | 새 도메인에서 시작해도 `Location`의 redirect_uri는 구 도메인 callback |
| MCP 검색·가이드 | issuer·resource·인증/토큰 URL·`/plugin/config`가 모두 구 도메인 |
| GitHub 플러그인 | `plugins/jobsight/.mcp.json`이 구 도메인 `/mcp`를 고정 참조 |
| 두 프록시 vhost | `40-job.conf`와 `41-job-donyk.conf`가 동일 upstream을 공유 |

## 범위와 불변 조건

- 공개 origin의 정본은 기존 `PUBLIC_BASE_URL` 하나로 유지한다. Host/forwarded 헤더로 OAuth URL을 새로 만들지 않는다.
- 환경 설정·플러그인 배포 주소·운영 문서·도메인 회귀 테스트를 함께 갱신한다. 기존 상대 경로 API와 Google OIDC를 재사용한다.
- 쿠키는 host-only·Secure·SameSite=Lax를 유지한다. 도메인 사이 세션/쿠키/토큰을 복제하지 않는다.
- MCP는 PKCE S256·정확한 resource·scope·현재 계정 상태·소유자 격리를 유지한다.
- 기존 플러그인은 새 MCP URL로 재연결한다. 구 audience access token과 refresh token의 무음 승계를 막는다.
- 구현 시 구 도메인은 사이트 진입 안내만 새 주소로 보내고, OAuth callback·Bearer·token POST를 도메인 간 전달하지 않는다.
- 이번 산출물은 계획·설계 문서다. 코드·`.env`·프록시·배포·사용자 연결은 변경하지 않는다.
- DCR 도입, Google 클라이언트 재발급, DB 구조 변경, 저장소 공개 전환, Codex 원격 제어는 범위 밖이다.

## 실행 순서

| 단계 | 작업 | 완료 기준 |
|---|---|---|
| 1. 계획·설계 | 현재 동작, URL 계약, 구 연결 처리, 검증·롤백 정의 | 이 계획과 연결된 설계 문서 작성 |
| 2. 구현 | 환경·정적 MCP URL·플러그인 버전·구 refresh 차단·도메인 테스트 | 격리된 테스트 통과, 비밀값 없는 diff |
| 3. 전환 | 새 origin 반영과 구 vhost 진입 정책을 같은 작업 창에 적용 | 컨테이너 healthy, 새 도메인 스모크 PASS |
| 4. 실제 인증 | Google 왕복, ChatGPT/Codex 재연결·조회·갱신 | 각 클라이언트의 실제 성공 확인 |
| 5. 기록 | 운영 문서와 검증 결과 갱신 | 미검증·롤백 영향 구분 |

구현·배포 일정은 미정이다. 의존 순서는 위와 같고, 실제 로그인·클라이언트 동의에는 사용자 조작이 필요할 수 있다.

## 성공 기준과 주요 위험

- [ ] 새 도메인의 Google callback, issuer, resource, challenge, 가이드, ZIP, GitHub 플러그인 주소가 일치한다.
- [ ] 새 도메인 스모크가 PASS이고 Google 실제 로그인 및 플러그인 도구 조회·토큰 회전이 성공한다.
- [ ] 구 audience access/refresh 토큰은 새 resource에서 거부되고, 새 연결은 재시작 후에도 유지된다.
- [ ] 구 도메인에서 새 로그인 세션이 만들어지지 않으며 프로토콜 요청의 외부 리다이렉트·자격증명 전달이 없다.
- 도메인 이동 후 기존 쿠키는 따라오지 않아 재로그인이 필요하다. 같은 Google sub의 기존 계정·데이터를 재사용한다.
- PUBLIC_BASE_URL만 바꾸면 기존 refresh token이 새 audience 토큰으로 재발급될 수 있다. 전환 전 거부 테스트·가드를 추가한다.
- 이 서버의 컨테이너 재생성은 즉시 공개 서비스에 반영된다. 테스트는 운영 Compose 재생성 전에 격리 실행한다.
- 롤백은 구 base URL·구 vhost·이전 플러그인 설정을 함께 복원한다. 데이터·OAuth 승인 행을 일괄 삭제하지 않는다.

## 문서

[상세 설계](../../02-design/features/custom-domain-oauth.design.md) · [MCP 계약](../../mcp-plugin.md) · [배포](../../nginx-deploy.md) · [세션](../../auth-session.md)
