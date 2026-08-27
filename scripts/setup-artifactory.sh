#!/usr/bin/env bash
# Creates the internal-tools-local repository in a freshly started Artifactory.
# Idempotent: re-running against an existing repository is fine.
set -uo pipefail

ART_URL="${ARTIFACTORY_URL:-http://localhost:8182}"
ART_USER="${ARTIFACTORY_USERNAME:-admin}"
ART_PASS="${ARTIFACTORY_PASSWORD:-password}"
REPO="${ARTIFACTORY_REPO:-internal-tools-local}"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

say "Waiting for Artifactory at $ART_URL"
for i in $(seq 1 60); do
  if curl -sf "$ART_URL/artifactory/api/system/ping" >/dev/null 2>&1; then
    echo "   ready after ${i}0s"; break
  fi
  printf '.'; sleep 10
done

say "Creating repository '$REPO'"
# A "generic" local repo: Artifactory stores arbitrary files at arbitrary
# paths, which is what we want - the platform owns the layout, not Artifactory.
CODE=$(curl -s -o /tmp/art-create.out -w '%{http_code}' \
  -u "$ART_USER:$ART_PASS" \
  -X PUT "$ART_URL/artifactory/api/repositories/$REPO" \
  -H 'Content-Type: application/json' \
  -d '{
        "key": "'"$REPO"'",
        "rclass": "local",
        "packageType": "generic",
        "description": "Internal developer tools - immutable versioned artifacts"
      }')
echo "   HTTP $CODE"
[ "$CODE" = "400" ] && echo "   (400 usually means it already exists - fine)"
cat /tmp/art-create.out 2>/dev/null | head -3

say "Repositories now present"
curl -s -u "$ART_USER:$ART_PASS" "$ART_URL/artifactory/api/repositories" \
  | python3 -c "import json,sys; [print('  ', r['key'], '-', r['type']) for r in json.load(sys.stdin)]" 2>/dev/null \
  || echo "   (could not list)"

say "Point the app at Artifactory with:"
cat <<TXT
   export ARTIFACT_STORE=artifactory
   export ARTIFACTORY_URL=$ART_URL
   export ARTIFACTORY_REPO=$REPO
   export ARTIFACTORY_USERNAME=$ART_USER
   export ARTIFACTORY_PASSWORD='<your password>'
   cd backend && ./mvnw spring-boot:run
TXT
