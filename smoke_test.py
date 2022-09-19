import argparse
import json
import sys
import unittest
from functools import cache
from unittest import TestCase
from urllib.parse import urlunsplit, urljoin

import requests

DESCRIPTION = """
Sam Smoke Test
Enter the host (domain and optional port) of the Sam instance you want to to test.  This test will ensure that the Sam 
instance running on that host is minimally functional.
"""


@cache
def call_sam(sam_url):
    """Function is memoized so that we only make the call once"""
    return requests.get(sam_url)


class SamSmokeTests(TestCase):
    SAM_HOST = None

    @staticmethod
    def build_sam_url(path: str) -> str:
        assert SamSmokeTests.SAM_HOST, "ERROR - SamSmokeTests.SAM_HOST not properly set"
        return urljoin(f"https://{SamSmokeTests.SAM_HOST}", path)


class SamStatusTests(SamSmokeTests):
    @staticmethod
    def status_url() -> str:
        return SamSmokeTests.build_sam_url("/status")

    def test_status_code_is_200(self):
        response = call_sam(self.status_url())
        self.assertTrue(response.status_code == 200)

    def test_subsystems(self):
        response = call_sam(self.status_url())
        status = json.loads(response.text)
        for system in status["systems"]:
            self.assertTrue(status["systems"][system]["ok"], f"{system} is not OK")


class SamVersion(SamSmokeTests):
    @staticmethod
    def version_url() -> str:
        return SamSmokeTests.build_sam_url("/version")

    def test_status_code_is_200(self):
        response = call_sam(self.version_url())
        self.assertTrue(response.status_code == 200)

    def test_version_value_specified(self):
        response = call_sam(self.version_url())
        version = json.loads(response.text)
        self.assertIsNotNone(version["version"], "Version value must be non-empty")


def main(main_args):
    SamSmokeTests.SAM_HOST = main_args.sam_host
    unittest.main(verbosity=main_args.verbosity)


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

        args = parser.parse_args()

        # Need to pop off sys.argv values to avoid messing with args passed to unittest.main()
        for _ in range(len(sys.argv[1:])):
            sys.argv.pop()

        main(args)
        sys.exit(0)

    except Exception as e:
        print(e)
        sys.exit(1)
