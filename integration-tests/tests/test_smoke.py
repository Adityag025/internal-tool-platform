"""Fast checks that answer 'is the platform even usable right now?'

Marked `smoke` so CI can run these first and fail in seconds rather than
discovering a dead service three minutes into the full suite.
"""

import pytest

pytestmark = pytest.mark.smoke


def test_health_is_up(api):
    response = api.health()
    assert response.status_code == 200

    body = response.json()
    assert body["status"] == "UP"

    unhealthy = {
        name: component["status"]
        for name, component in body.get("components", {}).items()
        if component["status"] != "UP"
    }
    assert not unhealthy, f"Some components are not UP: {unhealthy}"


def test_health_reports_both_dependencies(api):
    """A health check that only proves the JVM is running is not a health check."""
    components = api.health().json().get("components", {})

    for required in ("db", "artifactStore"):
        assert required in components, (
            f"/actuator/health should report '{required}'. Present: {list(components)}"
        )


def test_tool_listing_is_paginated(api):
    """A bare JSON array is a design dead-end; assert the envelope survives."""
    body = api.list_tools().json()

    assert "data" in body and "pagination" in body, (
        f"Collection endpoints must return an envelope, got keys: {list(body)}"
    )
    for field in ("page", "size", "totalElements", "totalPages", "hasMore"):
        assert field in body["pagination"], f"pagination is missing '{field}'"


def test_seeded_tool_is_visible(api, tool):
    assert api.get_tool(tool).status_code == 200


def test_requests_are_correlated(api, tool):
    """Every response carries an id you can grep for in the service log."""
    response = api.get_tool(tool)
    request_id = response.headers.get("X-Request-Id")

    assert request_id, "Every response should carry X-Request-Id for correlation"
    assert len(request_id) >= 8
