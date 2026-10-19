# 결정 기록

## 2026-10-19: 공개 origin은 job.donyk.link 하나로 전환

사용자가 붙인 새 도메인을 정식 주소로 채택한다. 기존 공용 프록시 구조·PUBLIC_BASE_URL 정본·Google OIDC는 유지하고
DuckDNS vhost는 이전 안내와 프로토콜 거부용으로 바꾼다. 두 도메인에서 인증을 동시에 시작하면 host-only 세션 쿠키와
Google callback이 갈라지므로 구 사이트 진입을 새 주소로 보낸다. query를 버려 code/state·자격증명이 도메인을 넘지 않게 한다.
도메인 전환으로 access aud만 바꾸면 구 refresh가 새 토큰을 만들 수 있으므로 저장된 code resource·refresh audience도 검사한다.
기존 연결은 새 URL로 재인증하며 사용자·업무 데이터·승인 행을 일괄 삭제하지 않는다. 아래 DuckDNS 배포 기록은 이전 결정이다.

## ChatGPT URL-only 연결은 제한된 CIMD로
매 커넥터 callback 수동 등록을 없애기 위해 ChatGPT의 공식 CIMD 문서를 검증한다. DCR 저장소를 추가하지 않는다.
허용된 HTTPS 문서 URL만 요청하며 최대 64KiB/연결·읽기 타임아웃 각각 5초, 리다이렉트 금지, 128개/10분 캐시로 제한한다.
사용자 데이터 접근은 여전히 Google 로그인·동의·PKCE·resource·scope로 보호한다. 공개 client_id는 신원 증명이나 인증 비밀이 아니다.

## Google 신원에 OAuth MCP 위임 추가
사용자 요청으로 외부 ChatGPT/Codex 클라이언트가 서비스를 조작하도록 OAuth 위임을 추가했다.
사람의 로그인은 계속 Google만 사용하고, MCP에는 별도 audience·scope를 가진 짧은 opaque token을 발급한다.
기존의 강제 홈 이동은 OAuth 승인 요청으로 복귀할 수 있도록 저장 요청이 있을 때만 복귀하도록 변경했다.
초기 단일 서버에서는 승인·토큰 저장을 메모리로 시작했으나, 아래 V21 결정으로 PostgreSQL 저장으로 교체했다.

되돌리고 싶은 결정이 있으면 먼저 여기서 이유를 읽는다. 되돌리면 그 이유도 여기에 적는다.

## 세션 쿠키는 `SameSite=Lax` (Strict 아님)
Strict 는 accounts.google.com 에서 돌아오는 콜백에 세션 쿠키를 싣지 않아 `authorization_request_not_found` 가 난다. 실제로 겪었다. Lax 는 cross-site POST/fetch 를 여전히 막고, CSRF 의 주축은 `XSRF-TOKEN` 토큰이다.

## 로그인 연장은 Google 토큰이 아니라 회전형 앱 토큰 (V17)
`JSESSIONID` 만 30일로 늘리면 탈취된 세션도 같은 기간 유효해진다. 브라우저 세션은 30분 유휴 만료로 두고, 원문을 HttpOnly 쿠키에만 두는 JobSight 리프레시 토큰으로 새 세션을 발급한다. DB에는 SHA-256 해시만 저장하며 매 사용 시 회전하되 최초 발급 30일의 절대 만료는 늘리지 않는다. Google access/refresh token은 저장하거나 재사용하지 않는다. MCP OAuth 토큰은 아래 V21의 별도 위임 수명주기를 따른다.

## MCP OAuth 상태는 PostgreSQL에 저장하고 refresh token은 90일 회전 (V21)
MCP access token은 노출 피해를 줄이기 위해 1시간으로 유지하고, 클라이언트가 90일 유효한 refresh token으로 갱신한다. refresh token은 사용할 때마다 교체하며 authorization·consent를 PostgreSQL에 저장해 서버 재시작 뒤에도 연결을 유지한다. 계정 상태는 모든 MCP 요청에서 다시 조회하므로 정지·삭제된 계정은 토큰 만료 전에도 접근할 수 없다.

## MCP JDBC 승인에는 Google principal 객체를 저장하지 않는다
Spring Authorization Server는 인가 요청의 principal을 승인 행 attributes에 함께 저장한다. 사용자 정의
`AppOidcUser`를 그대로 저장하면 쓰기는 되지만 기본 Jackson 허용 목록이 동의 POST의 역직렬화를 거부한다.
MCP 인가 체인에서만 principal을 애플리케이션 사용자 UUID와 현재 권한을 가진 Spring 기본 토큰으로 축약한다.
브라우저 세션의 Google principal은 바꾸지 않으며, DB에 불필요한 Google claim을 복제하지 않는다.
토큰 claims도 같은 허용 목록을 통과해야 한다. 따라서 `aud`는 `List.of()`가 만드는 JDK 내부 immutable 구현체가
아니라 Jackson이 허용하는 `ArrayList`로 저장한다. 이 결정은 Bearer 요청과 refresh token 갱신 모두에 적용된다.

## MCP 도구 메타데이터는 Spring 프록시가 아닌 target method에서 읽는다
운영 빈에는 Spring CGLIB 프록시가 적용될 수 있고 override 메서드에는 원본 `@Operation`이 직접 복사되지 않는다.
도구 등록 시 target class의 메서드와 병합 annotation을 고정하며, annotation이 없으면 첫 `tools/list`의 500이
아니라 서버 시작 시 도구 이름과 함께 실패시킨다.

## 메모 Markdown은 HTML 변환 문자열을 저장하지 않는다 (V18)
기업·공고·직무 메모는 사용자가 고칠 수 있는 Markdown 원문만 저장한다. 프런트는 요청된 제목·목록·들여쓰기·http(s) 링크만 Vue 노드로 렌더링하고 사용자 HTML은 해석하지 않는다. DB에 HTML을 저장하거나 `v-html`을 쓰지 않아 XSS 정화 라이브러리와 이중 정본을 만들지 않는다.

## 이력서 직무 연결은 관계, 자기소개는 별도 행 (V19)
이력서 본문 JSON에 직무·질문 ID를 섞지 않는다. 직무는 FK가 있는 `resume_positions`, 질문·답변은 이력서 FK가 있는 `self_introductions`로 저장해 삭제 정리와 소유권 검증을 DB·서비스가 맡는다. 검색 규모가 작은 동안 PostgreSQL 전문 검색이나 별도 검색 서버 대신 서버 메모리에서 BM25를 계산하고, 데이터 규모가 병목으로 확인될 때 검색 인덱스로 교체한다.

## 자기소개 문항은 여러 이력서에 연결 (V20)
같은 질문·답변을 지원서 버전마다 복제하면 검색 결과와 수정본이 갈라진다. 문항 본문은 한 행으로 유지하고 `self_introduction_resumes` 관계로 여러 이력서에 연결한다. 기존 `resume_id` 값은 마이그레이션에서 관계 행으로 옮긴 뒤 제거한다.

## CSRF 핸들러는 평문 `CsrfTokenRequestAttributeHandler`
Spring Security 6+ 기본 Xor 핸들러는 마스킹 토큰을 기대해 쿠키 원본 토큰을 거부한다(모든 POST 가 403). 토큰이 응답 본문에 실리지 않으므로 BREACH 위험은 해당 없다.

## 인증은 Google OIDC 하나, 비밀번호 로그인 없음
사용자 결정. 락아웃 위험은 Google 측 설정 문제일 때만 생기고 그때는 비밀번호가 있어도 Google 을 고쳐야 한다. 복구 경로는 DB 직접 접근. `APP_ADMIN_EMAIL` 상시 규칙이 관리자 확보를 보장한다. 단 Google 동의 화면이 Testing 이면 등록된 테스트 사용자만 로그인된다.

## `APP_ADMIN_EMAIL` 은 상시 규칙
로그인마다 ADMIN/ACTIVE 로 복구된다. 설정 파일이 권한의 최종 근거. 화면에서 내려도 다음 로그인에 돌아온다 — 의도된 동작이다.

## `redirect_uri` 는 `PUBLIC_BASE_URL` 로 명시 조립
`X-Forwarded-*` 추론은 nginx 내부 80 / 외부 8088·8443 차이로 세 번 어긋났다(redirect_uri_mismatch 두 번, /admin 302 한 번). 환경이 바뀌면 `.env` 한 줄만 바꾼다.

## 프런트는 base URL 을 모른다
same-origin 이므로 상대 경로가 정답이고, base URL 을 알면 포트 버그에 노출된다. 경로 상수(`routes.ts`)와 배포 값(`PUBLIC_BASE_URL`)은 다른 종류다 — 경로를 `.env` 에 넣지 않는다.

## 소유자 조회는 `findByIdAndOwnerId`
`findById` 후 비교는 비교를 빠뜨리면 그대로 유출이다. 쿼리에 소유자 조건을 넣으면 구조적으로 불가능. 타인 데이터는 존재를 알리지 않도록 404.

## Google 사용자 해석과 상태 검사를 분리
`resolveGoogleUser`(트랜잭션, 커밋) → 상태 검사(밖). 안에서 예외를 던지면 첫 로그인의 PENDING 계정이 롤백되어 "신청 접수" 안내와 실제 DB 가 어긋난다.

## 이메일 연결은 `email_verified=true` 일 때만
미검증 이메일로 기존 계정을 연결하면 계정 탈취가 된다.

## 가입 토글은 "자동 승인 여부" 다. 가입을 막는 상태는 없다 (V8)
처음엔 "가입 허용/거부" 였는데, 거부는 사용자에게 오류만 보여주고 관리자에게는 아무 흔적도 남기지 않았다. 지금은 첫 로그인이 항상 계정을 만들고, 토글이 그 초기 상태(ACTIVE / PENDING)만 정한다. 승인 대기 목록이 곧 대기열이라 관리자가 누가 왔는지 볼 수 있다. V8 이 컬럼을 `auto_approve_signup` 으로 개명하고 기존 동작(승인 필요)을 보존하기 위해 `false` 로 초기화했다.

## 지원 진행 상태는 공고가 가진다. 기업의 `status` 는 제거 (V9)
기업에 관심/지원준비/지원완료가 붙어 있고 공고에도 지원단계가 생기면, 같은 사실이 두 곳에 기록되어 서로 어긋난다. "이 회사 지원완료" 인데 공고는 작성중인 상황을 어느 쪽이 맞다고 할 수 없다. 지원은 공고 단위로 일어나므로 공고가 유일한 근거다. 기존 기업 `status` 값은 메모 끝에 한 줄로 옮기고 컬럼을 지웠다.

## 마감된 "관심" 공고 자동 보관은 조회 시점에 (스케줄러 없음)
`@Scheduled` 는 컨테이너가 꺼져 있던 기간을 건너뛰고, 로컬 개발에서 동작을 확인하기 어렵다. 목록 조회 트랜잭션에서 처리하면 사용자가 보는 순간 항상 정확하고 추가 인프라가 없다. 목록을 안 열면 보관되지 않지만, 안 여는 동안에는 보일 일도 없다.
자동 보관 대상을 `INTERESTED` 로만 한정한 이유: 지원을 넣은 공고는 마감이 지나도 결과를 기다리는 중이라 살아있는 정보다.

## 편집 가능한 계정 정보는 `display_name` 만
이메일은 Google 신원이자 `APP_ADMIN_EMAIL` 규칙·계정 연결의 키다. 편집을 허용하면 관리자 권한이 다른 사람에게 넘어가거나 계정이 합쳐질 수 있다. 관리자 화면의 "정보 수정" 도 이름만 다룬다.

## 계정 삭제는 소유 기업을 함께 지운다
FK `ON DELETE CASCADE`. 소유자 없는 기업 행을 남기면 소유자 격리 불변 조건이 깨진다. 삭제 전 확인 대화상자에 이 점을 명시한다.

## API 문서는 Swagger, ADMIN 전용
손으로 쓴 API 목록은 첫 변경에 썩는다. `@Operation` 이 유일한 API 설명 위치. 정보 노출이라 관리자만 본다.

## 마이그레이션은 데이터를 지우지 않는다 (V6)
`password_hash` 삭제 후 `google_sub` 없는 행은 "로그인 수단 없음" 으로 남긴다. 사람이 관리자 화면에서 정리한다.

## nginx 는 상대 리다이렉트 (`absolute_redirect off`)
기본값은 `$host`(포트 없음) 로 절대 URL 을 만든다.

## 가드레일은 산문보다 실행 파일
이 프로젝트의 오판은 코드 위치를 몰라서가 아니라 검증 절차(파이프가 exit code 숨김, 응답 오독)와 버전 함정에서 났다. `scripts/smoke.sh` 가 그 검증을 고정한다. AGENTS.md 는 짧게, 코드로 가는 지도로 유지한다.

## 지원단계 하나를 "내 상태" 와 "회사 절차" 로 분리 (V10)
`stage` 의 `관심·작성중·지원완료` 는 내가 정하는 상태고 `코테·면접·결과대기` 는 회사가 정하는 절차다. 절차는 공고마다 달라 고정 enum 에 들어갈 수 없다. V10에서는 `status` 4개 + `recruitment_steps` 로 나눴고, V12에서 준비·합격·탈락 상태를 추가했다. 기존 절차성 값은 `SUBMITTED` + 같은 이름의 step 하나로 옮겼다.

## 자동 보관 대상을 `INTERESTED` 에서 `INTERESTED + DRAFTING` 으로 (V10)
사용자 요청. 작성 중이던 공고도 마감이 지나면 낼 수 없는 정보다. `SUBMITTED` 는 여전히 결과를 기다리는 살아있는 정보라 그대로 둔다.

## 기업 스냅샷 이름 대신 자동 생성 (V10)
`company_name_snapshot` 은 "기업은 아닌데 이름은 있는" 반쯤 정규화된 상태였다. 직접 입력한 이름으로 기업을 찾거나 만들면 공고의 `company_id` 가 항상 있고, 기업 탭에도 자연히 나타난다. 매칭은 공백 제거 + 소문자(결정 4). "네이버" 와 "NAVER" 는 다른 기업이다 — 별칭은 필요해질 때.

## 절차는 공고 단위, 참고 정보는 계정 단위 (V10)
결정 1·2. 직무별 절차는 `position_id` 컬럼 하나로 나중에 확장 가능하고, 참고 정보의 사용자 간 공유는 소유자 격리 불변 조건에 예외를 만들기 때문에 열지 않았다.

## 플러그인 배포는 GitHub marketplace 중심

사용자는 ZIP 다운로드가 아니라 예시 저장소처럼 GitHub 최신 플러그인 설치를 원했다. 사이트의 ZIP·PowerShell 다운로드 안내를 Codex marketplace 등록·설치·업데이트 명령으로 대체한다. 기존 ZIP API는 호환용으로 유지한다. GitHub 공개 여부나 사용자 Codex 설정은 자동 변경하지 않는다.

## 공고 폼은 직무 이름만, 직무 상세는 직무 페이지에서
공고 폼에 직무 11개 필드를 직무 수만큼 반복하면 폼이 화면 서너 장이 된다. 공고 폼은 `id`+`name` 만, 나머지는 직무 드로어에서.

## 지원 상태를 준비·결과 단계까지 확장하고 칸반으로 표시 (V12)
사용자 요청. `SUBMITTED` 하나로는 필기 준비·면접 준비·합격·탈락을 구분할 수 없었다. 진행 중은 관심→작성중→제출 완료→필기 준비→면접 준비→합격, 보관함은 미지원과 서류/필기/면접 탈락으로 나눈다. `CLOSED`는 기존 데이터의 의미를 추측해 바꾸지 않고 호환 값으로만 남겨 보관한다.

## 매출 입력 단위를 원 값과 함께 저장 (V12)
같은 금액도 사용자가 만 원 또는 억 원 기준으로 읽고 입력할 수 있다. `annual_revenue`는 계산 가능한 원 단위 정본으로 유지하고 `revenue_unit`은 입력·표시 단위만 보존한다. 별도 문자열 금액을 저장하지 않는다.

## 채용 절차 결과를 예정·완료로 단순화 (V13)
사용자 요청. 지원 결과는 공고의 내 상태에서 이미 관리하므로 절차 노드의 진행 중·통과·탈락 네 상태는 중복이었다. 노드는 일정 체크 용도로 예정(`UPCOMING`)·완료(`PASSED`)만 둔다. 기존 `IN_PROGRESS`는 예정, `FAILED`는 절차가 수행된 기록이므로 완료로 이관한다.

## 배포는 공용 리버스 프록시 + duckdns 도메인 (tailnet 폐기)
처음엔 `tailscale serve --https=8443` 로 tailnet 안에만 열었다. 폐기는 사용자 결정이다.

지금은 호스트의 별도 프록시 스택(`/home/quincy/HDD/apps/reverse-proxy`, `conf.d/40-job.conf`)이 `https://job.donhse.duckdns.org` 를 TLS 종단하고, `jobsite-v006_app` 네트워크로 `jobsite-v006-frontend-1:80` 에 컨테이너명으로 직접 붙는다. jobsite 는 tailnet 에 올리지 않는다.

되돌리면서 생긴 대가:
- `PUBLIC_BASE_URL=https://job.donhse.duckdns.org`, `SESSION_COOKIE_SECURE=true`. 앱은 base URL 을 하나만 갖기 때문에 `http://127.0.0.1:8088` 로는 로그인할 수 없다 — 로컬 8088 은 미인증 스모크·디버깅 경로로만 남는다.
- 프록시가 `ports:` 매핑을 경유하지 않으므로 **이 저장소에서 컨테이너를 재생성하면 그 순간 공개 도메인에 반영된다.** "로컬에서 먼저 확인하고 나중에 노출" 이 성립하지 않는다.
- 서비스가 공개 인터넷에 노출된다. 접근 제한은 Google OIDC 와 관리자 승인제뿐이다. 동의 화면이 Testing 이면 등록된 테스트 사용자만 로그인된다(「인증은 Google OIDC 하나」 참고).

## 이력서는 JSONB 문서 하나, 섹션 테이블 없음 (V14)
9개 섹션마다 테이블을 두면 엔티티·DTO·리포지토리 40개가 생긴다. 이력서는 통째로 읽고 통째로 저장하는 문서이고, 자격증만 SQL 로 뽑을 일이 없다. 모양은 `ResumeContent` record 가, 화면은 `types/resume.ts` 의 `SECTIONS` 가 같은 키로 든다. 섹션별 질의가 필요해지면 그때 테이블로 뽑는다 — JSONB 라 마이그레이션에서 `jsonb_array_elements` 로 옮길 수 있다.
연월은 문자열로 둔다. 사용자는 `2024.03`, `202403`, `현재 교육 중` 처럼 쓰고, 날짜로 파싱하면 그 표현을 잃는다.

## 이력서 편집기는 드로어가 아니라 전체 페이지
드로어는 520px 고정(design-system 함정)이고 이력서는 섹션 9개·필드 최대 10개짜리 행들이다. 규칙 4(상세·수정은 드로어)의 유일한 예외로 규칙 7 에 적었다. 목록(`/resumes`)과 편집기(`/resumes?focus=<id>`)는 기존 `?focus=` 메커니즘을 그대로 쓴다 — 라우트를 새로 만들지 않았다. 저장은 문서 통째 PUT 이고 행 단위 API 는 없다: 행을 옮기고 지우는 건 화면 안의 일이고, 저장 버튼 하나가 DB 와의 경계다.

## MCP 401 힌트는 `jobsight.read jobsight.write`, admin 은 뺀다
`WWW-Authenticate`의 `scope`는 "이 리소스에 접근하려면 필요한 scope"이고, 클라이언트 상당수가 이걸 authorize 요청에 그대로 복사한다. 여기에 `jobsight.read`만 적어 두어 일반 사용자가 배포에서 쓰기 도구를 하나도 못 받았다. 리소스 메타데이터에는 세 scope가 다 있었지만 힌트를 우선하는 클라이언트에는 소용이 없었다.
`jobsight.admin`은 힌트에서 뺀다. 관리자 도구는 scope와 실제 `ROLE_ADMIN`을 함께 요구하므로 일반 사용자가 동의해도 권한이 늘지 않는다 — 동의 화면만 넓어진다. 관리자는 리소스 메타데이터의 `scopes_supported`를 보고 따로 요청한다.
인가 서버 메타데이터에도 `scopes_supported`를 넣었다. Spring Authorization Server는 이걸 기본으로 내보내지 않아, 힌트 대신 그 문서를 읽는 클라이언트에게는 write가 아예 보이지 않았다.

## 리다이렉트 스킴은 내부 nginx 가 `X-Forwarded-Proto` 를 보존해서 막는다
미인증 `/oauth2/authorize` 는 Google 로그인으로 보내는데, 그 `Location` 이 `http://job.donhse.duckdns.org/...` 로 나갔다. 앱은 `sendRedirect("/oauth2/authorization/google")` 로 상대 경로를 쓰지만 Tomcat 이 절대 URL 로 바꾸고, 그때 쓰는 스킴이 잘못됐다: 앞단 프록시가 `X-Forwarded-Proto: https` 를 넣어도 내부 nginx 가 `$scheme`(컨테이너 안이라 항상 http)으로 덮어써 버렸다.

브라우저에서는 드러나지 않는다. 앞단이 HSTS 를 보내고 80 이 301 로 올려 주기 때문이다. 끊기는 쪽은 HSTS 를 모르는 비브라우저 OAuth 클라이언트다.

내부 nginx 가 스킴을 보존하게 했다. Host 는 여전히 `$http_host` 원본만 쓰고, 스킴만 예외로 앞단 값을 받는다. 값이 정확히 `"https"` 일 때만 받아들이고 나머지는 `$scheme` 으로 떨어뜨려 임의 문자열을 그대로 신뢰하지는 않는다. 로컬 `127.0.0.1:8088` 직결은 http 로 남는다 — 그 포트는 루프백 전용이므로 헤더를 위조할 수 있는 위치라면 이미 호스트 접근 권한이 있는 셈이다.

`server.tomcat.use-relative-redirects: true` 도 검토했다. `Location` 이 `/oauth2/authorization/google` 로 나가 스킴을 조립할 일이 아예 없어지므로 더 강한 수정이다. 채택하지 않았다: Tomcat 이 이 설정에서 `sendRedirect` 를 302 대신 **303** 으로 내보내고, 그러면 스모크의 `GET /oauth2/authorization/google → 302` 단정이 깨진다. GET 리다이렉트에서 303 은 기능상 같지만, 증상 하나를 고치려고 기존 가드레일의 기대값을 낮추는 거래는 하지 않았다. 나중에 상태코드까지 함께 다루기로 하면 그때 두 층으로 올린다.

`scripts/smoke.sh` 가 authorize 진입점의 `Location` 이 `http://` 로 시작하면 실패한다.

## 관리자에게는 계정별 "개수"만 연다

소유자 격리(불변 조건 1)는 타인의 행을 404 로 감춘다. 관리자 화면에서 어떤 계정이 실제로 쓰이는지
보려면 이 규칙에 구멍을 내야 했는데, **집계 숫자에 한정해서만** 냈다 — `GET /api/admin/users/counts`
는 기업·공고·직무의 개수만 돌려주고 기업명·공고 제목 같은 내용은 어떤 관리자 엔드포인트도 내주지 않는다.
"관리자니까 다 볼 수 있다"로 넓히지 않는다. 넓히려면 여기부터 다시 읽는다. — docs/admin.md

## 관리자 요청 본문은 소유자 격리의 명시적 예외 (V16)

사용자가 관리자에게 전달하려고 작성한 버그 제보·기능 제안은 관리자가 읽고 답해야 기능이 성립한다.
따라서 기존의 "관리자는 타인 데이터의 집계 숫자만 본다" 결정을 요청 본문과 피드백에 한해서 확장한다.
기업·공고·직무·이력서 등 기존 업무 데이터의 내용 조회 권한은 그대로 열지 않는다.
