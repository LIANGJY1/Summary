#!/usr/bin/env python3
"""Compare a model's extraction decisions with a semantic fixture contract."""

import json
import sys
from pathlib import Path


def _index(document: dict, label: str) -> tuple[dict[str, dict], list[str]]:
    cases = document.get("cases")
    if not isinstance(cases, list):
        return {}, [f"{label}: `cases` must be a list"]

    indexed: dict[str, dict] = {}
    issues: list[str] = []
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("id"), str):
            issues.append(f"{label}: every case needs a string `id`")
            continue
        case_id = case["id"]
        if case_id in indexed:
            issues.append(f"{label}: duplicate case `{case_id}`")
        indexed[case_id] = case
    return indexed, issues


def compare(expected: dict, actual: dict) -> list[str]:
    expected_cases, issues = _index(expected, "expected")
    actual_cases, actual_issues = _index(actual, "actual")
    issues.extend(actual_issues)

    for case_id, expected_case in expected_cases.items():
        actual_case = actual_cases.get(case_id)
        if actual_case is None:
            issues.append(f"missing case `{case_id}`")
            continue
        for field in ("decision", "source"):
            if actual_case.get(field) != expected_case.get(field):
                issues.append(
                    f"case `{case_id}` {field}: expected {expected_case.get(field)!r}, "
                    f"got {actual_case.get(field)!r}"
                )

    for case_id in actual_cases.keys() - expected_cases.keys():
        issues.append(f"unexpected case `{case_id}`")
    return issues


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print("usage: check_extraction_eval.py EXPECTED.json ACTUAL.json", file=sys.stderr)
        return 2

    expected = json.loads(Path(args[0]).read_text(encoding="utf-8"))
    actual = json.loads(Path(args[1]).read_text(encoding="utf-8"))
    issues = compare(expected, actual)
    for issue in issues:
        print(issue, file=sys.stderr)
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
