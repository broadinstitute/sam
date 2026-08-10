#!/usr/bin/env python3
"""
List (and later, remove) Google Group members matching an email glob pattern.

Auth: uses your currently active `gcloud` login — run `gcloud auth login`
first if needed. Requires read access to the target group via the Cloud
Identity Groups API (Group Admin role, or being a manager/owner of the group).

Usage:
    python3 scripts/manage_group_members.py \
        --group-email GROUP_All_Users@example.org \
        --pattern 'tdr-ingest-sa@datarepo-*' \
        --output matched_members.csv
"""
import argparse
import csv
import fnmatch
import json
import subprocess
import sys


def list_group_members(group_email):
    # gcloud follows next_page_token internally for list commands, so a
    # single invocation returns the full membership list.
    cmd = [
        "gcloud", "identity", "groups", "memberships", "list",
        f"--group-email={group_email}",
        "--format=json",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error listing members of {group_email}:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)

    memberships = json.loads(result.stdout or "[]")
    emails = []
    for membership in memberships:
        email = membership.get("preferredMemberKey", {}).get("id")
        if email:
            emails.append(email)
    return emails


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group-email", required=True, help="Email of the Google Group to query")
    parser.add_argument(
        "--pattern",
        default="tdr-ingest-sa@datarepo-*",
        help="Glob pattern (fnmatch syntax) to match member emails against",
    )
    parser.add_argument(
        "--output",
        default="matched_members.csv",
        help="CSV file to write matched emails to",
    )
    args = parser.parse_args()

    print(f"Listing members of {args.group_email}...")
    all_emails = list_group_members(args.group_email)
    print(f"Found {len(all_emails)} total members.")

    matched = sorted(email for email in all_emails if fnmatch.fnmatch(email, args.pattern))
    print(f"{len(matched)} members match pattern '{args.pattern}'.")

    with open(args.output, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["email"])
        for email in matched:
            writer.writerow([email])

    print(f"Wrote {len(matched)} matching emails to {args.output}")


if __name__ == "__main__":
    main()
