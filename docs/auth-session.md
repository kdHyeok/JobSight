# 로그인 세션과 리프레시 토큰

## 목적

브라우저 세션은 30분 동안 사용하지 않으면 만료한다. 사용자가 다시 Google 로그인을 하지 않아도 로그인 상태를 이어갈 수 있도록, 별도의 JobSight 리프레시 토큰으로 새 세션을 발급한다.

## 진입점

- Google 로그인 성공: 리프레시 토큰 발급
- `POST /api/auth/refresh`: 유효한 리프레시 토큰을 회전하고 새 브라우저 세션 발급
- `POST /api/auth/logout`: 현재 리프레시 토큰 폐기와 쿠키 삭제
- 프런트 `src/api/http.ts`: API 401 시 한 번만 refresh 후 원래 요청 재시도

## 불변 조건

- 사람의 신원 확인은 계속 Google OIDC 하나다. 이 토큰은 Google 토큰이 아니라 JobSight가 발급한 opaque 토큰이다.
- 원문 토큰은 `HttpOnly`, `SameSite=Lax`, `/api/auth` 경로의 쿠키에만 두고 DB에는 SHA-256 해시만 저장한다.
- 토큰은 사용할 때마다 회전하고 최초 발급부터 30일 뒤 절대 만료한다. 여러 기기 로그인은 각각 독립 토큰을 가진다.
- ACTIVE 계정만 갱신할 수 있다. 정지·거절·승인 대기 계정은 기존 토큰이 있어도 새 세션을 받지 못한다.
- refresh는 CSRF 보호를 받는다. MCP OAuth 토큰·scope·Bearer 체인과 섞지 않는다.
- 로그아웃은 브라우저 세션과 현재 리프레시 토큰을 함께 폐기한다.
- 공개 origin은 `https://job.donyk.link`다. host-only 쿠키를 구 도메인에서 복제하거나 Domain 범위를 넓히지 않는다.
  구 도메인에서 시작한 Google callback은 쿼리를 버리고 새 주소의 `DOMAIN_CHANGED` 안내로 보낸다. 기존 Google 계정으로 다시 로그인한다.

## 실행·검증

1. 컨테이너에서 `./gradlew compileJava compileTestJava`를 실행한다.
2. 프런트에서 `npm run type-check && npm test`를 실행한다.
3. Compose 재빌드 뒤 backend 로그에서 Flyway `now at version v17`을 확인한다.
4. `bash scripts/smoke.sh`가 `PASS`인지 확인한다.
5. 브라우저에서 Google 로그인 왕복을 한 번 확인한다.

## 함정

- `SecurityContextHolder`에 인증만 넣으면 다음 요청에 남지 않는다. refresh 컨트롤러는 `SecurityContextRepository.saveContext`를 호출해야 한다.
- 리프레시 토큰 만료를 사용할 때마다 30일 뒤로 미루면 탈취 토큰이 영구 생존할 수 있다. 회전해도 최초 `expires_at`은 보존한다.
- 로그인 직후 CSRF 쿠키가 회전하므로 프런트는 항상 현재 `XSRF-TOKEN`을 읽어 refresh 요청에 보낸다.
