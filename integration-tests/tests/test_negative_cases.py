"""Edge cases, malformed input, and abuse - all from data/negative_cases.yaml.

Negative tests are where data-driven testing pays off most. Happy paths are few
and get written no matter what; edge cases are many, boring, and exactly the
ones people skip when each one costs a new function.
"""

import pytest

from framework.assertions import assert_problem, assert_status
from framework.client import ToolPlatformClient
from framework.loader import parametrized

pytestmark = pytest.mark.negative

TOOL_NAME_CASES, TOOL_NAME_IDS = parametrized("negative_cases.yaml", section="invalid_tool_names")
PAYLOAD_CASES, PAYLOAD_IDS = parametrized("negative_cases.yaml", section="invalid_version_payloads")
CLIENT_CASES, CLIENT_IDS = parametrized("negative_cases.yaml", section="invalid_client_versions")


@pytest.mark.contract
@pytest.mark.parametrize("case", TOOL_NAME_CASES, ids=TOOL_NAME_IDS)
def test_invalid_tool_names_are_rejected(api, case):
    """The tool name becomes part of a repository path, so it is validated hard."""
    response = api.post("/api/v1/tools", json={"name": case["name"]})

    body = assert_problem(response, case["expected_status"], case.get("expected_type"))
    assert any(err["field"] == "name" for err in body.get("errors", [])), (
        f"Validation error should name the offending field. Body: {body}"
    )


@pytest.mark.contract
@pytest.mark.parametrize("case", PAYLOAD_CASES, ids=PAYLOAD_IDS)
def test_invalid_version_payloads_are_rejected(api, tool, case):
    response = api.post(f"/api/v1/tools/{tool}/versions", json=case["body"])

    body = assert_problem(response, case["expected_status"], case.get("expected_type"))
    if "expected_field" in case:
        fields = [err["field"] for err in body.get("errors", [])]
        assert case["expected_field"] in fields, (
            f"Expected a validation error on '{case['expected_field']}', got {fields}"
        )


@pytest.mark.parametrize("case", CLIENT_CASES, ids=CLIENT_IDS)
def test_invalid_client_version_configuration(api, tool, resolve_client, case):
    client = resolve_client("client-a")
    original = api.get_client_version(client, tool).json().get("resolvedVersion")

    try:
        response = api.set_client_version(client, tool, case["version"])
        if case["expected_status"] == 200:
            assert_status(response, 200)
        else:
            assert_problem(response, case["expected_status"], case.get("expected_type"))
    finally:
        # Leave the shared fixture exactly as we found it.
        if original:
            api.set_client_version(client, tool, original)


def test_duplicate_tool_registration_is_409(api, tool):
    assert_problem(api.create_tool(tool), 409, "duplicate-resource")


def test_duplicate_version_publish_is_409(api, tool):
    response = api.publish_version(tool, "1.2", artifact_path="data-validator/1.2/DIFFERENT.jar")
    body = assert_problem(response, 409, "duplicate-resource")
    assert "immutable" in body["detail"].lower(), (
        f"The 409 should explain WHY, not just refuse. Got: {body['detail']}"
    )

    # and the original coordinates were not rewritten
    stored = api.get_version(tool, "1.2").json()["artifactPath"]
    assert "DIFFERENT" not in stored, f"A rejected publish still changed the path: {stored}"


def test_malformed_json_body_is_400(api, tool):
    response = api.post(
        f"/api/v1/tools/{tool}/versions",
        data="{this is not json",
        headers={"Content-Type": "application/json"},
    )
    assert response.status_code in (400, 422), (
        f"Malformed JSON should be a client error, got {response.status_code}"
    )


def test_wrong_content_type_on_upload_is_rejected(api, tool, fresh_version):
    api.publish_version(tool, fresh_version, status="DRAFT")

    response = api.put(
        f"/api/v1/tools/{tool}/versions/{fresh_version}/artifact",
        data=b"bytes",
        headers={"Content-Type": "text/plain"},
    )
    assert response.status_code == 415, (
        f"The upload endpoint consumes application/octet-stream only, "
        f"expected 415, got {response.status_code}"
    )


def test_unknown_endpoint_is_404(api):
    assert api.get("/api/v1/definitely-not-a-real-endpoint").status_code == 404


def test_wrong_method_is_405(api, tool):
    response = api.delete(f"/api/v1/tools/{tool}/versions/1.2")
    assert response.status_code in (404, 405), (
        f"Versions are immutable and cannot be deleted; expected 405 (or 404 if "
        f"the route does not exist), got {response.status_code}"
    )


def test_unauthenticated_write_is_rejected(api, auth_enabled, base_url, tool):
    """Publishing requires a credential.

    This was an `xfail` through Phases 4-8, carrying the reason "authentication
    is not implemented yet - it is Phase 9". It is now a real test, and it
    passes. That is the point of writing a test for a known gap rather than
    deleting it: the day the gap closes, the test tells you.
    """
    if not auth_enabled:
        pytest.skip(
            "This platform is running without an API key, so writes are "
            "intentionally open. Start it with API_KEY=<secret> to exercise "
            "authentication, and the suite will assert on it."
        )

    # A client with NO credential, regardless of what the suite was given.
    anonymous = ToolPlatformClient(base_url)

    response = anonymous.publish_version(tool, "8.8")

    body = assert_problem(response, 401, "unauthorized")
    assert "X-API-Key" in str(body.get("detail", "")), (
        "A 401 should tell a well-behaved client HOW to authenticate"
    )
    assert "ApiKey" in response.headers.get("WWW-Authenticate", ""), (
        "RFC 7235 requires WWW-Authenticate on a 401"
    )


def test_reads_stay_public_even_with_authentication_on(api, auth_enabled, base_url, tool):
    """Reads are open by policy, not by accident.

    Every consumer needs to list versions constantly; requiring a credential
    for that would push teams into sharing one.
    """
    if not auth_enabled:
        pytest.skip("authentication is not enabled on this platform")

    anonymous = ToolPlatformClient(base_url)
    assert_status(anonymous.list_versions(tool), 200)
