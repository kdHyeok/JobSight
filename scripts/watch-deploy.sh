#!/usr/bin/env bash
# origin/main 이 움직였으면 배포한다. systemd timer 가 주기적으로 부른다.
#
# 방향이 핵심이다: GitHub 가 이 머신을 부르지 않는다. 이 머신이 GitHub 를 본다.
# 공개 저장소에 셀프호스티드 러너를 붙이지 않는 이유는 docs/decisions.md 에 있다.
#
#   bash scripts/watch-deploy.sh          # 바뀌었으면 배포, 아니면 조용히 종료
#
# 종료코드: 0 = 변화 없음 또는 배포 성공, 1 = 배포 실패(스모크 실패 포함).
set -Eeuo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"
STATE="$REPO/.deploy-state"

# 겹쳐 돌지 않게. 빌드가 길어 타이머가 또 깨어날 수 있다.
exec 9>"$STATE.lock"
if ! flock -n 9; then
  echo "이미 배포가 돌고 있다. 건너뛴다."
  exit 0
fi

git fetch origin --quiet --prune
LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"

# 실패 검사가 먼저다. deploy.sh 는 빌드 전에 ff 머지를 하므로, 머지 뒤에 실패하면
# HEAD 는 이미 새 커밋이다. 변화 없음 검사를 먼저 두면 그 순간 경보가 꺼지고
# "구 이미지가 도는데 조용한" 상태가 된다. 사람이 고치고 이 파일을 지울 때까지
# 계속 1 을 돌려 systemd 가 failed 로 남게 한다.
if [ -f "$STATE.failed" ] && [ "$(cat "$STATE.failed")" = "$REMOTE" ]; then
  echo "$REMOTE 배포가 실패한 상태 그대로다. 무엇이 떠 있는지는 실패한 실행의 로그를 본다." >&2
  echo "고친 뒤 $STATE.failed 를 지우면 다시 시도한다." >&2
  exit 1
fi

# 단순 동등 비교로는 안 된다. HEAD 가 origin/main 보다 앞서 있으면(기능 브랜치에
# 올라가 있거나 현장에서 커밋했을 때) 영원히 "다르다"가 되고, deploy.sh 의 ff 머지는
# no-op 으로 성공해 깨어날 때마다 전체 재빌드를 되풀이한다. "HEAD 가 origin/main 을
# 이미 품고 있는가"로 묻는다.
if git merge-base --is-ancestor "$REMOTE" "$LOCAL"; then
  # 앞서 있는 상태는 고쳐야 할 상황이니 알리되, 1분마다 같은 줄을 찍으면 저널에서
  # 진짜 배포 로그가 묻힌다. 커밋이 바뀔 때 한 번만 적는다.
  if [ "$LOCAL" != "$REMOTE" ] && [ "$(cat "$STATE.ahead" 2>/dev/null)" != "$LOCAL" ]; then
    echo "HEAD 가 origin/main 보다 앞서 있다($LOCAL). 배포할 것이 없다." >&2
    echo "$LOCAL" > "$STATE.ahead"
  fi
  exit 0
fi

echo "=== $(date -Is)  $LOCAL → $REMOTE  배포 시작"
if bash scripts/deploy.sh origin/main; then
  rm -f "$STATE.failed"
  echo "$REMOTE" > "$STATE.ok"
  echo "=== $(date -Is)  배포 성공 $REMOTE"
else
  echo "$REMOTE" > "$STATE.failed"
  # 무엇이 떠 있는지 여기서 단정하지 않는다. deploy.sh 는 되돌리고 실패할 수도,
  # 마이그레이션이 적용돼 되돌리지 않고 실패할 수도 있다(새 코드가 그대로 떠 있다).
  echo "=== $(date -Is)  배포 실패 $REMOTE — 위 deploy.sh 로그에서 롤백 여부를 확인한다." >&2
  exit 1
fi
