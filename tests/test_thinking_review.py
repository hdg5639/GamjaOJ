import unittest
from generation.worker import review_schema, THINKING_REVIEW

class ThinkingReviewTest(unittest.TestCase):
    def test_new_review_schema_requires_all_axes_without_changing_frozen_legacy(self):
        legacy=review_schema(True)
        current=review_schema(True,True)
        self.assertNotIn('thinking',legacy['properties'])
        self.assertIn('thinking',current['required'])
        profile=current['properties']['thinking']
        self.assertEqual({'layer','insight','implementation','edgeCases','rationale'},set(profile['required']))
        self.assertEqual(9,profile['properties']['layer']['maximum'])
        self.assertEqual(5,profile['properties']['edgeCases']['maximum'])
        self.assertFalse(profile['additionalProperties'])
        current['properties']['thinking']['properties']['layer']['maximum']=100
        self.assertEqual(9,review_schema(True,True)['properties']['thinking']['properties']['layer']['maximum'])
        self.assertIn('spoiler-free',THINKING_REVIEW)
        self.assertIn('NOT a fixed algorithm lookup',THINKING_REVIEW)
