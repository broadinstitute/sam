import json

from ..sam_smoke_test_case import SamSmokeTestCase


class SamUserInfoTests(SamSmokeTestCase):
    @staticmethod
    def user_info_url() -> str:
        return SamSmokeTestCase.build_sam_url("/register/user/v2/self/info")

    def test_status_code_is_200(self):
        response = SamSmokeTestCase.call_sam(self.user_info_url(), SamSmokeTestCase.USER_TOKEN)
        self.assertEqual(response.status_code, 200, f"User info HTTP Status is not 200: {response.text}")

    def test_content_contains_correct_keys(self):
        response = SamSmokeTestCase.call_sam(self.user_info_url(), SamSmokeTestCase.USER_TOKEN)
        user_info = json.loads(response.text)
        self.assertListEqual(list(user_info.keys()),
                             ["adminEnabled", "enabled", "userEmail", "userSubjectId"],
                             "User Info object does not match expected fields")
