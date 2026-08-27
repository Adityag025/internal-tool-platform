"""Load test data from JSON/YAML files and turn it into pytest parameters.

This module is the whole point of "data-driven": the SHAPE of a test lives in
Python, the CASES live in data files. Adding a case is a data edit, not a code
edit - which means a QA engineer, or anyone who can read JSON, can extend the
suite without touching test logic.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml

DATA_DIR = Path(__file__).resolve().parent.parent / "data"


def load_cases(filename: str, section: str | None = None) -> list[dict[str, Any]]:
    """Read a JSON or YAML case file.

    Args:
        filename: file name inside data/, e.g. "tool_version_tests.json"
        section:  for YAML files that hold several groups, the key to read
    """
    path = DATA_DIR / filename
    if not path.exists():
        raise FileNotFoundError(f"Test data file not found: {path}")

    raw = path.read_text(encoding="utf-8")
    data = json.loads(raw) if path.suffix == ".json" else yaml.safe_load(raw)

    if section is not None:
        if section not in data:
            raise KeyError(f"Section '{section}' not found in {filename}; have {list(data)}")
        data = data[section]

    if not isinstance(data, list):
        raise TypeError(f"{filename} must contain a list of cases, got {type(data).__name__}")
    if not data:
        # An empty data file would make the test silently pass with zero cases -
        # a green build that verified nothing. Fail loudly instead.
        raise ValueError(f"{filename} contains no cases")
    return data


def case_id(case: dict[str, Any]) -> str:
    """A short, readable id shown in pytest output.

    Compare the two failure lines you might read at 2am:

        FAILED test_version_lookup[case17]
        FAILED test_version_lookup[missing-version-999.0-expect-404]

    The second one is why every case file carries an explicit `id`.
    """
    if "id" in case:
        return str(case["id"])
    # Fall back to something descriptive rather than an index.
    parts = [str(case.get(k)) for k in ("tool", "version", "client", "expected_status") if k in case]
    return "-".join(parts) or "case"


def parametrized(filename: str, section: str | None = None):
    """Return (cases, ids) ready to unpack into @pytest.mark.parametrize."""
    cases = load_cases(filename, section)
    return cases, [case_id(c) for c in cases]
