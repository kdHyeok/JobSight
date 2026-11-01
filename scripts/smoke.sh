#!/usr/bin/env bash
# 배포·설정 변경 뒤 반드시 통과해야 하는 최소 검증.
#
#   bash scripts/smoke.sh                 # .env 의 PUBLIC_BASE_URL 사용
#   bash scripts/smoke.sh https://job.donyk.link
#
# 이 스크립트가 잡는 것은 이 프로젝트에서 실제로 반복됐던 실패들이다:
#   - nginx 가 포트 없는 절대 URL 로 리다이렉트 (redirect_uri_mismatch, /admin 302)
#   - SecurityConfig 매처 누락으로 미인증 접근이 통과
#   - 끝 슬래시 경로가 500
#   - CSRF 토큰 없이 변경 요청이 통과
#   - 세션 쿠키 SameSite=Strict 로 OAuth 콜백이 깨짐
# 종료코드 0 = 전부 통과. 하나라도 실패하면 1.

set -u
cd "$(dirname "$0")/.."

curl() { command curl --connect-timeout 5 --max-time 15 "$@"; }

BASE="${1:-}"
if [ -z "$BASE" ] && [ -f .env ]; then
  BASE="$(grep -E '^PUBLIC_BASE_URL=' .env | cut -d= -f2- | tr -d '\r')"
fi
BASE="${BASE:-http://127.0.0.1:8088}"
BASE="${BASE%/}"

fail=0
pass=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then
    printf '  ok    %-48s %s\n' "$1" "$3"; pass=$((pass+1))
  else
    printf '  FAIL  %-48s expected=%s actual=%s\n' "$1" "$2" "$3"; fail=1
  fi
}
code()     { curl -sS -o /dev/null -w '%{http_code}' "$@"; }
header()   { curl -sS -o /dev/null -D - "$1" 2>/dev/null | tr -d '\r' | awk -v k="$(echo "$2" | tr 'A-Z' 'a-z'):" 'tolower($1)==k {sub(/^[^ ]+ /,""); print}'; }
urldecode(){ sed 's/%3A/:/gi; s/%2F/\//gi'; }

echo "smoke @ $BASE"
echo ""

echo "[가용성]"
check "GET /health"                         "200"  "$(code "$BASE/health")"
check "GET /api/auth/me"                    "200"  "$(code "$BASE/api/auth/me")"
check "  me.authenticated (쿠키 없음)"       "false" "$(curl -sS "$BASE/api/auth/me" | grep -o '"authenticated":[a-z]*' | cut -d: -f2)"

echo ""
echo "[미인증 차단]"
check "POST /mcp (Bearer 없음)"             "401" "$(code -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/mcp")"
check "GET MCP resource metadata"            "200" "$(code "$BASE/.well-known/oauth-protected-resource")"
# 401 의 scope 힌트를 그대로 쓰는 클라이언트가 많다. read 만 광고하면 쓰기 도구가 통째로 사라진다.
mcp_challenge="$(curl -sS -o /dev/null -D - -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/mcp" \
  | tr -d '\r' | grep -i '^www-authenticate:' || true)"
case "$mcp_challenge" in
  *"jobsight.read jobsight.write"*) check "MCP challenge advertises write" "read+write" "read+write" ;;
  *) check "MCP challenge advertises write" "read+write" "${mcp_challenge:-missing}" ;;
esac
case "$mcp_challenge" in
  *"resource_metadata=\"$BASE/.well-known/oauth-protected-resource\""*) check "MCP challenge canonical metadata URL" "match" "match" ;;
  *) check "MCP challenge canonical metadata URL" "match" "missing" ;;
esac
check "MCP same-origin Bearer requirement" "401" "$(code -X POST -H "Origin: $BASE" -H 'Content-Type: application/json' -d '{}' "$BASE/mcp")"
check "MCP foreign Origin rejected" "403" "$(code -X POST -H 'Origin: https://untrusted.example' -H 'Content-Type: application/json' -d '{}' "$BASE/mcp")"
check "GET path-specific resource metadata" "200" "$(code "$BASE/.well-known/oauth-protected-resource/mcp")"
check "GET OAuth server metadata"            "200" "$(code "$BASE/.well-known/oauth-authorization-server")"
# 미인증 authorize 는 Google 로그인으로 보낸다. Tomcat 이 절대 URL 로 바꾸면 앞단 스킴을
# 잘못 잡아 http:// 로 나가고, HSTS 를 모르는 OAuth 클라이언트가 평문 홉에서 끊긴다.
mcp_az_location="$(curl -sS -o /dev/null -D - \
  "$BASE/oauth2/authorize?response_type=code&client_id=jobsight-plugin&redirect_uri=https%3A%2F%2Fchatgpt.com%2Fconnector_platform_oauth_redirect&scope=jobsight.read+jobsight.write&state=smoke&resource=$(printf '%s' "$BASE/mcp" | sed 's/:/%3A/g; s#/#%2F#g')&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256" \
  2>/dev/null | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')"
# BASE 가 평문이면(로컬 8088) http Location 이 정상이다. 다운그레이드만 잡는다.
case "$mcp_az_location" in
  "")       az_actual="(없음)" ;;
  http://*) case "$BASE" in https://*) az_actual="$mcp_az_location" ;; *) az_actual="relative-or-https" ;; esac ;;
  *)        az_actual="relative-or-https" ;;
esac
check "authorize 진입점 Location (평문 금지)" "relative-or-https" "$az_actual"
oauth_metadata="$(curl -fsS "$BASE/.well-known/oauth-authorization-server" | tr -d '\r\n\t ')"
resource_metadata="$(curl -fsS "$BASE/.well-known/oauth-protected-resource" | tr -d '\r\n\t ')"
for field in "\"issuer\":\"$BASE\"" "\"authorization_endpoint\":\"$BASE/oauth2/authorize\"" "\"token_endpoint\":\"$BASE/oauth2/token\""; do
  case "$oauth_metadata" in
    *"$field"*) check "OAuth $field" "present" "present" ;;
    *) check "OAuth $field" "present" "missing" ;;
  esac
done
check "OAuth PKCE S256 advertised" "S256" "$(printf '%s' "$oauth_metadata" | grep -o '"code_challenge_methods_supported":\[[^]]*\]' | grep -o 'S256')"
check "OAuth refresh_token advertised" "refresh_token" "$(printf '%s' "$oauth_metadata" | grep -o '"grant_types_supported":\[[^]]*\]' | grep -o 'refresh_token')"
check "OAuth CIMD discovery" "true" "$(printf '%s' "$oauth_metadata" | grep -o '"client_id_metadata_document_supported":true' | cut -d: -f2)"
case "$oauth_metadata" in
  *'"token_endpoint_auth_methods_supported":["none"]'*) check "OAuth public client auth" "none" "none" ;;
  *) check "OAuth public client auth" "none" "missing" ;;
esac
check "GET plugin guide" "200" "$(code "$BASE/plugin")"
check "GET plugin config" "200" "$(code "$BASE/plugin/config")"
plugin_config="$(curl -fsS "$BASE/plugin/config" | tr -d '\r\n\t ')"
for field in "\"mcpUrl\":\"$BASE/mcp\"" "\"authorizationUrl\":\"$BASE/oauth2/authorize\"" "\"tokenUrl\":\"$BASE/oauth2/token\""; do
  case "$plugin_config" in
    *"$field"*) check "Plugin $field" "present" "present" ;;
    *) check "Plugin $field" "present" "missing" ;;
  esac
done
check "GET plugin skill" "200" "$(code "$BASE/plugin/skill")"
check "GET plugin package" "200" "$(code "$BASE/plugin/download")"
case "$resource_metadata" in
  *"\"resource\":\"$BASE/mcp\""*) check "MCP resource == BASE/mcp" "match" "match" ;;
  *) check "MCP resource == BASE/mcp" "match" "missing" ;;
esac
case "$resource_metadata" in
  *"\"authorization_servers\":[\"$BASE\"]"*) check "MCP authorization server == BASE" "match" "match" ;;
  *) check "MCP authorization server == BASE" "match" "missing" ;;
esac
for path in '/.well-known/oauth-authorization-server' '/.well-known/oauth-protected-resource' '/plugin/config'; do
  case "$(header "$BASE$path" Content-Type)" in
    application/json*) check "JSON response $path" "JSON" "JSON" ;;
    *) check "JSON response $path" "JSON" "missing" ;;
  esac
done
check "GET /api/companies"                  "401"  "$(code "$BASE/api/companies")"
check "GET /api/companies/{id}/contents"    "401"  "$(code "$BASE/api/companies/00000000-0000-0000-0000-000000000000/contents")"
check "GET /api/postings"                   "401"  "$(code "$BASE/api/postings")"
check "GET /api/positions"                  "401"  "$(code "$BASE/api/positions")"
check "GET /api/resumes"                    "401"  "$(code "$BASE/api/resumes")"
check "GET /api/self-introductions"         "401"  "$(code "$BASE/api/self-introductions")"
check "GET /api/attachments/x"              "401"  "$(code "$BASE/api/attachments/x")"
check "GET /api/references"                 "401"  "$(code "$BASE/api/references")"
check "GET /api/requests"                   "401"  "$(code "$BASE/api/requests")"
check "GET /api/admin/users"                "401"  "$(code "$BASE/api/admin/users")"
check "GET /api/admin/requests"             "401"  "$(code "$BASE/api/admin/requests")"
check "GET /api/admin/users/counts"         "401"  "$(code "$BASE/api/admin/users/counts")"
check "GET /api/admin/settings"             "401"  "$(code "$BASE/api/admin/settings")"
check "GET /swagger-ui/index.html"          "401"  "$(code "$BASE/swagger-ui/index.html")"
check "GET /v3/api-docs"                    "401"  "$(code "$BASE/v3/api-docs")"
check "POST /api/companies (CSRF 없음)"     "403"  "$(code -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/companies")"
check "PATCH /api/auth/me (CSRF 없음)"       "403"  "$(code -X PATCH -H 'Content-Type: application/json' -d '{}' "$BASE/api/auth/me")"
check "POST /api/auth/refresh (CSRF 없음)"   "403"  "$(code -X POST "$BASE/api/auth/refresh")"
check "DELETE /api/admin/users/x (CSRF 없음)" "403"  "$(code -X DELETE "$BASE/api/admin/users/00000000-0000-0000-0000-000000000000")"

echo ""
echo "[리다이렉트 — 절대 URL 금지]"
check "GET /admin -> status"                "302"  "$(code "$BASE/admin")"
check "GET /admin -> Location (상대 경로)"   "/"    "$(header "$BASE/admin" Location)"

echo ""
echo "[경로 견고성 — 500 이 나오면 안 된다]"
# 미인증이라 보안 필터가 먼저 401 을 낸다. 인증 세션에서는 NoResourceFoundException 핸들러가 404 를 낸다.
check "GET /api/companies/ (끝 슬래시)"     "401"  "$(code "$BASE/api/companies/")"
check "GET /api/nope"                       "401"  "$(code "$BASE/api/nope")"

echo ""
echo "[Google OAuth]"
google_enabled="$(curl -sS "$BASE/api/auth/login-options" | grep -o '"googleEnabled":[a-z]*' | cut -d: -f2)"
if [ "$google_enabled" = "true" ]; then
  loc="$(header "$BASE/oauth2/authorization/google" Location)"
  redirect_uri="$(printf '%s' "$loc" | tr '&?' '\n\n' | grep '^redirect_uri=' | cut -d= -f2- | urldecode)"
  check "GET /oauth2/authorization/google"    "302"  "$(code "$BASE/oauth2/authorization/google")"
  check "  redirect_uri == PUBLIC_BASE_URL+callback" "$BASE/login/oauth2/code/google" "$redirect_uri"
  cookies="$(curl -sS -o /dev/null -D - "$BASE/oauth2/authorization/google" 2>/dev/null | tr -d '\r' | grep -i '^set-cookie:' | grep -i 'JSESSIONID')"
  case "$cookies" in
    *SameSite=Lax*) check "  JSESSIONID SameSite" "Lax" "Lax" ;;
    *SameSite=Strict*) check "  JSESSIONID SameSite" "Lax" "Strict" ;;
    *) check "  JSESSIONID SameSite" "Lax" "(없음)" ;;
  esac
  case "$cookies" in
    *[Dd][Oo][Mm][Aa][Ii][Nn]=*) check "  JSESSIONID host-only" "yes" "no" ;;
    *) check "  JSESSIONID host-only" "yes" "yes" ;;
  esac
  case "$BASE" in https://*)
    case "$cookies" in
      *Secure*) check "  JSESSIONID Secure (HTTPS)" "yes" "yes" ;;
      *) check "  JSESSIONID Secure (HTTPS)" "yes" "no" ;;
    esac ;;
  esac
else
  printf '  skip  %-48s %s\n' "Google 미설정 (login-options.googleEnabled=false)" "-"
  check "GET /oauth2/authorization/google (미설정)" "404" "$(code "$BASE/oauth2/authorization/google")"
fi

echo ""
if [ "$fail" -eq 0 ]; then
  echo "PASS  ($pass checks)"
else
  echo "FAIL  — 위의 FAIL 항목을 고치기 전에는 배포하지 않는다."
fi
exit "$fail"
