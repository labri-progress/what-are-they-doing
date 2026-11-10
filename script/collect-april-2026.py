#!/usr/bin/env python3
"""Collect April 2026 daily commit data and raw commit details for all developers.

By default this refreshes each developer's repo list from recent GitHub
contributions, writes:

    data/{developer}-2026-04.json

and then downloads raw commit detail cache files for that month into:

    data/commits/{sha}.json
"""

import argparse
import json
from pathlib import Path

from collect_commit_cache_lib import cache_commit_details
from collect_commits_per_day_lib import (
    build_session,
    collect_month_data,
    load_github_token,
    write_month_data,
)

REPO_ROOT = Path(__file__).parent.parent
DEVELOPERS_FILE = REPO_ROOT / "developers.json"
TARGET_YEAR = 2026
TARGET_MONTH = 4
TARGET_MONTH_STR = f"{TARGET_YEAR}-{TARGET_MONTH:02d}"


def load_developers() -> list[dict]:
    return json.loads(DEVELOPERS_FILE.read_text())


def refresh_repos(developers: list[dict]) -> bool:
    import list_repos_by_user_with_events

    changed = False
    for developer in developers:
        handle = developer["handle"]
        print(f"Refreshing repos for @{handle}")
        repos_dict = list_repos_by_user_with_events.collect_all_repos(handle)
        repo_names = list(repos_dict.keys())
        if not repo_names:
            print(f"  Warning: no recent repos found for @{handle}; keeping existing repo list")
            continue
        if developer.get("repos") != repo_names:
            developer["repos"] = repo_names
            changed = True
        print(f"  {len(repo_names)} repo(s)")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect April 2026 daily commit data and raw commit details for all tracked developers."
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing data/{developer}-2026-04.json files.",
    )
    parser.add_argument(
        "--no-refresh-repos",
        action="store_true",
        help="Reuse repos from developers.json instead of refreshing recent repos first.",
    )
    args = parser.parse_args()

    developers = load_developers()
    if not args.no_refresh_repos and refresh_repos(developers):
        DEVELOPERS_FILE.write_text(json.dumps(developers, indent=2) + "\n")
        print(f"Updated {DEVELOPERS_FILE.relative_to(REPO_ROOT)}")

    session = build_session(load_github_token())
    month_files: list[Path] = []

    for developer in developers:
        handle = developer["handle"]
        repos = developer.get("repos", [])
        out_file = REPO_ROOT / "data" / f"{handle}-{TARGET_MONTH_STR}.json"
        month_files.append(out_file)

        if out_file.exists() and not args.force:
            print(f"Skipping @{handle}  {TARGET_MONTH_STR}  (file already exists: {out_file.relative_to(REPO_ROOT)})")
            continue

        print(f"Collecting commits for @{handle}  {TARGET_MONTH_STR}")
        print(f"  {len(repos)} repo(s): {', '.join(repos)}")
        result, total_saved = collect_month_data(session, handle, repos, TARGET_YEAR, TARGET_MONTH)
        out_file = write_month_data(handle, TARGET_YEAR, TARGET_MONTH, result)
        print(f"\nDone. {total_saved} commits saved to {out_file.relative_to(REPO_ROOT)}")

    existing_month_files = [path for path in month_files if path.exists()]
    if not existing_month_files:
        print(f"No monthly data files found for {TARGET_MONTH_STR}; skipping raw commit download.")
        return

    print(f"\nCaching raw commit details for {TARGET_MONTH_STR} …")
    cache_commit_details(existing_month_files, remove_stale=False, dry_run=False)


if __name__ == "__main__":
    main()
