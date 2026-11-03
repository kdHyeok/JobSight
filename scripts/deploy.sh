#!/usr/bin/env bash
# 배포 1회분. CD 워크플로(.github/workflows/cd.yml)와 사람이 같은 경로를 쓴다.
#
#   bash scripts/deploy.sh                 # origin/main 으로 배포
#   bash scripts/deploy.sh <ref>           # 특정 커밋/태그로 배포
#
# 순서가 곧 안전장치다:
#   1) 빌드를 먼저 끝낸다 — 빌드가 깨지면 운영 컨테이너를 건드리지 않는다.
#   2) up 하는 순간 공개 도메인이 바뀐다(docs/nginx-deploy.md 「함정」).
#   3) 스모크가 실패하면 직전 이미지로 되돌리고 종료코드 1 이다.
#
# 되돌리지 못하는 것: Flyway 는 되돌림이 없다. 스키마를 바꾼 배포는 이 스크립트의
# 자동 롤백으로 복구되지 않는다 — 코드만 돌아가고 스키마는 남는다.
set -Eeuo pipefail

cd "$(dirname "$0")/.."
TARGET="${1:-origin/main}"

COMPOSE_FILES=(-f compose.yaml)
[ -f compose.mcp.yaml ] && COMPOSE_FILES+=(-f compose.mcp.yaml)
dc() { docker compose "${COMPOSE_FILES[@]}" "$@"; }

log() { printf '\n== %s\n' "$*"; }
die() { printf '\n!! %s\n' "$*" >&2; exit 1; }

# ── 1. 선행 조건 ────────────────────────────────────────────────
[ -f .env ] || die ".env 가 없다. docs/nginx-deploy.md 「서버 준비」를 먼저 한다."
[ -d .secrets ] || die ".secrets/ 가 없다. docs/nginx-deploy.md 「서버 준비」를 먼저 한다."
[ -z "$(git status --porcelain)" ] || die "작업 트리가 깨끗하지 않다. 배포 디렉터리에서 직접 수정하지 않는다."

# ── 2. 소스 갱신 ────────────────────────────────────────────────
log "소스 갱신 → $TARGET"
git fetch origin --prune
BEFORE="$(git rev-parse HEAD)"
git merge --ff-only "$TARGET"
AFTER="$(git rev-parse HEAD)"
echo "$BEFORE → $AFTER"
if [ "$BEFORE" = "$AFTER" ]; then
  echo "(커밋 변화 없음 — 재빌드만 한다)"
else
  git --no-pager log --oneline "$BEFORE..$AFTER" | sed 's/^/   /'
fi

# 적용될 마이그레이션이 있으면 눈에 띄게 알린다. 자동 롤백 범위 밖이다.
if [ "$BEFORE" != "$AFTER" ] && \
   git diff --name-only "$BEFORE" "$AFTER" -- backend/src/main/resources/db/migration | grep -q .; then
  log "주의: 이 배포는 마이그레이션을 포함한다. 자동 롤백이 스키마를 되돌리지 않는다."
  git diff --name-only "$BEFORE" "$AFTER" -- backend/src/main/resources/db/migration | sed 's/^/   /'
fi

# ── 3. 롤백 지점 기록 ───────────────────────────────────────────
# 빌드하는 서비스만. db 는 공식 이미지라 재빌드하지 않는다.
PROJECT="$(dc config --format json | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])")"
mapfile -t BUILT_SERVICES < <(dc config --format json | python3 -c \
  "import json,sys; [print(n) for n,s in json.load(sys.stdin)['services'].items() if s.get('build')]")

declare -A ROLLBACK_IMAGE=()
for svc in "${BUILT_SERVICES[@]}"; do
  cid="$(dc ps -aq "$svc" 2>/dev/null | head -1)"
  if [ -n "$cid" ]; then
    ROLLBACK_IMAGE["$svc"]="$(docker inspect "$cid" --format '{{.Image}}')"
  fi
done
log "롤백 지점"
if [ ${#ROLLBACK_IMAGE[@]} -eq 0 ]; then
  echo "   (실행 중인 컨테이너 없음 — 첫 배포로 간주한다. 자동 롤백 없음)"
else
  for svc in "${!ROLLBACK_IMAGE[@]}"; do echo "   $svc ${ROLLBACK_IMAGE[$svc]}"; done
fi

rollback() {
  [ ${#ROLLBACK_IMAGE[@]} -gt 0 ] || { echo "되돌릴 이미지가 없다. 수동 조치가 필요하다." >&2; return; }
  log "롤백 — 직전 이미지로 되돌린다"
  for svc in "${!ROLLBACK_IMAGE[@]}"; do
    docker tag "${ROLLBACK_IMAGE[$svc]}" "${PROJECT}-${svc}:latest"
  done
  dc up -d --no-build --no-deps --wait --wait-timeout 180 "${!ROLLBACK_IMAGE[@]}" || true
  echo "롤백 후 상태:"; dc ps
}

# ── 4. 빌드 (운영 컨테이너를 건드리지 않는다) ───────────────────
log "빌드"
dc build
echo "빌드 종료코드 0"

# ── 5. 교체 — 이 시점에 공개 도메인이 바뀐다 ────────────────────
log "교체 (공개 반영)"
if ! dc up -d --wait --wait-timeout 300; then
  echo "컨테이너가 healthy 가 되지 못했다." >&2
  dc ps
  rollback
  exit 1
fi
dc ps

# ── 6. 스모크 — 통과해야 배포 완료다 ────────────────────────────
log "스모크"
set +e
bash scripts/smoke.sh
SMOKE=$?
set -e
echo "SMOKE_EXIT=$SMOKE"
if [ "$SMOKE" -ne 0 ]; then
  rollback
  die "스모크 실패($SMOKE). 롤백했다. 배포는 완료되지 않았다."
fi

log "배포 완료  $AFTER"
