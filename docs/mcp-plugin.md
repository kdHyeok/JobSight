# JobSight MCP / OAuth 플러그인

## 목적과 진입점

새 도메인 `job.donyk.link` 전환은 [계획](01-plan/features/custom-domain-oauth.plan.md)과
[설계](02-design/features/custom-domain-oauth.design.md)를 따른다. 공개 MCP URL은 `https://job.donyk.link/mcp`다.
GitHub 플러그인 0.3.1은 이 URL을 사용한다. 구 주소의 플러그인 연결은 새 주소로 재인증해야 한다.

`plugins/jobsight`를 ChatGPT/Codex용 플러그인으로 패키징한다. MCP는 기존 Spring 애플리케이션의
`/mcp`에서 JSON 응답형 Streamable HTTP로 실행하고, 기존 CRUD 컨트롤러·서비스와 DTO 검증을 재사용한다.
도구 설명은 기존 `@Operation`, 입력 스키마는 실제 요청 DTO에서 만든다. `SKILL.md`는 사이트 조작과 데이터 관계를 설명한다.

## 인증과 불변 조건

- Codex native CIMD는 `https://chatgpt.com/oauth/codex/<id>/client.json`만 추가 허용한다.
  공개 문서의 `application_type=native`, 해당 ID의 `http://127.0.0.1/callback/<id>`와
  `http://localhost/callback/<id>` 두 콜백을 검증한다. loopback IP의 임시 포트는 Spring 기본 검증에 맡기며
  다른 호스트·경로·쿼리로 허용 범위를 넓히지 않는다. PKCE와 사용자 동의는 그대로 강제한다.
  설치만으로 인증되지 않으며 `codex mcp login jobsight --oauth-client-registration cimd`가 필요하다.
  플러그인 MCP URL은 현재 공개 설정과 같은 `https://job.donyk.link/mcp`를 사용한다.
- 사용자 신원은 기존 Google OIDC만 사용한다. MCP OAuth는 그 사용자가 외부 클라이언트에 주는 위임이다.
- Spring Authorization Server의 authorization code + S256 PKCE + 동의 화면을 사용한다.
- ChatGPT CIMD 문서 URL을 client_id로 지원한다. HTTPS의 고정 chatgpt.com 문서 경로만 조회하고 리다이렉트·임의 호스트·과대 응답은 거부한다. 문서의 client_id, callback, none 인증과 authorization_code를 검증하며 PKCE·동의를 강제한다.
- 기존 `jobsight-plugin` 사전 등록 연결은 유지한다. DCR은 제공하지 않는다.
- `/mcp`는 세션 쿠키를 받지 않는 Bearer 전용 체인이다. 기존 REST/Google 세션의 CSRF는 유지한다.
- 매 MCP 요청에서 계정 활성 상태·현재 역할을 DB에서 다시 확인한다. 도구는 `jobsight.read`,
  `jobsight.write`, 관리자 작업은 추가로 `jobsight.admin` 및 실제 ADMIN 역할을 요구한다.
- resource는 `PUBLIC_BASE_URL + /mcp` 하나다. OAuth 토큰은 이 서버에서만 유효하며 Google 토큰을 전달하지 않는다.
- code 교환은 저장된 인가 요청의 resource, refresh는 저장된 access token metadata의 단일 aud를 검사한다.
  현재 resource와 다르거나 누락된 구 연결은 `invalid_grant`로 거부한다. 만료된 access token의 정상 refresh는 허용하며,
  도메인 전환을 이유로 기존 승인·동의 행이나 업무 데이터를 일괄 삭제하지 않는다.
- OAuth 승인·토큰은 Spring JDBC 저장소로 PostgreSQL에 보관한다. access token은 1시간,
  회전형 refresh token은 90일이며 서버 재시작 후에도 연결을 유지한다. 계정 정지·삭제 시에는 남은 토큰 기간과 무관하게 MCP 요청을 거부한다.
- OAuth 승인 행에는 Google `AppOidcUser` 객체를 직렬화하지 않는다. 인가 요청 동안에는 사용자 ID와 권한만
  가진 Spring 기본 principal로 축약한다. 그렇지 않으면 동의 POST에서 JDBC 행을 읽을 때 Jackson 허용 목록에
  의해 역직렬화가 거부되어 500이 난다.
- access token의 `aud` claim은 `ArrayList`로 저장한다. `List.of()`의 런타임 타입인
  `java.util.ImmutableCollections$List12`도 같은 Jackson 허용 목록에서 거부되므로 사용하지 않는다.
- 기업·공고 삭제 cascade, 마지막 직무 삭제 시 소속 공고 cascade, 참고 정보 공유, 소유자 404 규칙은 기존 서비스가 담당한다.
- 기업·공고·직무의 `memo`는 렌더링된 HTML이 아닌 Markdown 원문으로 조회·저장한다.

## 실행·검증

원격 main의 공개 도메인 배포 설정과 MCP 변경을 함께 유지한다. 배포 검증은 HTTP 200만 보지 않고
OAuth 메타데이터의 issuer, 인증/토큰 URL, S256 선언과 MCP resource 값까지 확인한다.
사이트 HTML을 반환하는 이전 서버는 통과할 수 없어야 한다.

`./gradlew compileJava compileTestJava`, MCP 인증/도구 테스트,
프런트 타입·테스트, `scripts/smoke.sh`를 실행한다. 실제 Google 왕복과 ChatGPT 등록 여부는 별도로 기록한다.

## 함정

사이트에서 Google 로그인한 뒤 브라우저로 `/mcp`를 직접 열어도 401이 정상이다. MCP는 세션 쿠키가 아닌
클라이언트가 OAuth로 받은 Bearer 토큰을 요구한다. 직접 접속 결과를 플러그인 연결 실패로 판정하지 않는다.
Google `redirect_uri_mismatch`가 표시되면 시작 응답의 `Location`에서 실제 callback을 확인하고 같은 Google
client ID의 Console 등록값을 대조한다. Google 등록값만 수정했다면 서버 재배포는 필요 없다.

로컬 loopback·tailnet 전용 주소는 ChatGPT 클라우드에서 바로 접근할 수 없다. 공개 HTTPS 배포 또는
클라이언트가 지원하는 사설 네트워크 연결이 필요하다. 등록 가능한 패키지 작성과 공개 배포/스토어 승인은 별개다.
공고 수정의 positions/steps, 직무 참고 연결은 전체 교체다. 변경 전에 조회하고 유지할 값을 포함해야 한다.

공식 근거: https://developers.openai.com/plugins/build/auth
및 https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/configuration-model.html

## 등록 방법

### URL 하나로 연결 (권장)

ChatGPT 개발자 모드에서 MCP URL만 입력하고 OAuth 등록 방식은 CIMD를 선택한다.
ID·시크릿·callback 서버 수동 등록은 필요 없다. Google 로그인과 권한 동의는 사용자가 직접 완료한다.
클라이언트 정책/버전에 따라 CIMD 선택이 필요할 수 있으며, 사이트가 ChatGPT의 설치 UI를 자동 조작하지 않는다.

사이트 상단 `메뉴` → `플러그인 연결 가이드`(`/plugin`)는 로그인 없이도 열 수 있다. 화면에는 MCP URL 복사,
ChatGPT 연결, Codex GitHub 설치·업데이트 명령만 제공한다. CIMD·scope·수동 OAuth 엔드포인트는 서버가 처리할 내부 설정이라 사용자 가이드에 노출하지 않는다.
표시 URL은 `/plugin/config`가 PUBLIC_BASE_URL로 내려주며 브라우저 Host로 조립하지 않는다.
SKILL.md 정본은 plugins/jobsight에 하나만 두고 Gradle 리소스에 포함한다. Docker named context로 공개 플러그인 파일만 전달한다.
MCP initialize.instructions와 resources/read에서도 동일 지침을 제공한다. 이는 클라이언트의 스킬 설치를 보장하는 기능은 아니다.
Codex는 GitHub marketplace에서 설치한다. `.agents/plugins/marketplace.json`이 `plugins/jobsight`의 MCP 설정과 스킬을 가리킨다. 설치 명령은 `codex plugin marketplace add kdHyeok/jobsite-v006 --ref main` 다음 `codex plugin add jobsight@jobsight`다. 최신 main 반영은 `codex plugin marketplace upgrade jobsight` 후 다시 plugin add 한다. 이 변경을 push한 이후 사용 가능하며 비공개 저장소는 Git 읽기 권한이 필요하다. ZIP 엔드포인트는 호환용으로만 유지하고 사이트에서는 GitHub 설치를 안내한다. 설치와 별개로 OAuth 동의는 필요하다.

검증: marketplace 식별자·플러그인 구조 검사 및 프런트 타입 검사·43개 테스트 통과. 실제 GitHub에서 설치와 Codex OAuth 연결은 push 전이므로 미검증이다. 메뉴 권한·닫기·가이드 전환과 설치 명령 복사는 프런트 테스트에 포함한다.

### 기존 사전 등록 연결 (수동 대안)

1. 서버 `PUBLIC_BASE_URL`을 실제 HTTPS origin으로 설정하고 Google Console callback도 같은 origin으로 맞춘다.
2. ChatGPT의 개발자용 앱/플러그인 연결에서 MCP URL을 `{PUBLIC_BASE_URL}/mcp`, 인증을 OAuth로 설정한다.
   사전 등록 client ID는 `jobsight-plugin`, 인증 방식은 public client(`none`), client secret은 없다.
   authorization code + PKCE(S256)를 사용한다. DCR/CIMD는 켜지 않는다.
3. 그 화면에 표시된 **정확한 callback URL**을 아래 `MCP_REDIRECT_URIS`로 설정해 재기동한다.
   기본값은 `https://chatgpt.com/connector_platform_oauth_redirect`지만 새 연결의 callback ID URL과 다를 수 있다.
   여러 클라이언트는 쉼표로 정확한 URI를 나열한다. 와일드카드와 동적 localhost 포트는 허용하지 않는다.

```powershell
$env:MCP_REDIRECT_URIS = 'https://chatgpt.com/connector/oauth/<등록 화면의 callback ID>'
docker compose -f compose.yaml -f compose.mcp.yaml up -d --build
```

4. `jobsight.read jobsight.write`를 요청하고 Google 로그인 및 JobSight 동의 화면을 완료한다.
   이 두 scope는 401의 `WWW-Authenticate` 힌트와 두 메타데이터가 함께 광고하므로 클라이언트가 알아서 요청한다.
   관리자 기능이 필요하면 `jobsight.admin`도 요청한다. scope만으로 관리자 권한이 생기지 않는다.
5. `account_get`, 기업 목록부터 확인한다. 쿠키/토큰을 사람이 복사하는 방식으로 연결하지 않는다.
   access token은 1시간 유효하며 클라이언트가 90일 회전형 refresh token으로 자동 갱신한다.
   90일 동안 갱신하지 않았거나 사용자가 연결을 해제한 뒤에는 OAuth로 다시 연결한다.
6. 스킬 포함 플러그인 업로드가 가능한 클라이언트에는 아래 ZIP을 등록한다. MCP만 연결한 경우
   스킬은 자동 설치되지 않으므로 `plugins/jobsight/skills/jobsight/SKILL.md`도 해당 클라이언트의 스킬 기능으로 등록한다.

```powershell
python plugins/jobsight/package_plugin.py --base-url https://실제-서비스-도메인 --output artifacts/jobsight-plugin.zip
```

소스 `.mcp.json`은 공개 서비스 주소다. 패키저와 사이트 다운로드는 ZIP 안의 URL을 대상 서버 주소로 바꾼다.
ZIP 루트는 `.codex-plugin/plugin.json`, `.mcp.json`, `skills/jobsight/SKILL.md`다.
로컬 Codex는 이 폴더를 개발 플러그인으로 설치하거나 HTTP MCP 서버로 연결한다. 사전 등록 client ID와
클라이언트가 사용하는 고정 callback을 설정할 수 있어야 한다(CIMD 미지원 클라이언트). DCR 지원을 주장하지 않는다.
스토어 공개는 별도 심사·실제 공개 URL·개인정보처리방침 등 게시 요구사항을 충족한 후 진행한다.

## 도구 계약과 검증 범위

도구는 53개이며 입력은 `id`, `companyId`, `postingId`, `q`, `archived`, `seq`, `section`, `index`, `request`처럼 실제 메서드 인자를 사용한다.
도구명·동작별 사용법은 스킬에 있다. `/mcp`의 `tools/list`는 연결 계정의 scope/역할에 따라 목록을 줄인다.
관리자 도구 7개는 일반 사용자에게 노출하지 않으며 직접 이름을 호출해도 거부한다.
`posting_list`는 자동 보관을 일으켜 쓰기 scope와 readOnlyHint=false를 사용한다.
뉴스/유튜브·참고 상세 조회는 소유자 필터된 목록에서 ID를 찾는다.
MCP용 `position_create`는 기존 `PositionService` 트랜잭션에서 공고 소유권을 검증하고 직무를 추가한다.
이력서는 `resume_list/get/create/update/copy/delete`, 자기소개는 `self_intro_list/get/create/update/delete`로 전체 CRUD를 제공한다.
이력서 행 도구(`resume_basic_update`, `resume_row_add/update/delete`)는 REST 에 없는 MCP 전용이다. REST 는 문서 통째 PUT 만 있고,
MCP 는 문서 전체를 되돌려 보내기 어려워 서버가 조회→한 군데 수정→저장을 한 트랜잭션으로 묶는다(`ResumeService.mutateRows`).
행 입력 `ResumeRowRequest` 는 8개 섹션 필드의 합집합 record 다 — 스키마 생성기가 Map 을 그리지 못하고 섹션별 도구는 24개가 되기 때문.
서버가 `section` enum 의 record 로 엄격 변환해 다른 섹션 필드는 `UNKNOWN_ROW_FIELD` 로 거부한다. `index` 는 0부터.

인증 테스트는 외부 Google 자격증명 없이 인증된 세션을 주입하고 실제 Spring OAuth 필터의 코드 발급/교환을 검증한다.
실제 Google 왕복 및 ChatGPT 서비스 연결과 같다고 간주하지 않는다.
`McpOAuthJdbcIntegrationTest`는 PostgreSQL에서 실제 `AppOidcUser` 인가 행을 저장한 뒤 state로 다시 읽어,
동의 화면 다음 요청에서 principal 역직렬화가 실패하지 않는지 검증한다. 이어서 인가 코드 교환, JDBC에 저장된
access token을 사용한 MCP initialize와 실제 전체 `tools/list`, refresh token 교환까지 수행해 토큰 metadata와
Spring 프록시가 적용된 운영 빈의 도구 설명도 다시 읽을 수 있는지 확인한다.

### 2026-09-07 검증 결과

- 컨테이너에서 `compileJava compileTestJava` 및 MCP/서비스/컨트롤러 테스트 27개: 종료코드 0, `BUILD SUCCESSFUL`.
- 프런트 `npm run type-check`, `npm test`: 종료코드 0, 테스트 40개 통과.
- 재빌드한 Compose 컨테이너 3개 healthy, `bash scripts/smoke.sh`: 종료코드 0, 25개 검사 PASS.
- 플러그인·스킬 공식 구조 검사 통과. 로컬 ZIP은 `artifacts/jobsight-plugin-local.zip`에 생성했다.
- 실제 Google 로그인 왕복은 내장 브라우저의 로컬 연결 오류로 검증하지 못했다.
  ChatGPT 연결은 공개 HTTPS 주소와 정확한 callback 설정 전이므로 검증하지 못했다.

### 새 도메인 배포 검증 (0.3.1, 2026-10-19)

- `8222ede`를 새 공개 origin에 배포하고 main에 push했다. config·metadata·challenge·다운로드 ZIP·GitHub 패키지 주소가 일치한다.
- 컨테이너에서 백엔드 컴파일·전체 테스트 74개, 프런트 타입 검사·79개 테스트·빌드가 종료코드 0으로 통과했다.
- 새 도메인 스모크 59개, 구 도메인 정책 검사 26개 PASS. 구 code/refresh의 audience 승계를 거부한다.
- Google Console callback 재등록 후 사용자의 실제 사이트 로그인 성공과 서버 callback·홈 복귀를 확인했다.
- 새 ChatGPT/Codex 연결의 권한 동의·실제 도구 조회·자동 refresh는 미검증이다. 기존 연결을 성공으로 간주하지 않는다.
- Codex Git 마켓플레이스 사용자는 `codex plugin marketplace upgrade jobsight` → `codex plugin add jobsight@jobsight`
  → 앱 재시작 → 새 OAuth 동의를 진행한다. 마켓플레이스를 삭제·재등록하거나 저장소를 public으로 바꿀 필요는 없다.

전체 결과·미검증 항목: [배포 보고](04-report/custom-domain-oauth.report.md).

### 원격 main 통합 검증

- `4586ba9`의 공개 도메인 배포 설정과 기존 UI 수정을 보존하면서 MCP 변경을 통합했다.
- 컨테이너의 `compileJava compileTestJava test --tests '*Mcp*Test' --tests '*ServiceTest' --tests '*ControllerTest' --rerun-tasks`: 종료코드 0, `BUILD SUCCESSFUL`.
- 프런트 타입 검사·40개 테스트 및 플러그인/스킬 구조 검사 통과.
- 로컬 Compose 재빌드 후 3개 컨테이너 healthy, 스모크 30개 PASS. OAuth 메타데이터의 S256·issuer·endpoint·resource를 확인했다.
- 이번 작업은 저장소 통합·커밋·push이며 원격 서버 재배포는 포함하지 않는다. 실제 Google 로그인 왕복과 ChatGPT 연결은 여전히 미검증이다.
- 서버 배포 시 ChatGPT가 표시한 callback을 `MCP_REDIRECT_URIS`로 설정하고 `compose.mcp.yaml`을 함께 적용해야 한다. 기본 compose만 재시작하면 사용자별 callback 설정이 반영되지 않는다.

### URL 기반 연결·가이드 검증 (0.2.0, 2026-09-07)

- 기존 배포 버전의 ChatGPT 등록 성공은 사용자가 확인했다. 이번 CIMD 변경은 로컬 검증이며 아직 push·재배포하지 않았다.
- ChatGPT 공개 CIMD 문서를 실제 조회하여 복수 인증 방법의 `none`, authorization_code/refresh_token capability와 고정 callback을 확인했다.
- 컨테이너 `compileJava compileTestJava` 및 MCP/서비스/컨트롤러 테스트 32개: 종료코드 0, `BUILD SUCCESSFUL`. CIMD 코드 교환과 동일 SKILL의 initialize/resource/다운로드 제공을 검증했다.
- 프런트 타입 검사·43개 테스트, 플러그인/스킬 구조 검사 통과. 로컬 Compose 재빌드 후 스모크 35개 PASS.
- 내장 브라우저에서 공개 가이드, URL 복사, Google 로그인 후 사이트 복귀를 확인했다. 새 CIMD 방식의 실제 ChatGPT OAuth 왕복은 배포 후 확인해야 한다.
- 위 수동 callback 설정은 정적 클라이언트의 대안으로 유지된다. 새 CIMD 방식에는 개별 callback 환경변수 등록이 필요 없다.

### 이력서·자기소개 CRUD와 OAuth 갱신 검증 (0.3.0, 2026-09-16)

- 플러그인 manifest와 SKILL에 이력서·자기소개 전체 CRUD를 명시했고 공식 플러그인·스킬 구조 검사를 통과했다.
- MCP 경계 테스트에서 이력서 content, 자기소개 질문·답변·이력서 연결, 검색어·이력서 필터가 각 서비스로 그대로 전달되는지 검증한다.
- 컨테이너 `compileJava compileTestJava test --tests '*Mcp*Test'`: 종료코드 0, `BUILD SUCCESSFUL`, 16개 통과.
  인가 코드 교환, refresh token 발급·회전, 이전 토큰 재사용 거부, 정지 계정 접근 거부를 검증했다.
- Compose 재빌드 후 PostgreSQL 마이그레이션이 `now at version v21`, 백엔드 healthy, 스모크 45개 PASS다.
- 실제 ChatGPT의 자동 갱신과 배포 서버 재시작 전후 연결 유지는 배포 후 확인해야 한다.

### ChatGPT 동의 화면 JDBC 500 수정 검증 (2026-09-16)

- Google 로그인 principal인 `AppOidcUser`가 JDBC 승인 행에 저장된 뒤 동의 POST에서 Jackson 허용 목록에
  거부되던 500을 재현했다. MCP 인가 요청에 한해 사용자 UUID와 권한만 가진 기본 principal로 축약했다.
- PostgreSQL Testcontainers에서 실제 `AppOidcUser`로 동의 화면을 열고 승인 행을 다시 읽는 회귀 테스트 및
  백엔드 전체 테스트가 통과했다.
- 첫 수정 배포 후 실제 ChatGPT가 토큰 발급에는 성공했지만, `aud`의 `List.of()` 런타임 타입을 JDBC에서
  다시 읽지 못해 Bearer MCP 호출과 refresh가 500이 되는 두 번째 문제를 확인했다. 회귀 테스트를 코드 교환,
  MCP initialize, refresh token 회전까지 확장했고 백엔드 전체 61개가 통과했다(`BUILD SUCCESSFUL`, 실패 0).
- 개발 서버 백엔드를 재빌드·교체했고 컨테이너 3개 healthy, Flyway v21 최신 상태, 스모크 45개 PASS를 확인했다.
- 이전 버전이 발급한 역직렬화 불가능 OAuth 행 1개를 정확히 삭제했다. 실제 ChatGPT의 Google 로그인·JobSight
  동의·callback·액션 새로 고침은 사용자가 새 인증 요청으로 다시 확인해야 한다.
- 두 번째 배포 후 실제 ChatGPT `tools/list`에서 Spring 프록시 클래스의 override 메서드에는 `@Operation`이
  직접 보이지 않아 500이 발생했다. 도구 등록 시 실제 target class 메서드와 병합 annotation을 고정하고,
  annotation 누락은 시작 단계에서 명시적으로 실패하도록 변경했다.
- 수정 후 PostgreSQL 통합 테스트는 최초 access token과 refresh로 회전된 access token 양쪽에서 실제
  `tools/list` 및 `self_intro_list` 제목을 검증한다. 백엔드 전체 61개와 공개 스모크 45개가 통과했고,
  배포 서버의 기존 ChatGPT access token으로 공개 `/mcp`를 직접 호출해 46개 도구와 해당 제목을 확인했다.

### Codex native OAuth 연결 복구 검증 (2026-10-19)

- Codex 로그의 `notLoggedIn`으로 도구 미노출을 확인했고, 실제 CIMD 로그인은 기존 경로 제한으로 400을 반환했다.
- 공식 Codex CIMD 문서의 native 콜백을 검증하도록 수정하고 공개 서버 백엔드에 배포했다.
  이전 이미지는 `jobsite-v006-backend:pre-codex-oauth-20261019`로 보관했다.
- 서버와 분리한 임시 소스에서 Java 21 컨테이너의 `compileJava compileTestJava test --tests '*Mcp*Test'`:
  종료코드 0, `BUILD SUCCESSFUL`, 25개 통과(실패·오류·skip 0).
  Codex 임시 loopback 포트의 인가 코드 교환과 MCP initialize, 잘못된 문서 콜백 거부를 포함한다.
- 실제 Codex OAuth 로그인·사용자 동의 완료 후 이 채팅에 도구 46개가 노출되었다.
  `account_get`의 authenticated=true/ADMIN과 `company_list`의 정상 배열 17개를 확인했다.
- 배포 후 공개 `scripts/smoke.sh https://job.donyk.link`: 45개 PASS, 종료코드 0. 컨테이너 3개 healthy.
- 업무 데이터 쓰기와 삭제는 검증하지 않았다.

### 실제 Codex 자동 토큰 갱신 검증 (2026-10-19)

- 현재 Codex OAuth 승인 한 건의 access token 만료 시각만 서버에서 조기 만료시켰다.
  원래 발급·만료 시각은 토큰 원문 없이 서버 임시 파일에 보존했고 다른 연결은 변경하지 않았다.
- 재로그인이나 수동 토큰 교환 없이 이 채팅의 `account_get`을 호출해 authenticated=true/ADMIN을 확인했다.
- DB에서 access token과 refresh token 발급 시각이 모두 한국 시각 15:34:22에서 15:48:48로 갱신됐음을 확인했다.
  새 access token 만료는 16:48:48, refresh token 만료는 2027-01-17 15:48:48이며 read/write scope는 유지됐다.
- 이어서 `company_list`가 오류 없이 기업 17개를 반환했다. 토큰 원문은 조회·출력·복사하지 않았다.
- 이 검증은 서버의 만료 응답을 받은 실제 Codex 클라이언트의 자동 갱신이다.
  1시간 자연 만료를 기다리는 경우와 앱 재시작 후 갱신은 별도로 검증하지 않았다.

## 함정 — scope는 세 곳에서 광고된다

클라이언트가 요청할 scope를 정하는 경로가 셋이고, 하나만 좁아도 사용자는 그 권한을 못 받는다.

| 어디 | 무엇을 광고하나 |
|---|---|
| 401 의 `WWW-Authenticate` (`McpOAuthConfig.challenge()`) | `jobsight.read jobsight.write` |
| 리소스 메타데이터 `/.well-known/oauth-protected-resource` | `SCOPES` 전부(admin 포함) |
| 인가 서버 메타데이터 `/.well-known/oauth-authorization-server` | `SCOPES` 전부 — Spring 기본값에는 없어 `claim()`으로 직접 넣는다 |

**실제로 겪은 일**: `challenge()`가 `scope="jobsight.read"` 하나만 적고 있었다. ChatGPT 커넥터를 비롯한
클라이언트 상당수가 이 힌트를 authorize 요청에 그대로 쓰기 때문에 일반 사용자는 읽기 토큰만 받았고,
`McpTools.permitted()`가 쓰기 도구를 전부 걸러 `tools/list`에서 사라졌다 — 화면에는 "읽기 권한만 있음"으로 보였다.
`scripts/smoke.sh`의 `MCP challenge advertises write`와 `McpChallengeTest`가 이 회귀를 잡는다.

`jobsight.admin`은 기본 힌트에서 뺀다. 관리자 도구는 scope와 실제 `ROLE_ADMIN`을 둘 다 요구하므로,
일반 사용자 동의 화면에 올려 봐야 아무 권한도 늘지 않고 요청만 넓어진다.
