"""Shared fixtures for the integration suite.

Two ideas drive everything in this file.

1. SEED ONCE, READ MANY. Building the fixture data is the expensive part
   (dozens of HTTP round trips). Session-scoped fixtures pay that cost once
   for the whole run instead of once per test.

2. EVERY RUN OWNS ITS OWN DATA. Published artifacts are immutable and there is
   no delete endpoint - by design. So a suite that hard-coded the tool name
   would pass the first time and fail with 409s forever after. Each run
   namespaces its tool and clients with a short random id, which also means
   two runs can execute against the same server concurrently without
   colliding. Test isolation is a property you build in, not one you hope for.
"""

from __future__ import annotations

import os
import time
import uuid
from typing import Any

import pytest
import requests

from framework.client import ToolPlatformClient, sha256_hex

# The name used inside the data files. Fixtures map it onto the real,
# run-scoped tool so the data stays readable.
LOGICAL_TOOL = "data-validator"

# Clients the suite seeds. Anything not in here is treated as a genuinely
# nonexistent client (so "no-such-client" really does 404).
SEEDED_CLIENTS = {
    "client-a": "1.0",          # audit-frozen, pinned
    "client-b": "1.1",          # mid-migration
    "client-c": "2.0",          # newest schema
    "client-latest": "latest",  # explicitly opted in to floating
    "client-deprecated": "0.9",
    "client-revoked": "9.9",
    # "client-unconfigured" is created but deliberately never configured
}


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--base-url",
        action="store",
        default=os.environ.get("BASE_URL", "http://localhost:8081"),
        help="Base URL of the Tool Registry service",
    )


# ------------------------------------------------------------------ session


@pytest.fixture(scope="session")
def base_url(pytestconfig: pytest.Config) -> str:
    return pytestconfig.getoption("--base-url")


@pytest.fixture(scope="session")
def run_id() -> str:
    """Short unique id that namespaces everything this run creates."""
    return uuid.uuid4().hex[:8]


@pytest.fixture(scope="session")
def api(base_url: str) -> ToolPlatformClient:
    return ToolPlatformClient(base_url)


@pytest.fixture(scope="session", autouse=True)
def platform_ready(api: ToolPlatformClient, base_url: str) -> None:
    """Fail fast and clearly if the service is not up.

    Without this, a stopped service produces 40 confusing ConnectionErrors.
    With it, you get one sentence telling you exactly what to start.
    """
    deadline = time.time() + 30
    last_error: str = "no attempt made"
    while time.time() < deadline:
        try:
            response = api.health()
            if response.status_code == 200 and response.json().get("status") == "UP":
                return
            last_error = f"health returned {response.status_code}: {response.text[:200]}"
        except requests.RequestException as exc:
            last_error = f"{type(exc).__name__}: {exc}"
        time.sleep(1)

    pytest.exit(
        "\n\nThe Tool Registry service is not reachable at "
        f"{base_url}\n  last error: {last_error}\n\n"
        "  Start it with:\n"
        "    docker compose -f docker/docker-compose.yml up -d\n"
        "    cd backend && ./mvnw spring-boot:run\n",
        returncode=1,
    )


# --------------------------------------------------------------- seed data


def _artifact_bytes(tool: str, version: str) -> bytes:
    """Deterministic stand-in for a real build output."""
    return f"{tool} {version} build output payload\n".encode("utf-8")


@pytest.fixture(scope="session")
def seeded(api: ToolPlatformClient, run_id: str) -> dict[str, Any]:
    """Create this run's tool, versions, artifacts and clients.

    Returns a description of what was created so tests can assert against it
    rather than against hard-coded literals.
    """
    tool = f"{LOGICAL_TOOL}-{run_id}"

    created = api.create_tool(tool, "Validates inbound data files")
    assert created.status_code == 201, f"Could not seed tool: {created.status_code} {created.text}"

    artifacts: dict[str, bytes] = {}

    def publish(version: str, status: str | None = None, with_artifact: bool = True) -> None:
        response = api.publish_version(tool, version, status=status)
        assert response.status_code == 201, (
            f"Could not seed {tool} {version}: {response.status_code} {response.text}"
        )
        if with_artifact:
            payload = _artifact_bytes(tool, version)
            uploaded = api.upload_artifact(tool, version, payload)
            assert uploaded.status_code == 201, (
                f"Could not upload artifact for {version}: {uploaded.status_code} {uploaded.text}"
            )
            artifacts[version] = payload

    # The four versions the business scenario is built around.
    for version in ("1.0", "1.1", "1.2", "2.0"):
        publish(version)

    # A deprecated version: still downloadable, but flagged.
    publish("0.9", status="DRAFT")
    api.promote(tool, "0.9", "PUBLISHED")
    api.promote(tool, "0.9", "DEPRECATED")

    # A revoked version: exists, has bytes, must never be served again.
    publish("9.9")
    api.promote(tool, "9.9", "REVOKED")

    # A version registered but never uploaded. This is how the suite provokes
    # registry/store drift on purpose, to prove it surfaces as 502 and not 404.
    publish("3.0", status="DRAFT", with_artifact=False)

    # Clients
    clients: dict[str, str] = {}
    for logical, version in SEEDED_CLIENTS.items():
        name = f"{logical}-{run_id}"
        assert api.create_client(name).status_code == 201, f"Could not seed client {name}"
        pinned = api.set_client_version(name, tool, version)
        assert pinned.status_code == 200, (
            f"Could not pin {name} to {version}: {pinned.status_code} {pinned.text}"
        )
        clients[logical] = name

    # Registered but intentionally left without any tool configuration.
    unconfigured = f"client-unconfigured-{run_id}"
    assert api.create_client(unconfigured).status_code == 201
    clients["client-unconfigured"] = unconfigured

    return {
        "tool": tool,
        "clients": clients,
        "artifacts": artifacts,
        "published": ["1.0", "1.1", "1.2", "2.0"],
        "deprecated": "0.9",
        "revoked": "9.9",
        "without_artifact": "3.0",
    }


@pytest.fixture(scope="session")
def tool(seeded: dict[str, Any]) -> str:
    return seeded["tool"]


# ------------------------------------------------------------ name mapping


@pytest.fixture(scope="session")
def resolve_tool(tool: str):
    """Map the logical name in the data files onto this run's real tool.

    Anything that is NOT the logical name is passed through untouched, so a
    case can still reference a genuinely nonexistent tool like "ghost-tool".
    """

    def _resolve(name: str) -> str:
        return tool if name == LOGICAL_TOOL else name

    return _resolve


@pytest.fixture(scope="session")
def resolve_client(seeded: dict[str, Any]):
    """Same idea for clients, so "no-such-client" stays genuinely absent."""
    mapping = seeded["clients"]

    def _resolve(name: str) -> str:
        return mapping.get(name, name)

    return _resolve


# ------------------------------------------------------------- per-test data


@pytest.fixture
def fresh_version(api: ToolPlatformClient, tool: str) -> str:
    """An unused version number, for tests that publish or mutate.

    Mutating tests cannot reuse the seeded versions: artifacts are immutable,
    so a second run would 409. Each such test gets its own coordinates.
    """
    for attempt in range(100):
        candidate = f"{700 + attempt}.0"
        if api.get_version(tool, candidate).status_code == 404:
            return candidate
    raise RuntimeError("Could not find an unused version number")


@pytest.fixture
def artifact_payload() -> bytes:
    return b"fresh build output for a mutating test\n"


@pytest.fixture
def artifact_sha(artifact_payload: bytes) -> str:
    return sha256_hex(artifact_payload)


# ------------------------------------------------------------------ reporting


def pytest_report_header(config: pytest.Config) -> list[str]:
    return [f"target service: {config.getoption('--base-url')}"]
