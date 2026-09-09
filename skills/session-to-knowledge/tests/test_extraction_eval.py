#!/usr/bin/env python3
"""Tests for the model-agnostic extraction boundary evaluator."""

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "check_extraction_eval.py"
FIXTURE = Path(__file__).parent / "fixtures" / "kotlin-project-qa.md"
FIXTURE_EXPECTED = Path(__file__).parent / "fixtures" / "kotlin-project-qa.expected.json"
spec = importlib.util.spec_from_file_location("check_extraction_eval", SCRIPT)
checker = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(checker)


EXPECTED = {
    "cases": [
        {"id": "object-expression", "decision": "include", "source": "user"},
        {"id": "getter-lazy-tour", "decision": "reject", "source": "assistant-only"},
    ]
}


class ExtractionEvalTests(unittest.TestCase):
    def test_expected_contract_covers_every_fixture_id(self):
        fixture_ids = set(re.findall(r"\[([a-z][a-z0-9-]+)\]", FIXTURE.read_text(encoding="utf-8")))
        expected = json.loads(FIXTURE_EXPECTED.read_text(encoding="utf-8"))
        expected_ids = {case["id"] for case in expected["cases"]}
        self.assertEqual(expected_ids, fixture_ids)

    def test_matching_semantic_decisions_pass(self):
        self.assertEqual(checker.compare(EXPECTED, EXPECTED), [])

    def test_missing_user_topic_is_reported(self):
        actual = {"cases": [EXPECTED["cases"][1]]}
        issues = checker.compare(EXPECTED, actual)
        self.assertTrue(any("object-expression" in issue and "missing" in issue for issue in issues))

    def test_assistant_tangent_promoted_to_topic_is_reported(self):
        actual = {
            "cases": [
                EXPECTED["cases"][0],
                {"id": "getter-lazy-tour", "decision": "include", "source": "assistant-only"},
            ]
        }
        issues = checker.compare(EXPECTED, actual)
        self.assertTrue(any("getter-lazy-tour" in issue and "decision" in issue for issue in issues))

    def test_cli_rejects_unexpected_topics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = root / "expected.json"
            actual = root / "actual.json"
            expected.write_text(json.dumps(EXPECTED), encoding="utf-8")
            actual.write_text(
                json.dumps({"cases": EXPECTED["cases"] + [{"id": "invented", "decision": "include"}]}),
                encoding="utf-8",
            )
            self.assertEqual(checker.main([str(expected), str(actual)]), 1)


if __name__ == "__main__":
    unittest.main()
