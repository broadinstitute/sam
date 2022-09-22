import json

from ..sam_smoke_test_case import SamSmokeTestCase


class SamResourceTypesTests(SamSmokeTestCase):
    @staticmethod
    def resource_types_url() -> str:
        return SamSmokeTestCase.build_sam_url("/api/config/v1/resourceTypes")

    def test_status_code_is_200(self):
        response = SamSmokeTestCase.call_sam(self.resource_types_url(), SamSmokeTestCase.USER_TOKEN)
        self.assertEqual(response.status_code, 200, f"Resource Types HTTP Status is not 200: {response.text}")
