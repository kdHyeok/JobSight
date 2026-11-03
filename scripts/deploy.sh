#!/usr/bin/env bash
# 배포 1회분. 워처(scripts/watch-deploy.sh)와 사람이 같은 경로를 쓴다.
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

# 이미지 ID 만 적어두면 안 된다. 빌드가 태그를 가져가고 옛 컨테이너가 교체되면
# 그 이미지는 참조가 없어져 사라질 수 있다 — 2026-11-03 배포에서 실제로 롤백이
# "No such image" 로 죽었다. 기록하는 순간 태그를 박아 고정한다.
STAMP="$(date +%Y%m%d-%H%M%S)"
declare -A ROLLBACK_IMAGE=()
for svc in "${BUILT_SERVICES[@]}"; do
  cid="$(dc ps -aq "$svc" 2>/dev/null | head -1)"
  [ -n "$cid" ] || continue
  img="$(docker inspect "$cid" --format '{{.Image}}')"
  tag="${PROJECT}-${svc}:rollback-${STAMP}"
  docker tag "$img" "$tag"
  ROLLBACK_IMAGE["$svc"]="$tag"
done
log "롤백 지점"
if [ ${#ROLLBACK_IMAGE[@]} -eq 0 ]; then
  echo "   (실행 중인 컨테이너 없음 — 첫 배포로 간주한다. 자동 롤백 없음)"
else
  for svc in "${!ROLLBACK_IMAGE[@]}"; do echo "   $svc ${ROLLBACK_IMAGE[$svc]}"; done
fi

rollback() {
  # set -e 로 중간에 죽으면 반만 되돌아간 상태가 된다. 여기서만 끈다.
  set +e
  if [ ${#ROLLBACK_IMAGE[@]} -eq 0 ]; then
    echo "되돌릴 이미지가 없다. 수동 조치가 필요하다." >&2
    set -e; return 1
  fi
  log "롤백 — 직전 이미지로 되돌린다"
  local failed=0
  for svc in "${!ROLLBACK_IMAGE[@]}"; do
    if docker tag "${ROLLBACK_IMAGE[$svc]}" "${PROJECT}-${svc}:latest"; then
      echo "   $svc ← ${ROLLBACK_IMAGE[$svc]}"
    else
      echo "   $svc 태그 실패: ${ROLLBACK_IMAGE[$svc]}" >&2; failed=1
    fi
  done
  dc up -d --no-build --no-deps --wait --wait-timeout 180 "${!ROLLBACK_IMAGE[@]}" || failed=1
  echo "롤백 후 상태:"; dc ps
  [ "$failed" -eq 0 ] || echo "롤백이 온전히 끝나지 않았다. 직접 확인한다." >&2
  set -e
  return "$failed"
}

# ── 3.5 마이그레이션이 걸려 있는가 ──────────────────────────────
# git diff 로 보면 안 된다. 이미 pull 된 트리를 재배포하는 경우를 놓친다.
# 실제로 적용된 DB 버전과 저장소의 최대 버전을 비교한다.
envval() { grep -E "^$1=" .env | cut -d= -f2- | tr -d '\r'; }
# db 가 떠 있지 않으면(스택이 내려가 있거나 첫 배포) 물어볼 데가 없다. 그때는
# 0 으로 보고 넘어간다 — `|| true` 가 없으면 set -e 가 여기서 조용히 죽는다.
DB_VERSION="$(dc exec -T db psql -U "$(envval POSTGRES_USER)" -d "$(envval POSTGRES_DB)" \
  -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success" 2>/dev/null \
  | tr -dc '0-9' || true)"
DB_VERSION="${DB_VERSION:-0}"
REPO_VERSION="$(ls backend/src/main/resources/db/migration/V*__*.sql 2>/dev/null \
  | sed -E 's|.*/V([0-9]+)__.*|\1|' | sort -n | tail -1)"
REPO_VERSION="${REPO_VERSION:-0}"
if [ "$REPO_VERSION" -gt "$DB_VERSION" ]; then
  MIGRATION_PENDING=yes
  log "주의: 마이그레이션이 적용된다 (DB V$DB_VERSION → 저장소 V$REPO_VERSION)"
  echo "   실패해도 자동 롤백하지 않는다 — 옛 코드가 새 스키마를 만나면 기동이 깨진다."
else
  MIGRATION_PENDING=no
  echo "마이그레이션 없음 (DB V$DB_VERSION, 저장소 V$REPO_VERSION)"
fi

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
# 컨테이너를 막 교체한 직후라 앞단 프록시의 upstream 재해석이 아직 안 끝났을 수
# 있다(docs/nginx-deploy.md 「resolver」). 실제로 2026-11-03 배포에서 66개 중
# 1개가 연결 실패(actual=000)로 떨어졌다가 곧바로 통과했다. 되돌리는 쪽이 더
# 위험하므로 한 번은 가라앉히고 다시 본다.
if [ "$SMOKE" -ne 0 ]; then
  echo "스모크 1차 실패($SMOKE). 20초 가라앉히고 한 번만 다시 본다."
  sleep 20
  bash scripts/smoke.sh
  SMOKE=$?
fi
set -e
echo "SMOKE_EXIT=$SMOKE"

if [ "$SMOKE" -ne 0 ]; then
  if [ "$MIGRATION_PENDING" = yes ]; then
    log "자동 롤백하지 않는다 — 이 배포는 마이그레이션을 포함했다"
    cat >&2 <<MSG
스키마는 이미 V$REPO_VERSION 이다. 이미지만 옛것으로 되돌리면 옛 코드의
ddl-auto: validate 가 새 스키마와 어긋나 기동이 아예 실패한다. 되돌리는 쪽이
지금보다 나쁘다. 사람이 판단한다.

지금 상태: 새 코드가 떠 있고 스모크가 실패했다.
  - 되돌리려면: 덤프 복원 + 아래 이미지 태그
$(for svc in "${!ROLLBACK_IMAGE[@]}"; do echo "      $svc  ${ROLLBACK_IMAGE[$svc]}"; done)
  - 앞으로 가려면: 실패 항목을 고쳐 새 커밋으로 다시 배포한다.
MSG
    exit 1
  fi
  rollback || true
  die "스모크 실패($SMOKE). 롤백했다. 배포는 완료되지 않았다."
fi

# ── 7. 옛 롤백 태그 정리 ────────────────────────────────────────
# 태그를 박는다는 것은 그 이미지가 영원히 회수되지 않는다는 뜻이다. 배포마다
# 서비스당 한 장씩 쌓이고 embedder 는 한 장이 약 2GB 다. 최근 것만 남긴다.
# 실패한 배포에서는 여기까지 오지 않으므로 되돌릴 태그를 지울 일이 없다.
ROLLBACK_KEEP="${ROLLBACK_KEEP:-3}"
for svc in "${BUILT_SERVICES[@]}"; do
  mapfile -t stale < <(docker images --format '{{.Tag}}' "${PROJECT}-${svc}" 2>/dev/null \
    | grep '^rollback-' | sort -r | tail -n "+$((ROLLBACK_KEEP + 1))")
  for t in "${stale[@]}"; do
    if docker rmi "${PROJECT}-${svc}:$t" >/dev/null 2>&1; then
      echo "   옛 롤백 태그 정리 ${PROJECT}-${svc}:$t"
    fi
  done
done

log "배포 완료  $AFTER"
