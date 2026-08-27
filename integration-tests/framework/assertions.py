"""Reusable assertions with failure messages you can act on.

A bare `assert response.status_code == 200` tells you almost nothing when it
fails. Every helper here dumps the request id, the status, and the body, so a
CI log is enough to diagnose without re-running anything locally.
"""

from __future__ import annotations

from typing import Any

import requests

PROBLEM_BASE = "https://platform.acme.internal/errors/"


def _context(response: requests.Response) -> str:
    """Everything you would otherwise have to re-run the test to discover."""
    request_id = response.headers.get("X-Request-Id", "<none>")
    body = response.text
    if len(body) > 600:
        body = body[:600] + " ...[truncated]"
    return (
        f"\n    {response.request.method} {response.url}"
        f"\n    status    : {response.status_code}"
        f"\n    requestId : {request_id}"
        f"\n    latency   : {response.elapsed.total_seconds() * 1000:.0f} ms"
        f"\n    body      : {body}"
    )


def assert_status(response: requests.Response, expected: int, why: str = "") -> None:
    suffix = f" ({why})" if why else ""
    assert response.status_code == expected, (
        f"Expected HTTP {expected}, got {response.status_code}{suffix}{_context(response)}"
    )


def assert_problem(response: requests.Response, expected_status: int,
                   expected_type: str | None = None) -> dict[str, Any]:
    """Validate an RFC 7807 problem+json error body.

    Asserting on the machine-readable `type` URI rather than on the English
    `detail` text is the whole reason the service returns problem+json: the
    wording can be improved without breaking a single test.
    """
    assert_status(response, expected_status)

    content_type = response.headers.get("Content-Type", "")
    assert "application/problem+json" in content_type, (
        f"Errors must use application/problem+json, got '{content_type}'{_context(response)}"
    )

    body = response.json()
    for field in ("type", "title", "status"):
        assert field in body, f"Problem body is missing '{field}'{_context(response)}"

    assert body["status"] == expected_status, (
        f"Problem body says status={body['status']} but HTTP said "
        f"{response.status_code}{_context(response)}"
    )

    if expected_type is not None:
        expected_uri = PROBLEM_BASE + expected_type
        assert body["type"] == expected_uri, (
            f"Expected problem type '{expected_uri}', got '{body['type']}'{_context(response)}"
        )
    return body


def assert_no_artifact_leaked(response: requests.Response) -> None:
    """An error response must never hand back artifact coordinates.

    This is the assertion that proves 'no silent fallback to latest': if a 404
    body carried an artifactPath, some caller would eventually use it.
    """
    body = response.text
    for leak in ("artifactPath", "checksumSha256"):
        assert leak not in body, (
            f"Error response leaked '{leak}' - a failed lookup must return no "
            f"artifact coordinates{_context(response)}"
        )


def assert_version_payload(response: requests.Response, tool: str, version: str) -> dict[str, Any]:
    """A successful version lookup returns EXACTLY what was asked for."""
    assert_status(response, 200)
    body = response.json()

    assert body["tool"] == tool, (
        f"Asked for tool '{tool}' but got '{body['tool']}'{_context(response)}"
    )
    # The heart of the platform: exact resolution, never a near miss.
    assert body["version"] == version, (
        f"Asked for version '{version}' but got '{body['version']}' - the service "
        f"must never substitute a different version{_context(response)}"
    )
    assert body["artifactPath"], f"Version {version} has no artifactPath{_context(response)}"
    assert version in body["artifactPath"], (
        f"artifactPath '{body['artifactPath']}' does not contain the version "
        f"'{version}' it claims to be{_context(response)}"
    )
    return body


def assert_artifact_headers(response: requests.Response, expected_sha: str | None = None,
                            expected_version: str | None = None) -> None:
    """Verify the integrity contract that travels with every download."""
    assert_status(response, 200)

    sha = response.headers.get("X-Artifact-Sha256")
    assert sha, f"Download is missing the X-Artifact-Sha256 header{_context(response)}"
    assert len(sha) == 64, f"SHA-256 must be 64 hex chars, got '{sha}'"

    etag = response.headers.get("ETag", "").strip('"')
    assert etag == sha, f"ETag '{etag}' disagrees with X-Artifact-Sha256 '{sha}'"

    disposition = response.headers.get("Content-Disposition", "")
    assert "attachment" in disposition, (
        f"Artifact download must be an attachment, got '{disposition}'"
    )

    if expected_version is not None:
        actual = response.headers.get("X-Artifact-Version")
        assert actual == expected_version, (
            f"Expected version '{expected_version}', header says '{actual}'{_context(response)}"
        )
    if expected_sha is not None:
        assert sha == expected_sha, (
            f"Server reported sha256 '{sha}' but the bytes we uploaded hash to "
            f"'{expected_sha}'{_context(response)}"
        )


def assert_bytes_match(response: requests.Response, expected: bytes) -> None:
    """The strongest assertion in the suite: the bytes are the bytes."""
    import hashlib

    actual = response.content
    assert actual == expected, (
        "Downloaded bytes differ from what was uploaded.\n"
        f"    expected sha256 : {hashlib.sha256(expected).hexdigest()}\n"
        f"    actual   sha256 : {hashlib.sha256(actual).hexdigest()}\n"
        f"    expected length : {len(expected)}\n"
        f"    actual   length : {len(actual)}"
    )
