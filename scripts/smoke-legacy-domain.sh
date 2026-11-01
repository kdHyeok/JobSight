#!/usr/bin/env bash
# The old origin must never forward a code, Bearer header, or token POST to the new origin.
set -u
BASE="${1:-https://job.donhse.duckdns.org}"
CANONICAL="https://job.donyk.link"
fail=0
pass=0
check() {
  if [ "$2" = "$3" ]; then printf 'ok   %s\n' "$1"; pass=$((pass+1));
  else printf 'FAIL %s expected=%s actual=%s\n' "$1" "$2" "$3"; fail=1; fi
}
probe() {
  response="$(curl -sS --connect-timeout 5 --max-time 15 -D - -o /dev/null "$@")"
  status="$(printf '%s' "$response" | tr -d '\r' | awk '/^HTTP\// {code=$2} END {print code}')"
  location="$(printf '%s' "$response" | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')"
}
probe "$BASE/health"
check 'legacy health' 200 "$status"
for path in / /plugin /companies /oauth2/authorization/google; do
  probe "$BASE$path?code=smoke-code&state=smoke-state&token=smoke-token"
  check "browser $path status" 302 "$status"
  check "browser $path drops query" "$CANONICAL$path" "$location"
done
probe "$BASE/login/oauth2/code/google?code=smoke-code&state=smoke-state"
check 'legacy callback' 302 "$status"
check 'callback drops code and state' "$CANONICAL/?authError=DOMAIN_CHANGED" "$location"
probe -X POST -H 'Authorization: Bearer smoke-not-a-token' -H 'Content-Type: application/json' -d '{}' "$BASE/mcp"
check 'legacy Bearer challenge' 401 "$status"
check 'legacy Bearer never redirects' '' "$location"
case "$response" in
  *"resource_metadata=\"$CANONICAL/.well-known/oauth-protected-resource\""*) check 'new resource challenge' yes yes ;;
  *) check 'new resource challenge' yes no ;;
esac
for path in /oauth2/token /oauth2/authorize /oauth2/revoke /oauth2/introspect; do
  probe -X POST -d 'code=smoke-code&refresh_token=smoke-not-a-token' "$BASE$path"
  check "legacy protocol $path" 400 "$status"
  check "legacy protocol $path never redirects" '' "$location"
done
probe -X POST -d '{}' "$BASE/api/companies"
check 'legacy mutation gone' 410 "$status"
check 'legacy mutation never redirects' '' "$location"
probe -X POST -d '{}' "$BASE/companies"
check 'browser path does not redirect POST' 410 "$status"
metadata="$(curl -fsS --connect-timeout 5 --max-time 15 "$BASE/.well-known/oauth-protected-resource" | tr -d '\r\n\t ')"
case "$metadata" in
  *"\"resource\":\"$CANONICAL/mcp\""*) check 'legacy discovery points to canonical resource' yes yes ;;
  *) check 'legacy discovery points to canonical resource' yes no ;;
esac
if [ "$fail" -eq 0 ]; then printf 'PASS (%s checks)\n' "$pass"; else printf 'FAIL legacy-domain policy\n'; fi
exit "$fail"
