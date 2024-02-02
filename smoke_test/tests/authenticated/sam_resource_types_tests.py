import json

from ..sam_smoke_test_case import SamSmokeTestCase


class SamResourceTypesTests(SamSmokeTestCase):
    @staticmethod
    def resource_types_url() -> str:
        return SamSmokeTestCase.build_sam_url("/api/config/v1/resourceTypes")

    def test_status_code_is_200(self):
       self.assertEqual(1, 2)
