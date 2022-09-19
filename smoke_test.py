import argparse
import json
import sys
import unittest
from functools import cache
from unittest import TestCase, TestSuite
from urllib.parse import urlunsplit, urljoin

import requests
from requests import Response

DESCRIPTION = """
Sam Smoke Test
Enter the host (domain and optional port) of the Sam instance you want to to test.  This test will ensure that the Sam 
instance running on that host is minimally functional.
"""


class SamSmokeTests(TestCase):
    SAM_HOST = None
    USER_TOKEN = None

    @staticmethod
    def build_sam_url(path: str) -> str:
        assert SamSmokeTests.SAM_HOST, "ERROR - SamSmokeTests.SAM_HOST not properly set"
        return urljoin(f"https://{SamSmokeTests.SAM_HOST}", path)

    @staticmethod
    @cache
    def call_sam(url: str, user_token: str = None) -> Response:
        """Function is memoized so that we only make the call once"""
        headers = {"Authorization": f"Bearer {user_token}"} if user_token else {}
        return requests.get(url, headers=headers)


class SamStatusTests(SamSmokeTests):
    @staticmethod
    def status_url() -> str:
        return SamSmokeTests.build_sam_url("/status")

    def test_status_code_is_200(self):
        response = SamSmokeTests.call_sam(self.status_url())
        self.assertEqual(response.status_code, 200)

    def test_subsystems(self):
        response = SamSmokeTests.call_sam(self.status_url())
        status = json.loads(response.text)
        for system in status["systems"]:
            self.assertEqual(status["systems"][system]["ok"], True, f"{system} is not OK")


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


class SamUserInfoTests(SamSmokeTests):
    @staticmethod
    def user_info_url() -> str:
        return SamSmokeTests.build_sam_url("/register/user/v2/self/info")

    def test_status_code_is_200(self):
        response = SamSmokeTests.call_sam(self.user_info_url(), SamSmokeTests.USER_TOKEN)
        self.assertEqual(response.status_code, 200, f"User info HTTP Status is not 200: {response.text}")


def gather_tests(is_authenticated: bool = False) -> TestSuite:
    suite = unittest.TestSuite()

    status_tests = unittest.defaultTestLoader.loadTestsFromTestCase(SamStatusTests)
    version_tests = unittest.defaultTestLoader.loadTestsFromTestCase(SamVersionTests)

    suite.addTests(status_tests)
    suite.addTests(version_tests)

    if is_authenticated:
        user_info_tests = unittest.defaultTestLoader.loadTestsFromTestCase(SamUserInfoTests)
        suite.addTests(user_info_tests)
    else:
        print("No User Token provided.  Skipping authenticated tests.")

    return suite


def main(main_args):
    SamSmokeTests.SAM_HOST = main_args.sam_host
    SamSmokeTests.USER_TOKEN = main_args.user_token
    runner = unittest.TextTestRunner(verbosity=main_args.verbosity)
    test_suite = gather_tests(main_args.user_token)
    runner.run(test_suite)


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser(
            description=DESCRIPTION,
            formatter_class=argparse.RawTextHelpFormatter
        )
        parser.add_argument(
            "-v",
            "--verbosity",
            type=int,
            choices=[0, 1, 2],
            default=1,
            help="""Python unittest verbosity setting: 
0: Quiet - Prints only number of tests executed
1: Minimal - (default) Prints number of tests executed plus a dot for each success and an F for each failure
2: Verbose - Help string and its result will be printed for each test"""
        )
        parser.add_argument(
            "sam_host",
            help="domain with optional port number of the Sam host you want to test"
        )
        parser.add_argument(
            "user_token",
            nargs='?',
            default=None,
            help="Optional. If present, will test additional authenticated endpoints using the specified token"
        )

        args = parser.parse_args()

        # Need to pop off sys.argv values to avoid messing with args passed to unittest.main()
        for _ in range(len(sys.argv[1:])):
            sys.argv.pop()

        main(args)
        sys.exit(0)

    except Exception as e:
        print(e)
        sys.exit(1)
