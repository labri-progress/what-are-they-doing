#!/usr/bin/env python3
"""Collect commit data for steveyegge over the 8 considered months (Sep 2025 – Apr 2026).

Fetches the developer's current repo list from GitHub, then collects daily
commit data for each month, writing:

    data/steveyegge-{YYYY-MM}.json

Finally downloads raw per-commit details into data/commits/{sha}.json.

Usage:
    python script/collect-steveyegge.py
    python script/collect-steveyegge.py --no-refresh-repos
    python script/collect-steveyegge.py --force
"""

import argparse
from pathlib import Path

import list_repos_by_user_with_events
from collect_commit_cache_lib import cache_commit_details
from collect_commits_per_day_lib import (
    build_session,
    collect_month_data,
    load_github_token,
    write_month_data,
)

HANDLE = "steveyegge"
MONTHS = [
    (2025, 9), (2025, 10), (2025, 11), (2025, 12),
    (2026, 1), (2026, 2), (2026, 3), (2026, 4),
]
REPO_ROOT = Path(__file__).parent.parent
DATA_DIR = REPO_ROOT / "data"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect 8-month commit data for steveyegge."
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing data files.",
    )
    parser.add_argument(
        "--no-refresh-repos",
        action="store_true",
        help="Use a fixed empty repo list instead of fetching from GitHub.",
    )
    args = parser.parse_args()

    if args.no_refresh_repos:
        repos = []
    else:
        print(f"Fetching repos for @{HANDLE} …")
        repos_dict = list_repos_by_user_with_events.collect_all_repos(HANDLE)
        repos = list(repos_dict.keys())
        print(f"  {len(repos)} repo(s) found")

    session = build_session(load_github_token())
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    month_files: list[Path] = []

    for year, month in MONTHS:
        month_str = f"{year}-{month:02d}"
        out_file = DATA_DIR / f"{HANDLE}-{month_str}.json"
        month_files.append(out_file)

        if out_file.exists() and not args.force:
            print(f"Skipping {month_str}  (already exists: {out_file.relative_to(REPO_ROOT)})")
            continue

        print(f"Collecting commits for @{HANDLE}  {month_str}")
        result, total_saved = collect_month_data(session, HANDLE, repos, year, month)
        out_file = write_month_data(HANDLE, year, month, result)
        print(f"  {total_saved} commits saved to {out_file.relative_to(REPO_ROOT)}")

    existing = [p for p in month_files if p.exists()]
    if existing:
        print(f"\nCaching raw commit details for {len(existing)} month file(s) …")
        cache_commit_details(existing, remove_stale=False, dry_run=False)


if __name__ == "__main__":
    main()
