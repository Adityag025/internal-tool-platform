"""The promotion state machine, driven by data/promotion_tests.yaml.

A state machine is the clearest possible argument for data-driven testing: the
transition table IS the specification. Written as individual test methods it
becomes nine near-identical functions where a missing row is invisible; written
as data, a gap in the table is something you can see at a glance.
"""

import pytest

from framework.assertions import assert_problem, assert_status
from framework.loader import parametrized

CASES, IDS = parametrized("promotion_tests.yaml", section="transitions")


def _prepare(api, tool: str, version: str, state: str, with_artifact: bool) -> None:
    """Put a brand-new version into the requested starting state."""
    if state == "REVOKED":
        # Bytes cannot be uploaded to an already-revoked version, so upload
        # first and revoke afterwards - the same order a real incident takes.
        assert api.publish_version(tool, version, status="PUBLISHED").status_code == 201
        if with_artifact:
            assert api.upload_artifact(tool, version, b"payload\n").status_code == 201
        assert api.promote(tool, version, "REVOKED").status_code == 200
    else:
        assert api.publish_version(tool, version, status=state).status_code == 201
        if with_artifact:
            assert api.upload_artifact(tool, version, b"payload\n").status_code == 201


@pytest.mark.parametrize("case", CASES, ids=IDS)
def test_promotion_transition(api, tool, fresh_version, case):
    _prepare(api, tool, fresh_version, case["from"], case["with_artifact"])

    response = api.promote(tool, fresh_version, case["to"])

    if case["expected_status"] == 200:
        assert_status(response, 200, why=f"{case['from']} -> {case['to']} should be allowed")
        assert response.json()["status"] == case["to"]
    else:
        assert_problem(response, case["expected_status"], case.get("expected_type"))
        # A rejected promotion must leave the version exactly as it was.
        current = api.get_version(tool, fresh_version).json()["status"]
        assert current == case["from"], (
            f"A rejected promotion changed the status anyway: "
            f"{case['from']} became {current}"
        )


@pytest.mark.artifact
def test_promotion_never_changes_the_bytes(api, tool, fresh_version, artifact_payload, artifact_sha):
    """Build once, deploy everywhere - verified rather than asserted in a README."""
    api.publish_version(tool, fresh_version, status="DRAFT")
    api.upload_artifact(tool, fresh_version, artifact_payload)

    checksums = []
    for target in ("PUBLISHED", "DEPRECATED", "PUBLISHED"):
        assert api.promote(tool, fresh_version, target).status_code == 200
        checksums.append(api.get_version(tool, fresh_version).json()["checksumSha256"])

    assert set(checksums) == {artifact_sha}, (
        f"The checksum changed during promotion: {checksums}. Promotion must "
        f"relabel the same bytes, never rebuild them."
    )
    assert api.download_artifact(tool, fresh_version).content == artifact_payload
