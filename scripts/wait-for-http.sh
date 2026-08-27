#!/usr/bin/env bash
# Poll an HTTP endpoint until it answers, or fail with a real diagnosis.
#
#   ./scripts/wait-for-http.sh http://localhost:8081/actuator/health 90
#
# This replaces `sleep 40`. A fixed sleep is wrong in both directions at once:
# too short and it is flaky, too long and it is wasted, and on a loaded runner
# it manages to be both on different days. Polling returns the moment the
# service is actually ready, and fails fast with output when it never will be.
set -uo pipefail

URL="${1:?usage: wait-for-http.sh <url> [timeout-seconds]}"
TIMEOUT="${2:-90}"
START=$(date +%s)

printf 'waiting for %s (timeout %ss)' "$URL" "$TIMEOUT"
while true; do
  if curl -sf --max-time 5 "$URL" >/dev/null 2>&1; then
    printf '\nready after %ss\n' "$(( $(date +%s) - START ))"
    exit 0
  fi
  ELAPSED=$(( $(date +%s) - START ))
  if [ "$ELAPSED" -ge "$TIMEOUT" ]; then
    printf '\nTIMED OUT after %ss\n' "$ELAPSED"
    curl -sS --max-time 5 "$URL" || true
    exit 1
  fi
  printf '.'
  sleep 1
done
