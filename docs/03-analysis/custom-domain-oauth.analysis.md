# 새 도메인 OAuth·플러그인 검증 분석

작성: 2026-10-19 · 기능: `custom-domain-oauth` · 상태: 실제 클라이언트 인증 검증 중

근거: [설계](../02-design/features/custom-domain-oauth.design.md). 대상은 `McpOAuthConfig`, 도메인 회귀 테스트,
로그인·플러그인 안내, 공개 패키지와 신·구 도메인 프록시다. 구현 `6138d0b`, 원격 변경 통합·배포 `8222ede`.

## 설계 대조

| 계약 | 확인 근거 | 상태 |
|---|---|---|
| 하나의 canonical origin | 런타임 env, Google Location, metadata·challenge·config·ZIP·GitHub가 새 주소 | 일치 |
| Google 실제 로그인 | Console callback 재등록 후 사용자 성공 확인; callback 302 → 홈 계정 조회 200 | 확인 |
| code·refresh resource 경계 | 새 code/refresh 성공, 구·누락 resource와 잘못된 aud 거부, 회전·재사용 거부 테스트 | 일치 |
| 쿠키·Origin·CSRF | host-only·Secure·Lax, 새 Origin 허용·다른 Origin 거부, 기존 CSRF 검사 | 일치 |
| 구 주소 자격증명 미전달 | 화면 GET/HEAD만 query 없는 고정 302, callback 쿼리 폐기, OAuth 400·MCP 401·API 410 | 일치 |
| 안내·패키지 | DOMAIN_CHANGED 문구와 재연결 안내 테스트, 패키지 0.3.1·새 URL | 일치 |
| 운영 배포·보존 | 3개 healthy, Flyway v21 유지, 원격 main 병합·push, 데이터·승인 행 삭제 없음 | 확인 |
| ChatGPT/Codex 새 OAuth와 읽기 도구 | 사용자 클라이언트에서 새 URL로 연결·동의 필요 | 미검증 |
| 실제 클라이언트 refresh·재시작 후 유지 | JDBC 통합 테스트는 통과, 운영 새 연결 확보 전 | 미검증 |

구현상 누락은 발견하지 않았으나 실제 사용 2개 항목을 자동 테스트로 대체할 수 없다. 전체 완료율을 100%로 표시하지 않는다.

## 실행 결과

| 검증 | 결과 |
|---|---|
| 컨테이너 `./gradlew compileJava compileTestJava test --no-daemon` | exit 0, BUILD SUCCESSFUL, 74개 실패·오류 0 |
| 프런트 `npm run type-check && npm test && npm run build` | exit 0, 20개 파일·79개 테스트 통과 |
| 새 주소 `bash scripts/smoke.sh https://job.donyk.link` | exit 0, PASS 59개 |
| `bash scripts/smoke-legacy-domain.sh` | exit 0, PASS 26개 |
| Compose 이미지 빌드·기동, 프록시 `nginx -t`·reload | exit 0, backend·frontend·db healthy |
| 실제 Google 진입 | redirect_uri 일치; 익명 Google 요청은 오류 없이 로그인 입력 화면까지 도달 |

## 오류 판별과 남은 작업

1. 최초 사용자 오류는 Google `redirect_uri_mismatch`였다. 실제 앱 Location은 새 callback이었다.
   사용자가 Google Console 등록값을 수정한 뒤 사이트 로그인 성공을 확인했다. 서버 코드를 되돌릴 이유는 없다.
2. 이후 플러그인 오류로 알려준 401은 브라우저의 `/mcp` 직접 접속이었다. 세션을 허용하지 않는 정상 동작이다.
3. 이 대화에 노출된 기존 두 연결의 계정 조회 호출은 실패했다. 새 URL 연결 성공의 증거로 사용하지 않는다.
4. Codex Git 마켓플레이스는 최신 패키지를 갱신하고 앱을 재시작한 뒤 OAuth에 동의한다.
   ChatGPT 웹 사용자 지정 MCP 연결은 이와 별도다. 기존 연결은 새 연결 성공 후 사용자가 정리한다.
5. 새 연결 후 계정·기업 목록을 읽기 전용으로 확인하고, 실제 refresh와 재시작 후 유지를 후속 검증한다.

토큰·쿠키·OAuth code/state는 보고서에 기록하지 않는다. 쿼리는 로그 확인 시 가린다.
