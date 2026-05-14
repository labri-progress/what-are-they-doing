#!/usr/bin/env python3
"""Download individual commit details and clean up stale cache files.

For every commit SHA listed in any data file (data/*.json) that is not yet
cached in data/commits/{sha}.json, this script fetches the commit detail from
the GitHub API and writes it to the cache.

It also removes any data/commits/{sha}.json file whose SHA does not appear in
any data file.

Cached files use the format expected by analyze-commit-quality.py:
    {"message": "<commit message>", "files": [<GitHub file objects>]}

Token retrieved from keyring: service='login2', username='github_token_2'.

Usage:
    python script/collect-individual-commits.py
    python script/collect-individual-commits.py --developer steipete
    python script/collect-individual-commits.py --developer steipete --month 2026-02
    python script/collect-individual-commits.py --dry-run
"""

import argparse
import sys
from pathlib import Path

from collect_commit_cache_lib import cache_commit_details

REPO_ROOT = Path(__file__).parent.parent
DATA_DIR = REPO_ROOT / "data"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Download missing commit details and remove stale cache files."
    )
    parser.add_argument("--developer", metavar="HANDLE", help="Limit to data files for this developer.")
    parser.add_argument("--month", metavar="YYYY-MM", help="Limit to data files for this month.")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would be done without making any changes.",
    )
    args = parser.parse_args()

    # ── Determine which data files to scan ────────────────────────────────
    if args.developer or args.month:
        developer_pat = args.developer or "*"
        month_pat = args.month or "*"
        data_files = sorted(DATA_DIR.glob(f"{developer_pat}-{month_pat}.json"))
        if not data_files:
            sys.exit(
                f"No data files found matching developer={args.developer!r}, month={args.month!r}"
            )
    else:
        data_files = None  # all files

    stats = cache_commit_details(
        data_files,
        remove_stale=data_files is None,
        dry_run=args.dry_run,
    )
    if args.dry_run or stats["downloaded"] or stats["skipped"] or stats["deleted"]:
        return


if __name__ == "__main__":
    main()
