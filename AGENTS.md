# AGENTS.md — JobSight v0.0.6 작업 규칙

이 파일이 정본이다. `CLAUDE.md`는 이 파일을 임포트만 한다. 두 파일에 내용을 나눠 쓰지 않는다.

## 이 프로젝트가 무엇인가

Google 계정으로 로그인하는 기업 정보 CRUD. 계정별 데이터 분리, 관리자 승인제(자동 승인 토글 있음).
PostgreSQL + Spring Boot 4.1(Java 21) + Vue 3(TypeScript) + nginx, Docker Compose 3 컨테이너.
로컬 `http://127.0.0.1:8088`, 정식 배포 주소는 `https://job.donyk.link`(호스트 리버스 프록시).

## 작업 순서 — 문서가 먼저다

1. 건드릴 영역의 문서를 아래 지도에서 찾아 **먼저 읽는다.**
2. 참조할 문서가 없는 기능·기획이면 `docs/` 에 **한 화면짜리 문서를 먼저 만든다** — 목적 / 진입점 / 불변 조건 / 실행·검증 / 함정. 그리고 아래 지도에 행을 추가한다.
3. 개발한다.
4. 바뀐 사실을 **그 문서에 반영한다.** 결정을 되돌렸으면 `docs/decisions.md` 에 이유를 적는다.

문서 없이 개발하지 않고, 개발하고 문서를 남겨두지 않는다. 이 순서를 건너뛴 변경은 완료가 아니다.

## 문서 지도

| 건드리는 것 | 읽을 문서 |
|---|---|
| 처음 실행, 환경 문제 | [docs/local-dev.md](docs/local-dev.md) |
| `backend/**` | [docs/backend.md](docs/backend.md) — 버전 함정 포함 |
| `frontend/src/**` | [docs/frontend.md](docs/frontend.md) |
| 로그인 세션·리프레시 토큰 | [docs/auth-session.md](docs/auth-session.md) |
| 기업 정보·채용공고 도메인 | [docs/job-postings.md](docs/job-postings.md) |
| 기업 뉴스·유튜브 자료 | [docs/company-content.md](docs/company-content.md) |
| 이력서(버전·섹션 JSON) | [docs/resumes.md](docs/resumes.md) |
| 이력서 연결 직무·자기소개 문항 | [docs/self-introductions.md](docs/self-introductions.md) |
| 이력서 증빙 파일 첨부 | [docs/attachments.md](docs/attachments.md) |
| 관리자 콘솔·계정별 등록 수 | [docs/admin.md](docs/admin.md) |
| 관리자 요청·피드백 | [docs/requests.md](docs/requests.md) |
| MCP·OAuth 위임·플러그인 | [docs/mcp-plugin.md](docs/mcp-plugin.md) |
| `job.donyk.link` 도메인·OAuth·플러그인 전환 | [계획](docs/01-plan/features/custom-domain-oauth.plan.md) · [설계](docs/02-design/features/custom-domain-oauth.design.md) |
| 화면 배치·색·컴포넌트 | [docs/design-system.md](docs/design-system.md) |
| `nginx.conf`, `compose*.yaml`, `.github/**`, 배포 | [docs/nginx-deploy.md](docs/nginx-deploy.md) |
| "왜 이렇게 했지?" | [docs/decisions.md](docs/decisions.md) — 되돌리기 전에 반드시 읽는다 |
| API 목록과 목적 | 관리자 로그인 후 `/swagger-ui/index.html`. 손으로 쓴 목록은 없다(썩는다). |

## 깨면 안 되는 것 (불변 조건)

1. **소유자 격리**: 기업 조회는 `findByIdAndOwnerId`/`findAllByOwnerId…`만 쓴다. `findById` 후 비교 금지. 타인 데이터는 403 이 아니라 404.
2. **기본 비밀번호 없음, 비밀번호 로그인 없음**: 인증은 Google OIDC 하나. 비밀값은 `.secrets/` 파일 → compose secret → `configtree` 로만 주입.
3. **경로 리터럴 금지**: 백엔드는 `ApiPaths`, 프런트는 `routes.ts`, nginx 는 `nginx.conf` 상단 표. 세 곳을 함께 갱신한다.
4. **절대 URL 조립 금지**: nginx·Spring 어디서도 `$host`/`$server_port`/`X-Forwarded-*` 로 URL 을 만들지 않는다. 브라우저가 보는 주소가 필요하면 `PUBLIC_BASE_URL` 하나만 쓴다.
5. **세션 쿠키 `SameSite=Lax`**: Strict 로 바꾸면 Google 콜백이 깨진다. CSRF 는 토큰이 담당한다.
6. **`APP_ADMIN_EMAIL` 은 상시 규칙**: 이 이메일은 로그인마다 ADMIN/ACTIVE 로 복구된다. 화면에서 내려도 소용없다.

## 건드린 것 → 반드시 돌릴 것

| 건드린 것 | 반드시 |
|---|---|
| 무엇이든 | `bash scripts/smoke.sh` 가 `PASS` 일 때만 "됐다"고 말한다 |
| 무엇이든 | 해당 `docs/*.md` 갱신. 새 영역이면 새 문서 + 위 지도에 행 추가 |
| `SecurityConfig`, `nginx.conf`, `routes.ts`, `ApiPaths` | 위 스모크 + 브라우저에서 Google 로그인 1회 실제 왕복 |
| `db/migration/*` | `docker compose logs backend`에서 `now at version vN` 확인. 새 파일만 추가, 적용된 파일 수정 금지 |
| `.env.example` | `compose.yaml`·`.env`·`docs/local-dev.md` 세 곳 동기화 |
| `backend/**` | 컨테이너 안에서 `./gradlew compileJava compileTestJava`, 종료코드 직접 확인 |
| `frontend/src/**` | `npm run type-check && npm test`, 종료코드 직접 확인 |

## 검증 절차 규칙 — 이 프로젝트에서 실제로 오판을 낸 것들

- **파이프로 종료코드를 가리지 않는다.** `gradle … | tail` 은 tail 의 0 을 돌려준다. 출력은 파일로 받고 `$?` 를 따로 읽는다.
- **응답 본문을 셀 때 상태코드를 먼저 본다.** 401 에러 JSON 의 키 5개를 "데이터 5건"으로 읽은 적이 있다.
- **redirect_uri 는 `Location` 헤더로 확인한다.** 사용자에게 Console 등록을 요청하기 전에 앱이 실제로 보내는 값을 본다.
- **"컴파일 성공"은 `BUILD SUCCESSFUL` 문자열과 exit 0 을 둘 다 봤을 때만 말한다.**
- **로그인 직후 CSRF 토큰은 회전한다.** curl 검증은 로그인 → GET 한 번 → 변경 요청 순서로 한다.

## 환경 사실 (Windows 호스트)

- 셸은 Git Bash. 사용자 터미널은 PowerShell 5.1 — `sed`/`printf` 없음, `Set-Content` 는 ANSI, `>` 는 BOM 포함 UTF-8. 파일 쓰기 예시는 `[System.IO.File]::WriteAllText` 로 준다.
- docker 볼륨/워크디렉터리 인자에 MSYS 경로 변환이 걸린다: `MSYS_NO_PATHCONV=1 docker run -v "$PWD/x":/x -w /x …`
- Windows Python 은 `/c/Users/...` 를 못 읽는다. `C:/Users/...` 로 넘긴다.
- `.env` 에 CRLF 가 섞이면 값 끝에 `\r` 이 붙는다. `tr -d '\r'` 로 정리한다.
- `docker compose ps` 는 중지된 컨테이너를 숨긴다. 상태 확인은 `ps -a`.

## 완료를 선언하기 전에

1. 위 「건드린 것 → 반드시 돌릴 것」 의 해당 행을 전부 돌렸다.
2. 되돌린 결정이 있으면 `docs/decisions.md` 에 이유를 남겼다.
3. 검증하지 못한 것은 "검증하지 못했다"고 적었다.
