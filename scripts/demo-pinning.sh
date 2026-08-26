#!/usr/bin/env bash
# Phase 2 demo: three clients, three versions, one tool - then a rollback.
# Idempotent: re-running is safe (already-exists responses are expected).
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
TOOL=data-validator

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   \033[2m%s\033[0m\n' "$*"; }

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { curl -s "$@"; }

pin() {  # pin <client> <version>
  printf '   %-9s -> %-6s  HTTP %s\n' "$1" "$2" \
    "$(code -X PUT "$BASE_URL/api/v1/clients/$1/tools/$TOOL/version" \
        -H 'Content-Type: application/json' -d "{\"version\":\"$2\"}")"
}

show() {  # show <client>
  json "$BASE_URL/api/v1/clients/$1/tools/$TOOL/version" | python3 -c "
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('   (no response)'); raise SystemExit
if d.get('status') in (404, 410):
    print(f\"   {'$1':<9} -> HTTP {d['status']}  {d['title']}\")
else:
    dep = '  [DEPRECATED]' if d.get('deprecated') else ''
    print(f\"   {d['client']:<9} -> {d['resolvedVersion']:<6} ({d['selector']:<6}) {d['artifactPath']}{dep}\")
"
}

say "0. Setup: tool + versions 1.0, 1.1, 1.2, 2.0"
code -X POST "$BASE_URL/api/v1/tools" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$TOOL\",\"description\":\"Validates inbound data files\"}" >/dev/null
for v in 1.0 1.1 1.2 2.0; do
  code -X POST "$BASE_URL/api/v1/tools/$TOOL/versions" -H 'Content-Type: application/json' \
    -d "{\"version\":\"$v\",\"artifactPath\":\"$TOOL/$v/$TOOL-$v.jar\"}" >/dev/null
done
note "(409s here just mean the versions already exist - they are immutable)"

say "1. Register three clients"
for c in client-a client-b client-c; do
  printf '   %-9s HTTP %s\n' "$c" \
    "$(code -X POST "$BASE_URL/api/v1/clients" -H 'Content-Type: application/json' -d "{\"name\":\"$c\"}")"
done

say "2. Each client pins the version it needs"
pin client-a 1.0
pin client-b 1.1
pin client-c 2.0

say "3. What does each client actually get?"
show client-a; show client-b; show client-c

say "4. client-c opts IN to floating on latest"
pin client-c latest
show client-c
note "selector is now LATEST - but the resolved version is still concrete and logged"

say "5. A new release lands: publish 3.0"
printf '   publish 3.0 -> HTTP %s\n' \
  "$(code -X POST "$BASE_URL/api/v1/tools/$TOOL/versions" -H 'Content-Type: application/json' \
      -d "{\"version\":\"3.0\",\"artifactPath\":\"$TOOL/3.0/$TOOL-3.0.jar\"}")"
show client-a; show client-b; show client-c
note "only the client that opted in moved. a and b are untouched by someone else's release."

say "6. 3.0 is bad - roll client-c back to 1.2 (one PUT, no rebuild)"
pin client-c 1.2
show client-c
printf '   3.0 itself is still published: HTTP %s\n' \
  "$(code "$BASE_URL/api/v1/tools/$TOOL/versions/3.0")"
note "rollback moved a pointer. the artifact was never mutated or deleted."

say "7. Guard rails"
printf '   pin to a version that does not exist -> HTTP %s\n' \
  "$(code -X PUT "$BASE_URL/api/v1/clients/client-a/tools/$TOOL/version" \
      -H 'Content-Type: application/json' -d '{"version":"999.0"}')"
printf '   unconfigured client resolves        -> HTTP %s\n' \
  "$(code "$BASE_URL/api/v1/clients/client-b/tools/ghost-tool/version")"
printf '   garbage version string              -> HTTP %s\n' \
  "$(code -X PUT "$BASE_URL/api/v1/clients/client-a/tools/$TOOL/version" \
      -H 'Content-Type: application/json' -d '{"version":"newest-please"}')"

say "Done."
