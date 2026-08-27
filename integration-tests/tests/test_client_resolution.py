"""Which version does each client get, and why.

data/client_resolution_tests.yaml is written so a product owner can read it and
CI can execute it. That file IS the business requirement.
"""

import pytest

from framework.assertions import assert_problem, assert_status
from framework.loader import parametrized

CASES, IDS = parametrized("client_resolution_tests.yaml", section="resolution")

# Some expectations cannot be literals without becoming flaky - see the notes
# on the client-latest case. A tiny sentinel vocabulary keeps the data file
# declarative while letting the value be computed at run time.
NEWEST_PUBLISHED = "<newest-published>"


def _resolve_expected_version(api, tool: str, expected: str) -> str:
    if expected != NEWEST_PUBLISHED:
        return expected
    published = [
        v["version"] for v in api.list_versions(tool).json() if v["status"] == "PUBLISHED"
    ]
    assert published, f"{tool} has no PUBLISHED versions to resolve 'latest' against"
    return published[0]  # the API already returns newest-first, numerically


@pytest.mark.parametrize("case", CASES, ids=IDS)
def test_client_version_resolution(api, resolve_tool, resolve_client, case):
    client = resolve_client(case["client"])
    tool = resolve_tool(case["tool"])
    expected = case["expected_status"]

    response = api.get_client_version(client, tool)

    if expected != 200:
        assert_problem(response, expected, case.get("expected_type"))
        return

    assert_status(response, 200)
    body = response.json()

    expected_version = _resolve_expected_version(api, tool, case["expected_version"])
    assert body["resolvedVersion"] == expected_version, (
        f"{case['client']} should receive {expected_version}, "
        f"got {body['resolvedVersion']}"
    )
    # WHY matters as much as WHICH: "2.0 because pinned" and "2.0 because
    # latest" are different facts during an incident.
    assert body["selector"] == case["expected_selector"], (
        f"{case['client']} should resolve via {case['expected_selector']}, "
        f"got {body['selector']}"
    )
    if "expected_deprecated" in case:
        assert body["deprecated"] is case["expected_deprecated"]
        if case["expected_deprecated"]:
            assert response.headers.get("Deprecation") == "true", (
                "A deprecated version must carry the Deprecation header so "
                "monitoring can alert without parsing the body"
            )


@pytest.mark.slow
def test_publishing_a_release_does_not_move_pinned_clients(
    api, tool, resolve_client, fresh_version
):
    """The single most important property of the whole platform.

    Encoded as code rather than data because it is a SEQUENCE - observe,
    mutate, observe again - not a table of inputs and outputs.
    """
    pinned = resolve_client("client-a")
    floating = resolve_client("client-latest")

    before_pinned = api.get_client_version(pinned, tool).json()["resolvedVersion"]
    before_floating = api.get_client_version(floating, tool).json()["resolvedVersion"]

    # a new release lands
    assert api.publish_version(tool, fresh_version).status_code == 201
    api.upload_artifact(tool, fresh_version, b"brand new release\n")

    after_pinned = api.get_client_version(pinned, tool).json()["resolvedVersion"]
    after_floating = api.get_client_version(floating, tool).json()["resolvedVersion"]

    assert after_pinned == before_pinned, (
        f"A pinned client moved from {before_pinned} to {after_pinned} because "
        f"somebody else released {fresh_version}. This is the failure mode the "
        f"entire platform exists to prevent."
    )
    assert after_floating == fresh_version, (
        f"A client that opted in to latest should have moved to {fresh_version}, "
        f"but is on {after_floating} (was {before_floating})"
    )


@pytest.mark.slow
def test_rollback_is_a_configuration_change(api, tool, resolve_client):
    """Roll a client back and forward; the artifacts are never touched."""
    client = resolve_client("client-c")
    original = api.get_client_version(client, tool).json()["resolvedVersion"]

    try:
        assert api.set_client_version(client, tool, "1.2").status_code == 200
        assert api.get_client_version(client, tool).json()["resolvedVersion"] == "1.2"

        # the version it rolled away from is still perfectly available
        assert api.get_version(tool, original).status_code == 200
    finally:
        api.set_client_version(client, tool, original)

    assert api.get_client_version(client, tool).json()["resolvedVersion"] == original
