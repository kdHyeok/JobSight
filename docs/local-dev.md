# 로컬 개발 실행

## 처음 한 번

```bash
cp .env.example .env
mkdir -p .secrets && chmod 700 .secrets
python3 -c 'import secrets; print(secrets.token_urlsafe(36))' > .secrets/postgres_password
touch .secrets/google_client_secret
chmod 644 .secrets/*
```

`.env` 에서 `APP_ADMIN_EMAIL` 을 본인 Gmail 로 바꾼다. 이 계정은 로그인하면 항상 관리자다.

Google 로그인을 쓰려면 [Google Cloud Console](https://console.cloud.google.com) → Credentials → OAuth client ID(Web) 를 만들고, **Authorized redirect URI** 에 정확히 이 값을 등록한다:

```
http://127.0.0.1:8088/login/oauth2/code/google
```

`PUBLIC_BASE_URL` 의 origin + `/login/oauth2/code/google` 이다. 발급값 넣기:

공개 배포에서는 `PUBLIC_BASE_URL=https://job.donyk.link`, `SESSION_COOKIE_SECURE=true`이며 Google callback은
`https://job.donyk.link/login/oauth2/code/google`다. `.env.example`과 Compose는 로컬 loopback 기본값을 유지한다.
Google Console callback과 ChatGPT/Codex callback은 서로 다르다. 공개 주소 전환 시 MCP 연결도 새 URL로 다시 인증한다.

```bash
# bash
sed -i 's#^GOOGLE_CLIENT_ID=.*#GOOGLE_CLIENT_ID=<ID>#' .env
printf '%s' '<SECRET>' > .secrets/google_client_secret
```
```powershell
# PowerShell — Set-Content/> 는 BOM·ANSI 로 저장해 값을 깨뜨린다
$enc = New-Object System.Text.UTF8Encoding($false)
$lines = (Get-Content .env) -replace '^GOOGLE_CLIENT_ID=.*', 'GOOGLE_CLIENT_ID=<ID>'
[System.IO.File]::WriteAllLines((Join-Path $PWD '.env'), $lines, $enc)
[System.IO.File]::WriteAllText((Join-Path $PWD '.secrets\google_client_secret'), '<SECRET>')
```

둘 중 하나라도 비어 있으면 앱은 뜨되 로그인 수단이 없는 상태다(`/api/auth/login-options` 의 `googleEnabled:false`).

## 실행 / 확인 / 중지

PowerShell에서는 검증과 기동을 묶은 스크립트를 사용한다. 같은 Compose 프로젝트를 다른 worktree가 실행 중이면 실수로 교체하지 않고 중단한다.

```powershell
.\scripts\start.ps1
# 다른 worktree가 띄운 컨테이너를 현재 작업공간으로 명시적으로 교체할 때만
.\scripts\start.ps1 -Takeover
```

`-Takeover`도 `postgres-data` 볼륨은 삭제하지 않는다. 설정만 확인하려면 `.\scripts\start.ps1 -CheckOnly`를 사용한다.

```bash
docker compose up -d --build
bash scripts/smoke.sh          # PASS 가 나와야 정상
docker compose ps -a           # -a 없으면 중지된 컨테이너가 안 보인다
docker compose logs -f backend
docker compose down            # 볼륨 유지. down -v 는 DB 삭제 — 확인 후에만
```

`http://127.0.0.1:8088` → "Google로 계속하기". 첫 로그인은 기본적으로 가입 신청(PENDING)이 되고 관리자가 `/admin` 에서 승인한다. `/admin` 의 "자동 승인 켜기" 를 켜면 첫 로그인이 곧 가입 완료다. `APP_ADMIN_EMAIL` 계정은 설정과 무관하게 즉시 관리자로 들어간다.

## 테스트

```bash
# frontend (호스트 node 없이)
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/frontend":/app -w /app node:24-alpine \
  sh -c "npm ci && npm run type-check && npm test"

# backend 컴파일
MSYS_NO_PATHCONV=1 docker run --rm -v jobsite-v006-gradle-cache:/root/.gradle \
  -v "$PWD":/workspace -w /workspace/backend eclipse-temurin:21-jdk-alpine \
  ./gradlew compileJava compileTestJava --no-daemon > build.log 2>&1; echo "exit=$?"

# backend 전체 테스트 — Docker Desktop (Windows / Mac)
# 통합 테스트가 Testcontainers 로 PostgreSQL 을 띄우므로 Docker 소켓을 마운트한다.
# 컨테이너 안의 JVM 은 형제 컨테이너를 host.docker.internal 로만 볼 수 있고,
# Ryuk(정리 컨테이너)은 이 구성에서 뜨지 않으므로 끈다.
# 저장소 루트("$PWD")를 마운트하고 backend 로 들어간다 — backend/ 만 마운트하면
# processResources 가 ../plugins/jobsight 를 못 찾아 McpOAuthTest 2건이 "Packaged JobSight skill is missing" 으로 깨진다.
MSYS_NO_PATHCONV=1 docker run --rm \
  -e JAVA_TOOL_OPTIONS="-XX:+StartAttachListener -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v jobsite-v006-gradle-cache:/root/.gradle \
  -v "$PWD":/workspace -w /workspace/backend eclipse-temurin:21-jdk-alpine \
  ./gradlew test --no-daemon > test.log 2>&1; echo "exit=$?"

# backend 전체 테스트 — Linux 호스트 (배포 서버 등)
docker run --rm --network host -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -e JAVA_TOOL_OPTIONS="-XX:+StartAttachListener -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v jobsite-v006-gradle-cache:/root/.gradle \
  -v "$PWD":/workspace -w /workspace/backend eclipse-temurin:21-jdk-alpine \
  ./gradlew test --no-daemon > test.log 2>&1; echo "exit=$?"
```

종료코드는 파이프 뒤가 아니라 `$?` 로 직접 읽는다. 결과 요약은 `backend/build/test-results/test/*.xml` 의 `tests= failures= errors=`.
Ryuk 를 끄면 JVM 이 비정상 종료할 때 `postgres:17-alpine` 테스트 컨테이너가 남을 수 있다 — `docker ps -a` 로 확인한다.
`StartAttachListener` 옵션은 Docker 안의 JDK 21에서 Mockito가 Byte Buddy agent를 붙일 때 attach 신호가 thread dump로 끝나는 환경을 피한다.

## 자주 걸리는 것

| 증상 | 원인 → 조치 |
|---|---|
| `redirect_uri_mismatch` | `PUBLIC_BASE_URL` 과 Console 등록 URI 의 origin 불일치. `bash scripts/smoke.sh` 의 redirect_uri 줄 확인 |
| 로그인 후 `/?authError=…` | `docker compose logs backend \| grep "Google 로그인 실패"` 에 코드·설명이 남는다 |
| `authorization_request_not_found` | 세션 쿠키 `SameSite` 가 Strict 로 바뀌었는지 확인 (Lax 여야 함) |
| 재시작 뒤 backend가 `no password was provided`로 반복 종료 | 다른 worktree의 Compose 컨테이너가 남았는지 `.\scripts\start.ps1`로 확인. 현재 작업공간으로 교체할 때만 `-Takeover` |
| 한글 JSON 이 curl 에서 400 | Git Bash 가 CP949 로 보냄. UTF-8 파일로 `--data-binary @file` |

셸·경로·CRLF 같은 환경 일반 사항은 `AGENTS.md` 「환경 사실」에 있다.
