from ..sam_smoke_tests import SamSmokeTests


class SamUserInfoTests(SamSmokeTests):
    @staticmethod
    def user_info_url() -> str:
        return SamSmokeTests.build_sam_url("/register/user/v2/self/info")

    def test_status_code_is_200(self):
        response = SamSmokeTests.call_sam(self.user_info_url(), SamSmokeTests.USER_TOKEN)
        self.assertEqual(response.status_code, 200, f"User info HTTP Status is not 200: {response.text}")