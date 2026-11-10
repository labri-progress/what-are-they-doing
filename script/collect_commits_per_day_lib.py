#!/usr/bin/env python3
"""Shared helpers for collecting daily GitHub commits into monthly data files."""

import json
import sys
import time
from calendar import monthrange
from datetime import date, timedelta
from pathlib import Path

import keyring
import requests

REPO_ROOT = Path(__file__).parent.parent
DATA_DIR = REPO_ROOT / "data"

PER_PAGE = 100
TOKEN_USERNAMES = ("github_token", "github_token_2")


def load_github_token() -> str:
    for username in TOKEN_USERNAMES:
        token = keyring.get_password("login2", username)
        if token:
            return token
    sys.exit(
        "No GitHub token found in keyring "
        "(service='login2', username in {'github_token', 'github_token_2'})"
    )


def build_session(token: str) -> requests.Session:
    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
    )
    return session


def get_with_retry(session: requests.Session, url: str, params: dict) -> requests.Response:
    while True:
        resp = session.get(url, params=params)

        if resp.status_code in (403, 429):
            reset = int(resp.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait = max(reset - time.time(), 0) + 2
            print(f"    Rate limited. Waiting {wait:.0f}s …", file=sys.stderr)
            time.sleep(wait)
            continue

        return resp


def fetch_commits_for_repo_day(
    session: requests.Session, repo: str, handle: str, day: date
) -> list[dict]:
    """Fetch all commits by handle in repo on a single day."""
    since = f"{day.isoformat()}T00:00:00Z"
    until = f"{(day + timedelta(days=1)).isoformat()}T00:00:00Z"
    url = f"https://api.github.com/repos/{repo}/commits"

    commits: list[dict] = []
    page = 1
    while True:
        resp = get_with_retry(
            session,
            url,
            {"author": handle, "since": since, "until": until, "per_page": PER_PAGE, "page": page},
        )
        if resp.status_code in (404, 409):
            return []
        resp.raise_for_status()

        items = resp.json()
        if not items:
            break
        commits.extend(items)
        if len(items) < PER_PAGE:
            break
        page += 1

    return commits


def collect_month_data(
    session: requests.Session,
    handle: str,
    repos: list[str],
    year: int,
    month: int,
) -> tuple[dict, int]:
    num_days = monthrange(year, month)[1]
    month_str = f"{year}-{month:02d}"
    result = {
        "developer": handle,
        "month": month_str,
        "repos": repos,
        "days": {},
    }
    total_saved = 0

    for day_num in range(1, num_days + 1):
        day = date(year, month, day_num)
        day_str = day.isoformat()

        all_commits = []
        for repo in repos:
            commits = fetch_commits_for_repo_day(session, repo, handle, day)
            all_commits.extend(commits)
            if commits:
                time.sleep(0.5)

        total_saved += len(all_commits)
        result["days"][day_str] = {
            "total_count": len(all_commits),
            "sampled": len(all_commits),
            "commits": all_commits,
        }
        print(f"  {day_str}  {len(all_commits):>3} commits")
        time.sleep(1)

    return result, total_saved


def write_month_data(handle: str, year: int, month: int, result: dict) -> Path:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    out_file = DATA_DIR / f"{handle}-{year}-{month:02d}.json"
    out_file.write_text(json.dumps(result, indent=2) + "\n")
    return out_file
