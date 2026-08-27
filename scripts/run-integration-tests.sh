#!/usr/bin/env bash
# Run the Python data-driven integration suite against a running platform.
#
#   ./scripts/run-integration-tests.sh                  # everything
#   ./scripts/run-integration-tests.sh -m smoke         # fast subset
#   ./scripts/run-integration-tests.sh -m "not slow"
#   BASE_URL=http://staging:8081 ./scripts/run-integration-tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IT="$ROOT/integration-tests"
VENV="$IT/.venv"
export BASE_URL="${BASE_URL:-http://localhost:8081}"

if [ ! -x "$VENV/bin/python" ]; then
  echo "Creating virtualenv..."
  python3 -m venv --without-pip "$VENV"
  curl -sSfL -o /tmp/get-pip.py https://bootstrap.pypa.io/get-pip.py
  "$VENV/bin/python" /tmp/get-pip.py -q
fi

# Pinned requirements, so the suite that passed in CI is the suite you ran.
"$VENV/bin/python" -m pip install -q -r "$IT/requirements.txt"

mkdir -p "$IT/reports"
cd "$IT"

# --junitxml is what CI systems parse into a test report tab.
# --html is the human-readable artefact you attach to a build.
exec "$VENV/bin/python" -m pytest \
  --junitxml="reports/junit.xml" \
  --html="reports/report.html" --self-contained-html \
  "$@"
