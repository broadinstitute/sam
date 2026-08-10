#!/usr/bin/env python3
"""
Retrieve a single member from a Google Group using the Admin SDK Directory
API via google-api-python-client, instead of the gcloud CLI.

Setup:
    pip install google-api-python-client google-auth

Auth:
    gcloud auth application-default login \
        --scopes="https://www.googleapis.com/auth/admin.directory.group.member.readonly,https://www.googleapis.com/auth/cloud-platform"

    Note: this is a different permission model than `gcloud identity groups`
    (Cloud Identity API). Reading group membership via the Admin SDK requires
    your account to have Google Workspace admin (or delegated Groups Reader)
    privilege in the admin console — a Cloud Identity IAM role alone isn't
    enough. If the gcloud CLI approach was failing on permissions, double
    check this privilege too.

Usage:
    python3 scripts/get_group_member_admin_sdk.py \
        --group-email GROUP_All_Users@example.org \
        --member-email tdr-ingest-sa@datarepo-prod.iam.gserviceaccount.com
"""
import argparse
import sys

import google.auth
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

SCOPES = ["https://www.googleapis.com/auth/admin.directory.group.member.readonly"]


def get_directory_service():
    credentials, _ = google.auth.default(scopes=SCOPES)
    return build("admin", "directory_v1", credentials=credentials)


def get_member(service, group_email, member_email):
    try:
        return service.members().get(groupKey=group_email, memberKey=member_email).execute()
    except HttpError as e:
        if e.resp.status == 404:
            return None
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group-email", required=True, help="Email of the Google Group to query")
    parser.add_argument("--member-email", required=True, help="Email of the member to look up")
    args = parser.parse_args()

    service = get_directory_service()

    try:
        member = get_member(service, args.group_email, args.member_email)
    except HttpError as e:
        print(f"Error retrieving member: {e}", file=sys.stderr)
        sys.exit(1)

    if member is None:
        print(f"{args.member_email} is NOT a member of {args.group_email}.")
        return

    print(f"{args.member_email} IS a member of {args.group_email}.")
    print(member)


if __name__ == "__main__":
    main()
