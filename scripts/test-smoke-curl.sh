#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/smoke-curl.sh

fixture_dir="$(mktemp -d)"
trap 'rm -f -- "$fixture_dir/curl" "$fixture_dir/state" "$fixture_dir/stderr"; rmdir -- "$fixture_dir"' EXIT
export SMOKE_CURL_TEST_STATE="$fixture_dir/state"
export PATH="$fixture_dir:$PATH"

cat > "$fixture_dir/curl" <<'FIXTURE'
#!/usr/bin/env bash
count=0
if [ -f "$SMOKE_CURL_TEST_STATE" ]; then read -r count < "$SMOKE_CURL_TEST_STATE"; fi
count=$((count + 1))
printf '%s\n' "$count" > "$SMOKE_CURL_TEST_STATE"
case "$SMOKE_CURL_TEST_CASE" in
  recovered)
    if [ "$count" -lt 3 ]; then
      printf 'Set-Cookie: test=partial; SameSite=Strict\n'
      exit 28
    fi
    printf 'Set-Cookie: test=complete; SameSite=Lax; Secure\n' ;;
  timeout) printf 'partial response\n'; exit 28 ;;
  certificate) printf 'partial response\n'; exit 60 ;;
  http_error) printf 'partial response\n'; exit 22 ;;
  http_500) printf '500\n' ;;
  success) printf '200\n' ;;
esac
FIXTURE
chmod +x "$fixture_dir/curl"

passed=0
run_case() {
  local label="$1" expected_status="$2" expected_calls="$3" expected_output="$4"
  local status=0 output
  export SMOKE_CURL_TEST_CASE="$label"
  rm -f -- "$fixture_dir/state"
  output="$(curl -sS https://smoke-fixture.invalid/health 2>"$fixture_dir/stderr")" || status=$?
  if [ "$status" -ne "$expected_status" ] || [ "$(cat "$fixture_dir/state")" -ne "$expected_calls" ] || [ "$output" != "$expected_output" ]; then
    printf 'FAIL %s: status=%s calls=%s output=%s\n' "$label" "$status" "$(cat "$fixture_dir/state")" "$output" >&2
    exit 1
  fi
  if [ "$expected_calls" -gt 1 ]; then
    [ "$(grep -c 'smoke: retrying curl timeout' "$fixture_dir/stderr")" -eq 2 ]
  fi
  passed=$((passed + 1))
  printf 'ok %s\n' "$label"
}

run_case recovered 0 3 'Set-Cookie: test=complete; SameSite=Lax; Secure'
run_case timeout 28 3 ''
run_case certificate 60 1 ''
run_case http_error 22 1 ''
run_case http_500 0 1 '500'
run_case success 0 1 '200'
printf 'PASS (%s smoke curl cases)\n' "$passed"
