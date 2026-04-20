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
import json
import sys
import time
from pathlib import Path

import keyring
import requests

REPO_ROOT = Path(__file__).parent.parent
DATA_DIR = REPO_ROOT / "data"
COMMITS_DIR = DATA_DIR / "commits"


def build_session(token: str) -> requests.Session:
    s = requests.Session()
    s.headers.update(
        {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
    )
    return s


def get_with_retry(session: requests.Session, url: str) -> dict | None:
    """GET with automatic rate-limit back-off. Returns None on 404."""
    while True:
        resp = session.get(url)

        if resp.status_code in (403, 429):
            reset = int(resp.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait = max(reset - time.time(), 0) + 2
            print(f"    Rate limited. Waiting {wait:.0f}s …", file=sys.stderr)
            time.sleep(wait)
            continue

        if resp.status_code == 404:
            return None  # private repo or deleted commit

        resp.raise_for_status()
        return resp.json()


def collect_referenced_commits(data_files: list[Path] | None = None) -> dict[str, str]:
    """Return {sha: api_url} for every commit in the given data files.

    If data_files is None, all data/*.json files are scanned.
    """
    result: dict[str, str] = {}
    if data_files is None:
        data_files = sorted(DATA_DIR.glob("*.json"))
    for data_file in data_files:
        data = json.loads(data_file.read_text())
        for day_data in data.get("days", {}).values():
            for commit in day_data.get("commits", []):
                sha = commit.get("sha", "")
                url = commit.get("url", "")
                if sha and url and sha not in result:
                    result[sha] = url
    return result


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

    # ── Collect all SHAs referenced by data files ─────────────────────────
    print("Scanning data files …")
    referenced = collect_referenced_commits(data_files)
    print(f"  {len(referenced)} unique SHAs referenced in data files")

    # ── Determine which commits need downloading ───────────────────────────
    COMMITS_DIR.mkdir(parents=True, exist_ok=True)
    existing = {p.stem for p in COMMITS_DIR.glob("*.json")}

    to_download = {sha: url for sha, url in referenced.items() if sha not in existing}
    # Only remove stale files when scanning all data files (no filter applied)
    to_delete = (
        [COMMITS_DIR / f"{sha}.json" for sha in existing if sha not in referenced]
        if data_files is None
        else []
    )

    print(f"  {len(existing)} commit files already cached")
    print(f"  {len(to_download)} commits to download")
    print(f"  {len(to_delete)} stale cache files to remove")

    # ── Remove stale files ─────────────────────────────────────────────────
    if to_delete:
        print(f"\nRemoving {len(to_delete)} stale cache file(s) …")
        for path in sorted(to_delete):
            print(f"  - {path.name}")
            if not args.dry_run:
                path.unlink()

    # ── Download missing commits ───────────────────────────────────────────
    if not to_download:
        print("\nNothing to download.")
        return

    if args.dry_run:
        print(f"\n[dry-run] Would download {len(to_download)} commit(s).")
        return

    token = keyring.get_password("login2", "github_token_2")
    if not token:
        sys.exit(
            "No GitHub token found in keyring "
            "(service='login2', username='github_token_2')"
        )

    session = build_session(token)

    print(f"\nDownloading {len(to_download)} commit(s) …")
    done = 0
    skipped = 0
    errors = 0

    for sha, url in sorted(to_download.items()):
        dest = COMMITS_DIR / f"{sha}.json"
        data = get_with_retry(session, url)

        if data is None:
            # 404: commit not accessible; write an empty sentinel so we don't retry
            detail = {"message": "", "files": []}
            skipped += 1
            print(f"  404 {sha[:12]}  (private/deleted) → empty sentinel written")
        else:
            detail = {
                "message": data.get("commit", {}).get("message", ""),
                "files": data.get("files", []),
            }
            done += 1
            if done % 100 == 0:
                print(f"  {done}/{len(to_download)} downloaded …")

        dest.write_text(json.dumps(detail, indent=2) + "\n")
        time.sleep(0.5)

    print(
        f"\nDone. {done} downloaded, {skipped} not accessible (404), {errors} errors."
    )


if __name__ == "__main__":
    main()
