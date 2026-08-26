#!/usr/bin/env bash
# Seeds the demo tool + the four versions used throughout the project.
# Deliberately uses the public REST API rather than SQL inserts, so it
# doubles as a smoke test of the running service.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1. Registering tool 'data-validator'"
curl -sS -X POST "$BASE_URL/api/v1/tools" \
  -H 'Content-Type: application/json' \
  -d '{"name":"data-validator","description":"Validates inbound data files against a schema"}' \
  -w '\n-> HTTP %{http_code}\n' || true

for v in 1.0 1.1 1.2 2.0; do
  say "2. Publishing data-validator $v"
  curl -sS -X POST "$BASE_URL/api/v1/tools/data-validator/versions" \
    -H 'Content-Type: application/json' \
    -d "{\"version\":\"$v\",\"artifactPath\":\"data-validator/$v/data-validator-$v.jar\"}" \
    -w '\n-> HTTP %{http_code}\n' || true
done

say "3. Exact lookup of 1.2 (expect 200)"
curl -sS "$BASE_URL/api/v1/tools/data-validator/versions/1.2" -w '\n-> HTTP %{http_code}\n'

say "4. Lookup of 999.0 (expect 404, NOT a fallback to 2.0)"
curl -sS "$BASE_URL/api/v1/tools/data-validator/versions/999.0" -w '\n-> HTTP %{http_code}\n'

say "5. Re-publishing 1.2 (expect 409 - immutable)"
curl -sS -X POST "$BASE_URL/api/v1/tools/data-validator/versions" \
  -H 'Content-Type: application/json' \
  -d '{"version":"1.2","artifactPath":"data-validator/1.2/TAMPERED.jar"}' \
  -w '\n-> HTTP %{http_code}\n'

say "Done."
