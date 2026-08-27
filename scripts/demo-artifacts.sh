#!/usr/bin/env bash
# Phase 3 demo: the full release pipeline for one version, end to end.
#   register -> DRAFT -> upload bytes -> promote -> pin -> client downloads
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
TOOL=data-validator
VERSION="${VERSION:-4.0}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   \033[2m%s\033[0m\n' "$*"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# A stand-in for a real build output.
ARTIFACT="$WORK/$TOOL-$VERSION.jar"
printf 'data-validator %s build output\n' "$VERSION" > "$ARTIFACT"
LOCAL_SHA=$(sha256sum "$ARTIFACT" | cut -d' ' -f1)

say "0. Built an artifact locally"
echo "   file   : $(basename "$ARTIFACT") ($(wc -c < "$ARTIFACT") bytes)"
echo "   sha256 : $LOCAL_SHA"

say "1. Register version $VERSION as DRAFT (metadata only, no bytes yet)"
printf '   HTTP %s\n' "$(code -X POST "$BASE_URL/api/v1/tools/$TOOL/versions" \
  -H 'Content-Type: application/json' \
  -d "{\"version\":\"$VERSION\",\"artifactPath\":\"$TOOL/$VERSION/$TOOL-$VERSION.jar\",\"status\":\"DRAFT\"}")"

say "2. Try to PUBLISH before uploading (expect 409 - the release gate)"
curl -s -X POST "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/promotion" \
  -H 'Content-Type: application/json' -d '{"status":"PUBLISHED"}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('  ', d.get('status'), '-', d.get('detail'))"

say "3. Upload the bytes (this is what CI does after a green build)"
curl -s -X PUT "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/artifact" \
  -H 'Content-Type: application/octet-stream' --data-binary "@$ARTIFACT" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('   stored :', d.get('artifactPath'))
print('   sha256 :', d.get('checksumSha256'))"
note "compare with the local sha256 above - they must match"

say "4. Re-upload different bytes (expect 409 - immutable)"
printf 'TAMPERED\n' > "$WORK/tampered.jar"
printf '   HTTP %s\n' "$(code -X PUT "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/artifact" \
  -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/tampered.jar")"

say "5. Promote DRAFT -> PUBLISHED (same bytes, new lifecycle stage)"
curl -s -X POST "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/promotion" \
  -H 'Content-Type: application/json' -d '{"status":"PUBLISHED"}' \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('   status :', d.get('status'))
print('   sha256 :', d.get('checksumSha256'), '(unchanged by promotion)')"

say "6. Download by exact coordinates and verify locally"
curl -s -D "$WORK/headers.txt" -o "$WORK/downloaded.jar" \
  "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/artifact"
DL_SHA=$(sha256sum "$WORK/downloaded.jar" | cut -d' ' -f1)
grep -i '^x-artifact-\|^etag' "$WORK/headers.txt" | sed 's/^/   /'
echo "   local recompute : $DL_SHA"
[ "$DL_SHA" = "$LOCAL_SHA" ] && echo "   >> MATCH - the bytes that came back are the bytes we built" \
                             || echo "   >> MISMATCH"

say "7. A client asks for its copy WITHOUT naming a version"
code -X POST "$BASE_URL/api/v1/clients" -H 'Content-Type: application/json' \
  -d '{"name":"client-d"}' >/dev/null
printf '   pin client-d -> %s  HTTP %s\n' "$VERSION" \
  "$(code -X PUT "$BASE_URL/api/v1/clients/client-d/tools/$TOOL/version" \
      -H 'Content-Type: application/json' -d "{\"version\":\"$VERSION\"}")"
curl -s -D "$WORK/h2.txt" -o "$WORK/client.jar" \
  "$BASE_URL/api/v1/clients/client-d/tools/$TOOL/artifact"
grep -i '^x-artifact-version\|^x-artifact-sha256' "$WORK/h2.txt" | sed 's/^/   /'
cmp -s "$WORK/client.jar" "$ARTIFACT" && echo "   >> client-d received exactly the bytes we built"

say "8. Revoke it (CVE found) - the bytes are never served again"
printf '   promote to REVOKED : HTTP %s\n' "$(code -X POST "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/promotion" \
  -H 'Content-Type: application/json' -d '{"status":"REVOKED"}')"
printf '   download           : HTTP %s  (410 Gone)\n' \
  "$(code "$BASE_URL/api/v1/tools/$TOOL/versions/$VERSION/artifact")"
printf '   client-d download  : HTTP %s  (410 Gone)\n' \
  "$(code "$BASE_URL/api/v1/clients/client-d/tools/$TOOL/artifact")"

say "Done."
