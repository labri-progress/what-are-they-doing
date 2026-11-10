#!/usr/bin/env python3
"""Shared helpers for caching raw per-commit GitHub API data."""

import json
import sys
import time
from pathlib import Path

from collect_commits_per_day_lib import DATA_DIR, build_session, load_github_token

COMMITS_DIR = DATA_DIR / "commits"


def get_with_retry(session, url: str) -> dict | None:
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
            return None

        resp.raise_for_status()
        return resp.json()


def collect_referenced_commits(data_files: list[Path] | None = None) -> dict[str, str]:
    """Return {sha: api_url} for every commit referenced by the given data files."""
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


def cache_commit_details(
    data_files: list[Path] | None = None,
    *,
    remove_stale: bool = False,
    dry_run: bool = False,
) -> dict[str, int]:
    """Download missing raw commit data for the given monthly data files."""
    print("Scanning data files …")
    referenced = collect_referenced_commits(data_files)
    print(f"  {len(referenced)} unique SHAs referenced in data files")

    COMMITS_DIR.mkdir(parents=True, exist_ok=True)
    existing = {p.stem for p in COMMITS_DIR.glob("*.json")}
    to_download = {sha: url for sha, url in referenced.items() if sha not in existing}
    to_delete = (
        [COMMITS_DIR / f"{sha}.json" for sha in existing if sha not in referenced]
        if remove_stale
        else []
    )

    print(f"  {len(existing)} commit files already cached")
    print(f"  {len(to_download)} commits to download")
    print(f"  {len(to_delete)} stale cache files to remove")

    if to_delete:
        print(f"\nRemoving {len(to_delete)} stale cache file(s) …")
        for path in sorted(to_delete):
            print(f"  - {path.name}")
            if not dry_run:
                path.unlink()

    if not to_download:
        print("\nNothing to download.")
        return {"downloaded": 0, "skipped": 0, "deleted": len(to_delete)}

    if dry_run:
        print(f"\n[dry-run] Would download {len(to_download)} commit(s).")
        return {"downloaded": 0, "skipped": 0, "deleted": len(to_delete)}

    session = build_session(load_github_token())

    print(f"\nDownloading {len(to_download)} commit(s) …")
    downloaded = 0
    skipped = 0

    for sha, url in sorted(to_download.items()):
        dest = COMMITS_DIR / f"{sha}.json"
        data = get_with_retry(session, url)

        if data is None:
            detail = {"message": "", "files": []}
            skipped += 1
            print(f"  404 {sha[:12]}  (private/deleted) → empty sentinel written")
        else:
            detail = {
                "message": data.get("commit", {}).get("message", ""),
                "files": data.get("files", []),
            }
            downloaded += 1
            if downloaded % 100 == 0:
                print(f"  {downloaded}/{len(to_download)} downloaded …")

        dest.write_text(json.dumps(detail, indent=2) + "\n")
        time.sleep(0.5)

    print(f"\nDone. {downloaded} downloaded, {skipped} not accessible (404).")
    return {"downloaded": downloaded, "skipped": skipped, "deleted": len(to_delete)}
