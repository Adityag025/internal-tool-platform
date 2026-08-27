"""Real bytes: upload, integrity, and the client-facing download."""

import pytest

from framework.assertions import (
    assert_artifact_headers,
    assert_bytes_match,
    assert_problem,
    assert_status,
)
from framework.client import sha256_hex

pytestmark = pytest.mark.artifact


@pytest.mark.smoke
def test_upload_then_download_round_trips_exactly(
    api, tool, fresh_version, artifact_payload, artifact_sha
):
    """The strongest assertion available: the bytes are the bytes."""
    assert api.publish_version(tool, fresh_version, status="DRAFT").status_code == 201

    uploaded = api.upload_artifact(tool, fresh_version, artifact_payload)
    assert_status(uploaded, 201)
    assert uploaded.json()["checksumSha256"] == artifact_sha, (
        "The server computed a different SHA-256 than we did for identical bytes"
    )

    assert api.promote(tool, fresh_version, "PUBLISHED").status_code == 200

    downloaded = api.download_artifact(tool, fresh_version)
    assert_artifact_headers(downloaded, expected_sha=artifact_sha, expected_version=fresh_version)
    assert_bytes_match(downloaded, artifact_payload)


def test_client_downloads_its_pinned_version_without_naming_it(
    api, tool, seeded, resolve_client
):
    """The consumer's whole interaction: 'give me my copy of data-validator'."""
    for logical, expected_version in (("client-a", "1.0"), ("client-b", "1.1"), ("client-c", "2.0")):
        client = resolve_client(logical)
        response = api.download_for_client(client, tool)

        assert_artifact_headers(response, expected_version=expected_version)
        assert_bytes_match(response, seeded["artifacts"][expected_version])


def test_uploaded_artifact_cannot_be_replaced(api, tool, fresh_version, artifact_payload):
    """Immutability, observed from outside the service."""
    api.publish_version(tool, fresh_version, status="DRAFT")
    assert api.upload_artifact(tool, fresh_version, artifact_payload).status_code == 201

    second = api.upload_artifact(tool, fresh_version, b"TAMPERED CONTENT\n")
    assert_problem(second, 409, "duplicate-resource")

    # and the original survived the attempt
    api.promote(tool, fresh_version, "PUBLISHED")
    assert_bytes_match(api.download_artifact(tool, fresh_version), artifact_payload)


@pytest.mark.negative
def test_registry_store_drift_is_502_not_404(api, tool, seeded):
    """A version the registry knows about but the store has no bytes for.

    404 would send the consumer hunting for a typo. 502 says 'the system behind
    me is inconsistent', which points at the right team and implies a retry
    might help.
    """
    response = api.download_artifact(tool, seeded["without_artifact"])
    assert_problem(response, 502, "artifact-missing")


@pytest.mark.negative
def test_revoked_artifact_is_gone_for_everyone(api, tool, seeded, resolve_client):
    """410 by exact coordinates AND through a client's own download path."""
    by_coordinates = api.download_artifact(tool, seeded["revoked"])
    assert_problem(by_coordinates, 410, "version-revoked")

    by_client = api.download_for_client(resolve_client("client-revoked"), tool)
    assert_problem(by_client, 410, "version-revoked")


@pytest.mark.negative
def test_cannot_upload_to_a_revoked_version(api, tool, seeded, artifact_payload):
    response = api.upload_artifact(tool, seeded["revoked"], artifact_payload)
    assert response.status_code in (409, 410), (
        f"Uploading to a revoked version must be refused, got {response.status_code}"
    )


@pytest.mark.negative
def test_empty_upload_is_rejected(api, tool, fresh_version):
    api.publish_version(tool, fresh_version, status="DRAFT")

    response = api.upload_artifact(tool, fresh_version, b"")
    assert response.status_code in (400, 422), (
        f"An empty artifact must not be stored, got {response.status_code}"
    )


def test_every_published_version_with_bytes_is_downloadable(api, tool, seeded):
    """Sweep: nothing advertised as published is silently unavailable."""
    broken = []
    for version in seeded["published"]:
        response = api.download_artifact(tool, version)
        if response.status_code != 200:
            broken.append((version, response.status_code))
    assert not broken, f"Published versions that failed to download: {broken}"


def test_downloaded_checksum_matches_the_registry_record(api, tool, seeded):
    """Cross-check the two sources of truth for every seeded version."""
    for version in seeded["published"]:
        recorded = api.get_version(tool, version).json()["checksumSha256"]
        downloaded = api.download_artifact(tool, version)

        assert recorded == downloaded.headers["X-Artifact-Sha256"], (
            f"{version}: registry says {recorded}, download header says "
            f"{downloaded.headers['X-Artifact-Sha256']}"
        )
        assert recorded == sha256_hex(downloaded.content), (
            f"{version}: the recorded checksum does not match the actual bytes"
        )
