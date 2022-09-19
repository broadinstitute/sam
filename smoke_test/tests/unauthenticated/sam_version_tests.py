import json

from ..sam_smoke_tests import SamSmokeTests


class SamVersionTests(SamSmokeTests):
    @staticmethod
    def version_url() -> str:
        return SamSmokeTests.build_sam_url("/version")

    def test_status_code_is_200(self):
        response = SamSmokeTests.call_sam(self.version_url())
        self.assertEqual(response.status_code, 200)

    def test_version_value_specified(self):
        response = SamSmokeTests.call_sam(self.version_url())
        version = json.loads(response.text)
        self.assertIsNotNone(version["version"], "Version value must be non-empty")