# nginx · Compose · 배포

`job.donyk.link` 전환의 [계획](01-plan/features/custom-domain-oauth.plan.md)·
[설계](02-design/features/custom-domain-oauth.design.md)를 따른다. 구 주소용 설정 정본은
`deploy/nginx/40-job-legacy.conf`이며 외부 프록시의 `conf.d/40-job.conf`에 설치한다.

## nginx (`frontend/nginx.conf`)

컨테이너 안에서 **80** 을 듣고 밖에서는 **8088**(로컬 직결) 또는 **443**(공개 도메인) 으로 보인다. 이 차이가 이 프로젝트 버그의 절반이었다.

규칙:
- `absolute_redirect off;` — 리다이렉트는 상대 경로. 켜면 `Location: http://127.0.0.1/` 처럼 포트가 빠진다.
- 프록시 헤더는 `$http_host`(브라우저가 보낸 Host 원본). `$host` 는 포트를 떼고 `$server_port` 는 80 이다.
- 스킴만은 앞단 값을 받는다: `X-Forwarded-Proto` 가 정확히 `https` 면 그대로 넘기고, 아니면 `$scheme`(=http). 이 nginx 는 평문 80 을 들으므로 `$scheme` 을 그대로 넘기면 Tomcat 이 상대 리다이렉트를 `http://` 절대 URL 로 바꾼다. 덮어쓰면 authorize 진입점의 `Location` 이 평문으로 나가 HSTS 를 모르는 OAuth 클라이언트가 끊긴다(`docs/decisions.md`).
- `X-Forwarded-Port` 를 보내지 않는다. 절대 URL 이 필요한 곳은 백엔드가 `PUBLIC_BASE_URL` 로 만든다.

백엔드로 넘기는 접두사(파일 상단 표와 동일): `/api/`, `/oauth2/`, `/login/`, `/swagger-ui`, `/v3/`, `/mcp`, `/.well-known/`.
`/admin` 은 `auth_request` → `/api/auth/admin-check`(204 면 index.html, 아니면 302 `/`). 데이터 보호는 백엔드가 하고 이 게이트는 UI 노출 방지용이다.

경로를 추가하면 `nginx.conf` 표 · `ApiPaths.java` · `routes.ts` 를 함께 갱신한다.

## Compose

백엔드 빌드의 `plugin` named context는 `plugins/jobsight`만 포함한다. `.env`·`.secrets`는 전달하지 않는다.
`/plugin`은 공개 가이드 SPA, `/plugin/` 하위는 공개 설정·스킬·ZIP 다운로드를 위해 backend로 프록시한다.

MCP의 정확한 OAuth callback 설정은 선택적 `compose.mcp.yaml`에서 `MCP_REDIRECT_URIS`를 받는다.
등록 절차와 공개 HTTPS 접근 조건은 [mcp-plugin.md](mcp-plugin.md)를 따른다.

- `compose.yaml` 하나로 로컬·서버 모두 소스에서 빌드한다. 이미지 레지스트리를 쓰지 않는다.
- DB 이름·사용자 값이 비어 있으면 Compose 보간 단계에서 중단한다. backend는 DB 연결을 3초 안에 실패시키고 60초의 초기 기동 유예 뒤 healthcheck 3회 연속 실패 시 unhealthy가 된다. frontend healthcheck는 DB를 조회하는 `/api/auth/login-options`까지 확인해 backend·DB 장애를 함께 감지한다.
- 비밀값: `.secrets/postgres_password`, `.secrets/google_client_secret` → compose secret → 컨테이너 `/run/secrets/<프로퍼티키>` → Spring `configtree`. 파일 이름이 프로퍼티 키다.
- 설정: `.env` 의 `PUBLIC_BASE_URL`, `APP_ADMIN_EMAIL`, `GOOGLE_CLIENT_ID`, `SESSION_COOKIE_SECURE`(HTTPS 뒤에서만 true).
- 새 키를 추가하면 `.env.example`·`compose.yaml`·`docs/local-dev.md` 세 곳.

## 공개 진입점 — 호스트의 리버스 프록시

이 저장소 밖에 있다. 별도 compose 프로젝트다.

```
브라우저 → https://job.donyk.link                (호스트 443, 192.168.0.211)
        → reverse_proxy 컨테이너  nginx:1.28-alpine, listen 8443 ssl
           /home/quincy/HDD/apps/reverse-proxy/conf.d/41-job-donyk.conf
        → jobsite-v006-frontend-1:80             jobsite-v006_app 네트워크, 컨테이너명 직결
        → backend:8080
```

- TLS는 Let's Encrypt `donyk.link` 와일드카드 인증서로 덮는다. 구 도메인 인증서는 구 vhost와 ACME 갱신에 유지한다. 종단은 프록시가 하고 이 저장소는 평문 80만 다룬다.
- 프록시가 `X-Forwarded-Proto https` 를 고정으로 넣는다. Spring 은 `forward-headers-strategy: framework` 로 이를 받아 secure 판정에 쓴다. `redirect_uri` 는 여기에 의존하지 않고 `PUBLIC_BASE_URL` 로 조립한다. 내부 nginx 가 이 값을 덮어쓰지 않아야 한다 — 덮어쓰면 authorize 진입점의 `Location` 이 평문으로 나간다.
- 프록시가 `proxy_set_header Host $host` 를 쓴다. 공개 포트가 기본 443 이라 지금은 포트 누락이 없다. **비표준 포트로 옮기면 그 순간 `$host` 함정에 걸린다** — 그때는 `$http_host` 로 바꾼다.
- upstream 을 변수(`set $job_up …`)로 두고 `resolver 127.0.0.11` 로 런타임 재해석한다. 이게 없으면 컨테이너를 재생성한 뒤 프록시가 죽은 IP 를 계속 잡는다.

**함정: 로컬 확인 후 노출이라는 단계가 없다.** 프록시는 `ports:` 매핑(`127.0.0.1:8088`)을 경유하지 않고 컨테이너명으로 붙는다. 그래서 이 저장소에서 `docker compose up -d` 를 돌리면 그 순간 공개 도메인에 반영된다. 스모크는 배포 **뒤** 검증이지 배포 전 관문이 아니다.

`SESSION_COOKIE_SECURE=true` 이므로 `http://127.0.0.1:8088` 로는 로그인할 수 없다(쿠키가 실리지 않는다). 8088 은 미인증 스모크·디버깅 경로다.

## 배포 — 서버에서 직접 pull

CI/CD 파이프라인은 없다. 서버에 SSH 로 들어가 `git pull` 하고 다시 올린다.

정식 저장소는 `https://github.com/kdHyeok/JobSight`다. 기존 clone은
`git remote set-url origin https://github.com/kdHyeok/JobSight.git`으로 원격만 바꾼다.
아래 로컬 디렉터리·Compose 프로젝트·볼륨 이름은 저장소 이름 변경과 무관하게 유지한다.

### 서버 준비 (1회)

```bash
git clone https://github.com/kdHyeok/JobSight.git ~/jobsite-v006
cd ~/jobsite-v006
cp .env.example .env
mkdir -p .secrets && chmod 700 .secrets
python3 -c 'import secrets; print(secrets.token_urlsafe(36))' > .secrets/postgres_password
printf '%s' '<Google 클라이언트 시크릿>' > .secrets/google_client_secret
chmod 644 .secrets/*
```

`.env` 를 공개 도메인 값으로 채운다. `.env` 와 `.secrets/` 는 `.gitignore` 대상이라 `git pull` 이 덮어쓰지 않는다.

```
PUBLIC_BASE_URL=https://job.donyk.link
SESSION_COOKIE_SECURE=true
APP_ADMIN_EMAIL=<본인 Gmail>
GOOGLE_CLIENT_ID=<클라이언트 ID>
```

Google Cloud Console 의 Authorized redirect URI 에 `{PUBLIC_BASE_URL}/login/oauth2/code/google` 을 추가한다. 로컬용(`http://127.0.0.1:8088/…`)과 별개 항목이다.

새 도메인을 붙이려면 이 저장소가 아니라 프록시 스택의 `conf.d/` 에 vhost 를 추가하고 그 스택을 reload 한다.

구 주소는 알려진 화면 GET/HEAD만 새 고정 origin으로 302 이동하고 query는 모두 버린다. Google callback도
code/state를 전달하지 않고 `/?authError=DOMAIN_CHANGED`로 보낸다. 구 `/mcp`는 새 metadata challenge로 401,
OAuth 프로토콜 경로는 400 `invalid_target`, 나머지 API·변경 요청은 410이다. metadata GET은 새 canonical JSON을 제공한다.
Bearer·POST body·세션 쿠키를 새 도메인으로 프록시/리다이렉트하지 않는다. `/health`와 HTTP ACME 갱신은 유지한다.

### 매 배포

```bash
cd ~/jobsite-v006
git pull
docker compose up -d --build   # 이 시점에 공개 도메인이 바뀐다
bash scripts/smoke.sh          # PASS 여야 배포 완료
```

Windows 개발 환경에서는 `scripts/start.ps1`가 고정 Compose 프로젝트를 다른 worktree가 실행 중인지 먼저 확인한다. `-Takeover`는 컨테이너만 현재 작업공간 설정으로 재생성하고 DB 볼륨은 유지한다.

`smoke.sh` 는 `.env` 의 `PUBLIC_BASE_URL` 을 읽는다. 출력 첫 줄이 공개 도메인인지 확인한다.
기존 수동 OAuth callback을 유지하는 서버는 `-f compose.yaml -f compose.mcp.yaml`을 함께 사용한다.
base URL을 바꿀 때는 구 code/refresh 승계를 막는 코드도 함께 배포하고, 가이드·ZIP·GitHub 플러그인 URL을 대조한다.

### 롤백

```bash
git log --oneline -5
git checkout <이전 커밋>
docker compose up -d --build
```

마이그레이션이 이미 적용된 뒤라면 코드만 되돌려도 스키마는 남는다. Flyway 는 되돌리지 않으므로, 스키마를 바꾼 배포의 롤백은 되돌림 마이그레이션을 새로 쓴다. 되돌린 코드의 `ddl-auto: validate` 가 남은 스키마와 어긋나면 기동이 실패한다.

스키마를 바꾸는 배포 전에는 덤프를 떠 둔다.

```bash
docker compose exec -T db pg_dump -U jobsite_app -d jobsite > ~/jobsite-db-$(date +%Y%m%d).sql
```
