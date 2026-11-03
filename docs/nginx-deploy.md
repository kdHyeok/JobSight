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

정적 자산 캐시(설계 1.3 P9):
- `/assets/` 는 Vite 해시 파일이라 `Cache-Control: public, max-age=31536000, immutable` 이다. 없는 파일은 `index.html` 로 떨어뜨리지 않고 404 다 — 옛 해시 이름에 HTML 이 1년 캐시되는 일을 막는다.
- `index.html` 에는 캐시 헤더를 붙이지 않는다. 새 배포의 해시 이름을 바로 받아야 해서다. 공개 도메인에서는 Cloudflare 가 따로 엣지 캐시를 한다(설계 1.0).
- gzip 은 넣지 않았다. 공개 도메인은 Cloudflare 가 br 압축을 하고, 직결(N1-(B))로 갈 때만 의미가 있다.
- 스모크가 해시 자산 하나의 `Cache-Control` 과 없는 자산의 404 를 본다.
- 웹폰트는 `index.html` 의 `<link rel=preconnect>` + `<link>` 로 받는다. `main.css` 의 `@import` 로 두면 CSS 를 받은 뒤에야 폰트 CSS 요청이 나간다.

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
- **embedder(5단계)**: 4번째 컨테이너. 내부 `app` 네트워크에만 붙고 nginx 경로·호스트 포트가 없다(nginx.conf 표에 행이 없는 것이 맞다). 기본 CPU 이고 GPU 는 `compose.gpu.yaml` 오버라이드다. 서버의 첫 `up` 은 이미지(약 2GB) 빌드와 모델(약 470MB) 내려받기가 붙는다 — backend 는 기다리지 않고 검색만 그동안 `degraded:["dense"]` 다. 설정 키 `EMBEDDER_URL`·`EMBEDDER_MODEL`(docs/local-dev.md).

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

## 배포 — 서버가 origin/main 을 당겨간다

서버의 systemd timer 가 `origin/main` 을 보고 있다가 커밋이 올라가면 배포한다. 사람이 직접
돌리는 경로(아래 「손으로 배포」)도 같은 `scripts/deploy.sh` 를 쓴다 — 두 경로가 갈라지지 않게.

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

### 자동 배포 (pull 방식)

```
GitHub (공개)        이 머신
─────────────        ─────────────────────────────────
tests (호스티드)      systemd timer  5분마다
                     └ scripts/watch-deploy.sh
                        HEAD 가 origin/main 을 품고 있나?
                        └ scripts/deploy.sh
                           빌드 → up → 스모크 → 실패 시 롤백
```

**GitHub 에서 이 머신으로 들어오는 경로가 없다.** 이 머신이 GitHub 를 볼 뿐이다.
인바운드 포트도, 터널도, 셀프호스티드 러너도 없다. 공개 저장소에 러너를 붙이지
않기로 한 이유는 `docs/decisions.md` 에 있다 — 요약하면 포크 PR 이 워크플로 파일
자체를 바꿔 보낼 수 있어서, 트리거를 하나라도 열어두면 이 호스트가 넘어간다.

GitHub 쪽에 남는 것은 테스트뿐이다(`.github/workflows/ci.yml`, `tests.yml`,
호스티드 러너). 배포 비밀값은 GitHub 에 올라가지 않는다 — 서버가 자기 디스크의
`.env`·`.secrets/` 를 읽는다.

`scripts/deploy.sh` 의 순서가 안전장치다.

1. 작업 트리가 더러우면 중단한다. 배포 디렉터리에서 직접 고치지 않는다는 뜻이다.
2. **빌드를 먼저 끝낸다.** 빌드가 깨져도 운영 컨테이너는 그대로다.
3. `up` 하는 순간 공개 도메인이 바뀐다(위 「함정」).
4. 스모크가 실패하면 직전 이미지로 되돌리고 종료코드 1 이다.

**자동 롤백의 한계: Flyway 는 되돌리지 않는다.** 그래서 스크립트는 **마이그레이션이
적용된 배포에서는 자동 롤백을 하지 않는다.** 되돌리면 옛 코드가 새 스키마를 만나
`ddl-auto: validate` 에서 기동 자체가 실패하기 때문이다 — 되돌리는 쪽이 더 나쁘다.
그 경우 상태와 선택지(덤프 복원 / 고쳐서 전진)를 찍고 종료코드 1 로 멈춘다.
판정은 DB 의 `flyway_schema_history` 최대 버전과 저장소 `V*.sql` 최대 번호를 비교한다
(git diff 로 보면 이미 pull 된 트리의 재배포를 놓친다). 스키마를 바꾸는 배포 전에는
아래 덤프를 뜬다.

**롤백 지점은 태그로 고정한다.** 기록하는 순간 `<project>-<svc>:rollback-<타임스탬프>`
를 박는다. 이미지 ID 만 적어두면 빌드가 태그를 가져가고 컨테이너가 교체된 뒤 그
이미지가 사라져 롤백이 `No such image` 로 죽는다 — 실제로 났다
([트러블슈팅](troubleshooting/2026-11-03-deploy-rollback-no-such-image.md)).
태그를 박으면 그 이미지는 회수되지 않으므로, 배포가 성공한 뒤 서비스별로 최근
3개만 남기고 옛 `rollback-*` 태그를 지운다(`ROLLBACK_KEEP` 으로 바꾼다). embedder
이미지는 한 장이 약 2GB 라 정리하지 않으면 디스크가 금방 찬다.

**스모크는 실패하면 20초 뒤 한 번 더 본다.** 컨테이너 교체 직후에는 앞단 프록시의
upstream 재해석이 안 끝나 연결 실패가 날 수 있다. 일시적 흔들림 하나로 운영을
되돌리는 것이 더 위험하다.

**배포가 실패한 뒤의 상태를 알아 둔다.** `deploy.sh` 는 빌드 전에 ff 머지를 하므로,
실패해도 작업 트리는 새 커밋이다. **실행 이미지가 무엇인지는 경우에 따라 다르다** —
되돌렸을 수도 있고, 마이그레이션이 적용된 배포라 되돌리지 않고 새 코드가 그대로 떠
있을 수도 있다(위 「자동 롤백의 한계」). 워처 로그는 이것을 단정하지 않으니
`deploy.sh` 쪽 로그에서 롤백 여부를 읽는다. 워처는 실패한 커밋을
`.deploy-state.failed` 에 적고 **같은 커밋으로 재시도하지 않는다** — 5분마다 같은 실패를
반복하지 않기 위해서다. 고친 뒤 그 파일을 지우거나, 다음 커밋이 올라오면 다시 돈다.

이 검사는 「변화 없음」 검사보다 **먼저** 와야 한다. `deploy.sh` 가 빌드 전에 ff 머지를
하므로 머지 뒤 실패하면 `HEAD == origin/main` 이 되고, 변화 없음을 먼저 보면 그 순간
경보가 꺼져 "구 이미지가 도는데 아무도 모르는" 상태가 된다. 지금은 사람이 치울 때까지
계속 종료코드 1 을 돌려 `systemctl --user status jobsight-deploy.service` 가 failed 로 남는다.

**변화 판정은 동등 비교가 아니라 "HEAD 가 origin/main 을 품고 있는가"다.** 운영
디렉터리가 기능 브랜치에 올라가 있거나 현장에서 커밋이 하나 얹히면 `HEAD != origin/main`
이 영원히 참이 된다. 그런데 `deploy.sh` 의 `git merge --ff-only origin/main` 은 그 경우
"Already up to date" 로 **성공**하므로, 동등 비교로는 5분마다 전체 재빌드를 되풀이한다.
`git merge-base --is-ancestor` 로 물어 그 경우 한 줄만 남기고 넘어간다. 양쪽이 갈라진
경우(ff 불가)는 그대로 배포를 시도하고 `deploy.sh` 가 ff 실패로 멈춘다.

### 워처 설치 (1회)

유닛은 `deploy/systemd/` 에 템플릿으로 있다. `@@JOBSITE_DIR@@` 를 운영 디렉터리로
바꿔서 사용자 유닛으로 설치한다. root 가 필요 없고, `loginctl enable-linger` 가
켜져 있으면 로그아웃·재부팅 뒤에도 돈다.

```bash
cd ~/jobsite-v006
mkdir -p ~/.config/systemd/user
for u in jobsight-deploy.service jobsight-deploy.timer; do
  sed "s|@@JOBSITE_DIR@@|$PWD|g" "deploy/systemd/$u" > ~/.config/systemd/user/$u
done
systemctl --user daemon-reload
systemctl --user enable --now jobsight-deploy.timer
loginctl enable-linger "$USER"      # 로그아웃해도 타이머가 살아 있게
```

상태와 로그:

```bash
systemctl --user list-timers jobsight-deploy.timer
systemctl --user status jobsight-deploy.service
journalctl --user -u jobsight-deploy.service -n 100 --no-pager
```

주기를 바꾸려면 `~/.config/systemd/user/jobsight-deploy.timer` 의 `OnUnitActiveSec`
을 고치고 `systemctl --user daemon-reload && systemctl --user restart jobsight-deploy.timer`.
잠시 멈추려면 `systemctl --user stop jobsight-deploy.timer`.


### 손으로 배포

러너가 죽었거나 특정 커밋으로 올릴 때.

```bash
cd ~/jobsite-v006
bash scripts/deploy.sh            # origin/main
bash scripts/deploy.sh <ref>      # 특정 커밋 (앞으로만 간다 — ff-only)
```

되돌아가는 배포는 아래 「롤백」을 쓴다. `deploy.sh` 는 ff-only 라 과거로 가지 않는다.

Windows 개발 환경에서는 `scripts/start.ps1`가 고정 Compose 프로젝트를 다른 worktree가 실행 중인지 먼저 확인한다. `-Takeover`는 컨테이너만 현재 작업공간 설정으로 재생성하고 DB 볼륨은 유지한다.

`smoke.sh` 는 `.env` 의 `PUBLIC_BASE_URL` 을 읽는다. 출력 첫 줄이 공개 도메인인지 확인한다.
기존 수동 OAuth callback을 유지하는 서버는 `-f compose.yaml -f compose.mcp.yaml`을 함께 사용한다.
base URL을 바꿀 때는 구 code/refresh 승계를 막는 코드도 함께 배포하고, 가이드·ZIP·GitHub 플러그인 URL을 대조한다.

### 2026-10-31 드롭다운 배포 검증

- 변경 커밋: `98b47b8` (`main` push 완료). 이력서 지원 직무·자기소개 연결 이력서의 검색 드롭다운과 관련 테스트·문서만 포함한다.
- 컨테이너에서 프런트 타입 검사·전체 테스트 95개·빌드 종료코드 0. 배포용 `docker compose -f compose.yaml -f compose.mcp.yaml build frontend`도 종료코드 0이다.
- 기존 프런트 실행 이미지를 `jobsite-v006-frontend:pre-dropdown-20261031-98b47b8`로 보관한 뒤 아래 명령으로 프런트만 교체했다. 백엔드·DB는 컨테이너 ID·이미지·시작 시각이 모두 유지됐으며, 세 컨테이너 모두 healthy다.

```bash
docker compose -f compose.yaml -f compose.mcp.yaml up -d --no-deps --no-build --wait --wait-timeout 120 frontend
bash scripts/smoke.sh https://job.donyk.link
```

- 배포 후 공개 스모크 59개 PASS(종료코드 0). `/`, `/resumes`, `/introductions`의 HTML이 새 자산을 참조하며 공개 JS(`index-KGzONS1k.js`)·CSS(`index-DQZz7-Em.css`)의 SHA256이 실행 컨테이너 파일과 일치한다.
- 공개 사이트의 실제 JS·CSS를 Chrome에서 실행하고 API만 가짜 응답으로 대체해 두 드롭다운을 검증했다. 항목 40개·휠 스크롤·끝 항목 선택·검색·연속 선택·키보드·닫기·저장 payload·390px 모바일 배치가 통과했다. 자기소개의 검색 Enter 자동 저장 방지와 긴 이름 칩도 확인했다.
- 실제 계정의 데이터 쓰기와 Google 로그인 왕복은 이번 배포에서 다시 수행하지 않았다. OAuth 설정·백엔드·DB·외부 프록시 설정은 변경하지 않았다.

이 프런트 배포만 되돌릴 때는 보관한 이미지를 `docker tag jobsite-v006-frontend:pre-dropdown-20261031-98b47b8 jobsite-v006-frontend:latest`로 지정하고 위 `up`·스모크 명령을 다시 실행한다. `--no-build`를 유지해야 보관한 이미지가 소스 빌드로 덮어써지지 않는다.

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

### 정기 백업과 복원 리허설 (경험 원본 4a 사용 배포의 선행 조건, 설계 7절 R54)

경험 원본(V25)부터 사실의 정본이 DB 에만 있다. 사용 배포 전에 아래를 한 번 끝내 둔다. **아직 수행하지 않았다(절차만 적었다).**

1. 정기 덤프(사용자 정의 형식). 서버 crontab 에 하루 한 번:
   ```bash
   cd ~/jobsite && docker compose exec -T db pg_dump -U jobsite_app -d jobsite -Fc > ~/backups/jobsite-$(date +%Y%m%d).dump
   find ~/backups -name 'jobsite-*.dump' -mtime +14 -delete   # 2주 보관
   ```
2. 다른 기기로 사본: `rsync -a ~/backups/ <다른 기기>:jobsite-backups/` (같은 디스크에만 있으면 백업이 아니다).
3. 복원 리허설: 빈 DB 에 복원해 스모크를 돈다. 운영 스택이 아니라 별도 프로젝트 이름으로 한다.
   ```bash
   docker run -d --name jobsite-restore -e POSTGRES_PASSWORD=rehearsal postgres:17-alpine
   docker cp ~/backups/jobsite-YYYYMMDD.dump jobsite-restore:/tmp/db.dump
   docker exec jobsite-restore createuser -U postgres jobsite_app
   docker exec jobsite-restore createdb -U postgres -O jobsite_app jobsite
   docker exec jobsite-restore pg_restore -U postgres -d jobsite --no-owner --role=jobsite_app /tmp/db.dump
   docker exec jobsite-restore psql -U postgres -d jobsite -c "select version, success from flyway_schema_history order by installed_rank desc limit 1"
   ```
   복원 DB 를 가리키는 별도 compose 프로젝트(다른 포트)로 백엔드를 띄워 `bash scripts/smoke.sh <그 주소>` 가 PASS 인지 본 뒤 컨테이너를 지운다.
4. 리허설 날짜와 결과를 이 절 아래에 적는다.
