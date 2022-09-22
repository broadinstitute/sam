import json

from ..sam_smoke_test_case import SamSmokeTestCase


class SamStatusTests(SamSmokeTestCase):
    @staticmethod
    def status_url() -> str:
        return SamSmokeTestCase.build_sam_url("/status")

    def test_status_code_is_200(self):
        response = SamSmokeTestCase.call_sam(self.status_url())
        self.assertEqual(response.status_code, 200)

    def test_subsystems(self):
        response = SamSmokeTestCase.call_sam(self.status_url())
        status = json.loads(response.text)
        for system in status["systems"]:
            self.assertEqual(status["systems"][system]["ok"], True, f"{system} is not OK")