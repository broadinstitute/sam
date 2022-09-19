import argparse
import json
import sys
import unittest
from functools import cache
from unittest import TestCase

import requests

DESCRIPTION = """
Sam Smoke Test
Enter the host (domain and optional port) of the Sam instance you want to to test.  This test will ensure that the Sam 
instance running on that host is minimally functional.
"""


@cache
def get_sam_status(sam_host):
    """Function is memoized so that we only make the call once"""
    status_url = f"https://{sam_host}/status"
    return requests.get(status_url)


class SamStatusTests(TestCase):
    SAM_HOST = None

    def test_ok(self):
        response = get_sam_status(self.SAM_HOST)
        self.assertTrue(response.status_code == 200)

    def test_subsystems(self):
        response = get_sam_status(self.SAM_HOST)
        status = json.loads(response.text)
        for system in status["systems"]:
            self.assertTrue(status["systems"][system]["ok"], f"{system} is not OK")


def main(main_args):
    SamStatusTests.SAM_HOST = main_args.sam_host
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
1: Default - Prints number of tests executed plus a dot for each success and an F for each failure
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
