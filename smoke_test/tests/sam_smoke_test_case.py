import re
from functools import cache
from unittest import TestCase
from urllib.parse import urljoin

import requests
from requests import Response


class SamSmokeTestCase(TestCase):
    SAM_HOST = None
    USER_TOKEN = None

    @staticmethod
    def build_sam_url(path: str) -> str:
        assert SamSmokeTestCase.SAM_HOST, "ERROR - SamSmokeTests.SAM_HOST not properly set"
        if re.match(r"^\s*https?://", SamSmokeTestCase.SAM_HOST):
            return urljoin(SamSmokeTestCase.SAM_HOST, path)
        else:
            return urljoin(f"https://{SamSmokeTestCase.SAM_HOST}", path)

    @staticmethod
    @cache
    def call_sam(url: str, user_token: str = None) -> Response:
        """Function is memoized so that we only make the call once"""
        headers = {"Authorization": f"Bearer {user_token}"} if user_token else {}
        return requests.get(url, headers=headers)
