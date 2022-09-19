import argparse
import sys
import unittest
from unittest import TestSuite

from tests.authenticated.sam_user_info_tests import SamUserInfoTests
from tests.sam_smoke_tests import SamSmokeTests
from tests.unauthenticated.sam_status_tests import SamStatusTests
from tests.unauthenticated.sam_version_tests import SamVersionTests

DESCRIPTION = """
Sam Smoke Test
Enter the host (domain and optional port) of the Sam instance you want to to test.  This test will ensure that the Sam 
instance running on that host is minimally functional.
"""


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
