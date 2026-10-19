# 새 도메인 배포 결과

작성: 2026-10-19 · 기능: `custom-domain-oauth` · 상태: **Partial — 배포·Google 로그인 완료, 플러그인 실제 연결 검증 대기**

## 완료한 변경

- 정식 origin을 `https://job.donyk.link`로 전환했다. Google callback·MCP issuer/resource·가이드·ZIP·GitHub 패키지 주소가 같다.
- 구 인가 코드·refresh가 새 resource 토큰으로 승계되지 않도록 검증하고 회귀 테스트를 추가했다.
- 구 도메인은 안전한 화면 진입만 이동시킨다. Bearer·token POST·Google callback code/state는 도메인 사이 전달하지 않는다.
- 패키지 0.3.1, 재연결·DOMAIN_CHANGED 안내, 운영 문서를 반영했다. 업무 데이터·기존 승인 행을 삭제하지 않았다.
- 원격 main의 최신 직무 삭제·헬스체크 변경을 보존해 병합했다. 구현 `6138d0b`, 배포·push `8222ede`.
- Google Console 재등록 후 사용자의 실제 Google 로그인·홈 복귀를 서버 로그와 함께 확인했다.

## 검증

백엔드 74개, 프런트 79개 테스트와 타입 검사·빌드 통과. 새 도메인 스모크 59개, 구 도메인 정책 26개 PASS.
명령의 실제 종료코드 0과 Gradle BUILD SUCCESSFUL을 확인했다. Compose 3개 컨테이너 healthy, DB는 기존 Flyway v21을 유지한다.

이 결과는 실제 ChatGPT/Codex 연결 성공을 의미하지 않는다. 브라우저 `/mcp` 직접 접속의 401은 정상이다.
새 URL로 OAuth·권한 동의를 완료한 클라이언트에서 계정·기업 조회, 실제 refresh, 재시작 후 연결 유지는 아직 검증하지 못했다.

## 사용자가 이어서 할 일

- Codex Git 마켓플레이스 설치: `codex plugin marketplace upgrade jobsight` 후 `codex plugin add jobsight@jobsight`,
  앱 완전 종료·재실행 → 새 대화의 JobSight 연결·권한 동의. 마켓플레이스 삭제·재등록은 필요 없다.
- `jobsight` 마켓플레이스가 없다는 오류라면 먼저 `codex plugin marketplace add kdHyeok/jobsite-v006 --ref main`으로 등록한다.
- ChatGPT 웹: 플러그인의 사용자 지정 MCP 서버 추가에서 `https://job.donyk.link/mcp`, OAuth·CIMD로 연결한다.
- 새 연결이 확인되기 전 기존 연결을 삭제하지 않는다. 실제 마지막 로그인·권한 동의는 사용자가 수행한다.

## 운영·롤백

이전 backend·frontend 이미지는 `:pre-domain-20261019` 태그로 보존했다. 이전 앱 env와 두 vhost의 백업도
서버의 제한된 임시 백업 디렉터리에 보관했다. 되돌릴 때는 이미지·env·프록시·정적 패키지 URL을 함께 복원한다.
스키마 변경은 없다. 롤백하면 새 audience 토큰도 구 origin에서 거부되므로 재연결이 필요하며,
남겨 둔 구 승인 행이 다시 유효해질 수 있다. 구 인증서와 Google 구 callback은 제거하지 않았다.

## 관련 문서

[계획](../01-plan/features/custom-domain-oauth.plan.md) · [설계](../02-design/features/custom-domain-oauth.design.md) ·
[검증 분석](../03-analysis/custom-domain-oauth.analysis.md) · [연결 가이드](../mcp-plugin.md)

다음 단계: 사용자 클라이언트 새 연결과 읽기 도구 호출 확인. PDCA는 검증 중이며 기능 전체 완료로 표시하지 않는다.
