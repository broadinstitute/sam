#!/usr/bin/env python3
"""
Check whether a single email is a member of a Google Group, and optionally
remove it.

Auth: uses your currently active `gcloud` login — run `gcloud auth login`
first if needed. Requires read (and, for --remove, write) access to the
target group via the Cloud Identity Groups API (Group Admin role, or being
a manager/owner of the group).

Usage:
    # Check only (default) — does not modify the group
    python3 scripts/check_group_member.py \
        --group-email GROUP_All_Users@example.org \
        --member-email tdr-ingest-sa@datarepo-prod.iam.gserviceaccount.com

    # Check and remove if present
    python3 scripts/check_group_member.py \
        --group-email GROUP_All_Users@example.org \
        --member-email tdr-ingest-sa@datarepo-prod.iam.gserviceaccount.com \
        --remove
"""
import argparse
import subprocess
import sys


def member_exists(group_email, member_email):
    cmd = [
        "gcloud", "identity", "groups", "memberships", "describe",
        f"--group-email={group_email}",
        f"--member-email={member_email}",
        "--format=json",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0:
        return True
    if "NOT_FOUND" in result.stderr or "not found" in result.stderr.lower():
        return False
    print(f"Error checking membership:\n{result.stderr}", file=sys.stderr)
    sys.exit(1)


def remove_member(group_email, member_email):
    cmd = [
        "gcloud", "identity", "groups", "memberships", "delete",
        f"--group-email={group_email}",
        f"--member-email={member_email}",
        "--quiet",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error removing member:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group-email", required=True, help="Email of the Google Group to check")
    parser.add_argument("--member-email", required=True, help="Email of the member to look up")
    parser.add_argument("--remove", action="store_true", help="Remove the member from the group if found")
    args = parser.parse_args()

    print(f"Checking whether {args.member_email} is a member of {args.group_email}...")
    exists = member_exists(args.group_email, args.member_email)

    if not exists:
        print(f"{args.member_email} is NOT a member of {args.group_email}. Nothing to do.")
        return

    print(f"{args.member_email} IS a member of {args.group_email}.")

    # if args.remove:
    #     print(f"Removing {args.member_email} from {args.group_email}...")
    #     remove_member(args.group_email, args.member_email)
    #     print("Removed.")
    # else:
    #     print("Run again with --remove to remove this member from the group.")


if __name__ == "__main__":
    main()
