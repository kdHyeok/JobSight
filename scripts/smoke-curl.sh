#!/usr/bin/env bash

# Only unauthenticated smoke probes use this; never replay authenticated writes.
curl() {
  local attempt output status
  for attempt in 1 2 3; do
    if output="$(command curl --connect-timeout 5 --max-time 15 "$@")"; then
      printf '%s\n' "$output"
      return 0
    else
      status=$?
    fi
    # Discard failed attempts: partial headers must not reach cookie checks.
    if [ "$status" -ne 28 ] || [ "$attempt" -eq 3 ]; then return "$status"; fi
    printf 'smoke: retrying curl timeout (%s/2)\n' "$attempt" >&2
    sleep 1
  done
}
