import json
import unittest
from pathlib import Path

from diagnostics.time_limits import limits_for, POLICY

ROOT = Path(__file__).resolve().parents[1]


class DiagnosticTimeLimitTests(unittest.TestCase):
    def test_all_twenty_have_per_language_margin_and_exact_migration_fences(self):
        policy = json.loads(POLICY.read_text())
        self.assertEqual(20, len(policy['items']))
        migration = (ROOT / 'backend/src/main/resources/db/migration/V58__problem_time_limits.sql').read_text()
        budgets = set()
        for item in policy['items']:
            limits = item['limits']
            budgets.add(tuple(limits[l] for l in ('JAVA', 'CPP', 'PYTHON')))
            for language, observed in item['recordedMaxWallMs'].items():
                self.assertLessEqual(observed * 4, limits[language] * 1000)
                self.assertTrue(1 <= limits[language] <= 20)
            for version, digest in item['versions'].items():
                self.assertIn(f"id='{version}' AND package_sha256='{digest}'", migration)
        self.assertGreater(len(budgets), 4)
        # A known version with different hidden/public bytes must never silently inherit its calibration.
        version = next(iter(policy['items'][0]['versions']))
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            limits_for({'version': version, 'statement': 'changed'})
        self.assertIsNone(limits_for({'version': 'unrelated-bank'}))

    def test_private_banks_if_present_match_the_reviewed_limits(self):
        paths = [ROOT / f'diagnostics/private/algo-mix-a-v{version}.json' for version in (1, 2)]
        if not all(p.exists() for p in paths):
            self.skipTest('private diagnostic artifacts are not committed')
        for path in paths:
            for item in json.loads(path.read_text())['items']:
                self.assertIsNotNone(limits_for(item['problem']))
