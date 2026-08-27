"""Exact-version resolution, driven entirely by data/tool_version_tests.json.

WHY DATA-DRIVEN INSTEAD OF ONE TEST METHOD PER CASE
---------------------------------------------------
The duplicated-method version of this file would be ~15 near-identical
functions, each 6 lines, differing only in two string literals:

    def test_version_1_0_returns_200(api):
        response = api.get_version("data-validator", "1.0")
        assert response.status_code == 200
    def test_version_1_1_returns_200(api):
        ...

Five things go wrong with that, all of them in practice and not in theory:

1. Adding a case means writing code, so people stop adding cases.
2. Improving an assertion means editing 15 functions, so nobody improves it -
   and the ones they miss silently keep testing less.
3. Copy-paste drift: one function asserts the status, its neighbour also
   asserts the body, and no one can tell which behaviour is actually covered.
4. The cases become invisible. Nobody outside the team can read Python well
   enough to answer "what does this suite actually check?"
5. Coverage gaps hide in the noise. A missing case looks exactly like the
   other 15 - absent.

Data-driven inverts it: ONE well-reviewed assertion path, N cases in a file
anyone can read and extend. The test data becomes the specification, and it is
executable.
"""

import pytest

from framework.assertions import (
    assert_no_artifact_leaked,
    assert_problem,
    assert_version_payload,
)
from framework.loader import parametrized

CASES, IDS = parametrized("tool_version_tests.json")


@pytest.mark.parametrize("case", CASES, ids=IDS)
def test_version_lookup(api, resolve_tool, case):
    """One assertion path, fifteen cases."""
    tool = resolve_tool(case["tool"])
    version = case["version"]
    expected = case["expected_status"]

    response = api.get_version(tool, version)

    if expected == 200:
        body = assert_version_payload(response, tool, version)
        if "expected_version_status" in case:
            assert body["status"] == case["expected_version_status"], (
                f"Expected lifecycle status {case['expected_version_status']}, got {body['status']}"
            )
    else:
        assert_problem(response, expected, case.get("expected_type"))
        # The assertion that actually enforces "no silent fallback":
        # a failed lookup must not hand back usable coordinates.
        assert_no_artifact_leaked(response)


@pytest.mark.smoke
def test_versions_are_listed_newest_first(api, tool):
    """Numeric ordering, not lexicographic."""
    response = api.list_versions(tool)
    assert response.status_code == 200

    versions = [v["version"] for v in response.json()]
    assert versions, "the seeded tool should have versions"

    def as_tuple(v: str) -> tuple[int, ...]:
        parts = [int(p) for p in v.split(".")]
        while len(parts) < 3:
            parts.append(0)
        return tuple(parts)

    numeric = [as_tuple(v) for v in versions]
    assert numeric == sorted(numeric, reverse=True), (
        f"Versions are not in descending numeric order: {versions}"
    )


@pytest.mark.smoke
def test_every_listed_version_is_individually_retrievable(api, tool):
    """A listing that advertises something unreachable is worse than no listing."""
    listed = [v["version"] for v in api.list_versions(tool).json()]

    unreachable = [
        v for v in listed if api.get_version(tool, v).status_code != 200
    ]
    assert not unreachable, f"Listed but not retrievable: {unreachable}"
