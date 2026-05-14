#!/usr/bin/env python3
"""Collect commits per day for a developer in a given month.

Repos are read from developers.json in the repository root.

Usage:
    python script/collect-commits-per-day.py --developer steipete --month 2025-12
    python script/collect-commits-per-day.py --developer steipete  # Sep 2025 – Feb 2026
    python script/collect-commits-per-day.py --all                 # all developers, Sep 2025 – Feb 2026

Output:
    data/{developer}-{YYYY-MM}.json  — one file for the whole month

Token is retrieved from the system keyring:
  service  = "login2"
  username = "github_token"
"""

import argparse
import sys
import json
from pathlib import Path

from collect_commits_per_day_lib import (
    DATA_DIR,
    build_session,
    collect_month_data,
    load_github_token,
    write_month_data,
)

REPO_ROOT = Path(__file__).parent.parent
DEVELOPERS_FILE = REPO_ROOT / "developers.json"


def parse_month(value: str) -> tuple[int, int]:
    try:
        year_str, month_str = value.split("-")
        year, month = int(year_str), int(month_str)
        if not (1 <= month <= 12):
            raise ValueError
        return year, month
    except (ValueError, AttributeError):
        raise argparse.ArgumentTypeError(
            f"Invalid month '{value}'. Expected YYYY-MM, e.g. 2025-12"
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect commits per day for a GitHub developer."
    )
    DEFAULT_MONTHS = [
        (2025, 9), (2025, 10), (2025, 11), (2025, 12),
        (2026, 1), (2026, 2),
    ]

    parser.add_argument("--developer", help="GitHub handle")
    parser.add_argument("--all", action="store_true", help="Collect for all developers in developers.json")
    parser.add_argument(
        "--month",
        type=parse_month,
        metavar="YYYY-MM",
        help="Month to collect, e.g. 2025-12 (default: Sep 2025 – Feb 2026)",
    )
    args = parser.parse_args()

    if not args.all and not args.developer:
        parser.error("one of --developer or --all is required")

    months = [args.month] if args.month else DEFAULT_MONTHS

    developers = json.loads(DEVELOPERS_FILE.read_text())
    if args.all:
        dev_entries = developers
    else:
        dev_entry = next((d for d in developers if d["handle"] == args.developer), None)
        if dev_entry is None:
            sys.exit(f"Developer '{args.developer}' not found in {DEVELOPERS_FILE}")
        dev_entries = [dev_entry]

    session = build_session(load_github_token())
    DATA_DIR.mkdir(parents=True, exist_ok=True)

    for dev_entry in dev_entries:
        handle = dev_entry["handle"]
        repos = dev_entry["repos"]

        for year, month in months:
            month_str = f"{year}-{month:02d}"
            out_file = DATA_DIR / f"{handle}-{month_str}.json"

            if out_file.exists():
                print(f"Skipping @{handle}  {month_str}  (file already exists: {out_file.relative_to(REPO_ROOT)})")
                continue

            print(f"Collecting commits for @{handle}  {month_str}")
            print(f"  {len(repos)} repo(s) from developers.json: {', '.join(repos)}")

            result, total_saved = collect_month_data(session, handle, repos, year, month)
            out_file = write_month_data(handle, year, month, result)
            print(f"\nDone. {total_saved} commits saved to {out_file.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
