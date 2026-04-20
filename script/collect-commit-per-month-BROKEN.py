#!/usr/bin/env python3
"""Collect GitHub commit counts per month for developers listed in developers.json.

Strategy
--------
Rather than the global commit-search API (which inflates counts by including
forks and mirrors), this script:

  1. Lists every repo the developer *owns* that is NOT a fork.
  2. For each such repo, calls GET /repos/{owner}/{repo}/commits with
     ``author`` + ``since`` / ``until`` filters and paginates to count commits.
  3. Sums the per-repo counts to produce the monthly total.

Token is retrieved from the system keyring:
  service  = "login2"
  username = "github_token"

Date range: January 2025 through the current month.
Results are written back to developers.json.
"""


import json
import re
import sys
import time
from calendar import monthrange
from datetime import date
from pathlib import Path

import keyring
import requests
import list_repos_by_user_with_events

print ("""
       THIS DOES NOT GIVE RELIABLE DATA BECAUSE OF A BUG IN THE GITHUB API see https://github.com/orgs/community/discussions/188372
       
       """)
sys.exit(-1)

REPO_ROOT = Path(__file__).parent.parent
DEVELOPERS_FILE = REPO_ROOT / "developers.json"

START = (2025, 10)

MONTH_ABBR = [
    "jan", "feb", "mar", "apr", "may", "jun",
    "jul", "aug", "sep", "oct", "nov", "dec",
]

# Seconds to pause between ordinary API calls (stays well within 5 000 req/h).
INTER_REQUEST_SLEEP = 0.4


# ---------------------------------------------------------------------------
# Date helpers
# ---------------------------------------------------------------------------

def month_key(year: int, month: int) -> str:
    return f"{MONTH_ABBR[month - 1]}-{year}"


def iter_months(end: tuple[int, int]):
    """Yield (year, month) tuples from START through end, inclusive."""
    year, month = START
    end_year, end_month = end
    while (year, month) <= (end_year, end_month):
        yield year, month
        month += 1
        if month > 12:
            month = 1
            year += 1


def month_window(year: int, month: int) -> tuple[str, str]:
    """Return (since, until) ISO-8601 strings that bracket the whole month."""
    last_day = monthrange(year, month)[1]
    since = f"{year}-{month:02d}-01T00:00:00Z"
    # Use first moment of *next* month so the range is half-open [since, until).
    if month == 12:
        until = f"{year + 1}-01-01T00:00:00Z"
    else:
        until = f"{year}-{month + 1:02d}-01T00:00:00Z"
    _ = last_day  # not needed with the half-open strategy
    return since, until


# ---------------------------------------------------------------------------
# I/O
# ---------------------------------------------------------------------------

def load_developers() -> list[dict]:
    text = DEVELOPERS_FILE.read_text()
    # Repair missing commas between adjacent JSON objects (} ... {)
    text = re.sub(r"\}\s*\{", "},{", text)
    return json.loads(text)


# ---------------------------------------------------------------------------
# GitHub API helpers
# ---------------------------------------------------------------------------

def _get_with_retry(
    session: requests.Session,
    url: str,
    params: dict | None = None,
) -> requests.Response:
    """GET with automatic back-off on rate-limit responses (403 / 429)."""
    while True:
        resp = session.get(url, params=params)
        if resp.status_code in (403, 429):
            reset = int(resp.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait = max(reset - time.time(), 0) + 2
            print(f"    Rate limited — waiting {wait:.0f}s …", file=sys.stderr)
            time.sleep(wait)
            continue
        return resp


def get_owned_nonfork_repos(handle: str, session: requests.Session) -> list[str]:
    """Return full_names of repos owned by *handle* that are not forks."""
    repos: list[str] = []
    page = 1
    while True:
        resp = _get_with_retry(
            session,
            f"https://api.github.com/users/{handle}/repos",
            params={"type": "owner", "per_page": 100, "page": page, "sort": "pushed"},
        )
        if resp.status_code == 404:
            print(f"  Warning: user {handle!r} not found (404).", file=sys.stderr)
            break
        if resp.status_code != 200:
            print(
                f"  Warning: could not list repos for {handle} "
                f"(HTTP {resp.status_code}).",
                file=sys.stderr,
            )
            break

        data = resp.json()
        if not isinstance(data, list) or not data:
            break

        for repo in data:
            if not repo.get("fork", False):
                repos.append(repo["full_name"])

        if len(data) < 100:
            break
        page += 1
        time.sleep(INTER_REQUEST_SLEEP)

    return repos


def count_commits_in_repo(
    handle: str,
    full_name: str,
    year: int,
    month: int,
    session: requests.Session,
) -> int:
    """Return the number of commits authored by *handle* in *full_name* during
    the given calendar month.  Paginates through all results."""
    since, until = month_window(year, month)
    count = 0
    page = 1

    while True:
        resp = _get_with_retry(
            session,
            f"https://api.github.com/repos/{full_name}/commits",
            params={
                "author": handle,
                "since": since,
                "until": until,
                "per_page": 100,
                "page": page,
            },
        )

        if resp.status_code == 409:
            # Empty repository or Git database unavailable — treat as zero.
            break
        if resp.status_code == 404:
            # Repo was deleted or made private since we listed it.
            break
        if resp.status_code != 200:
            print(
                f"    Warning: HTTP {resp.status_code} for {full_name} "
                f"{month_key(year, month)}",
                file=sys.stderr,
            )
            break

        data = resp.json()
        if not isinstance(data, list) or not data:
            break

        count += len(data)

        if len(data) < 100:
            break  # last (or only) page

        page += 1
        time.sleep(INTER_REQUEST_SLEEP)

    return count


def get_repos_committed_in_december(handle: str, session: requests.Session) -> list[str]:
    """Return full_names of repos where *handle* has commits in December 2025 using commit search API."""
    december_repos: list[str] = []
    page = 1

    # Search for commits by handle in December 2025
    while True:
        resp = _get_with_retry(
            session,
            "https://api.github.com/search/commits",
            params={
                "q": f"author:{handle} committer-date:2025-12-01..2025-12-31",
                "per_page": 100,
                "page": page,
            },
        )

        if resp.status_code != 200:
            print(
                f"  Warning: commit search failed (HTTP {resp.status_code}).",
                file=sys.stderr,
            )
            break

        data = resp.json()
        items = data.get("items", [])
        if not items:
            break

        # Extract unique repo full_names
        for commit in items:
            repo_name = commit.get("repository", {}).get("full_name")
            if repo_name and repo_name not in december_repos:
                december_repos.append(repo_name)

        if len(items) < 100:
            break  # last page
        page += 1
        time.sleep(INTER_REQUEST_SLEEP)

    return december_repos


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    token = keyring.get_password("login2", "github_token")
    if not token:
        sys.exit(
            "No GitHub token found in keyring "
            "(service='login2', username='github_token')"
        )

    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
    )

    today = date.today()
    end = (today.year, today.month)
    months = list(iter_months(end))

    developers = load_developers()

    for dev in developers:
        handle = dev["handle"]
        print(f"\n{'=' * 60}")
        print(f"Developer: @{handle}")

        # Step 1 — discover non-fork owned repos
        repos = list_repos_by_user_with_events.collect_all_repos(handle)
        print(f"  {len(repos)} repo(s found for user")
        dev["repos"] = repos  # store for transparency / debugging
        time.sleep(INTER_REQUEST_SLEEP)

        dev.setdefault("commits_per_month", {})

        # Step 2 — count commits per month across all repos
        for year, month in months:
            key = month_key(year, month)
            monthly_total = 0
            repo_hits: list[str] = []

            for full_name in repos:
                n = count_commits_in_repo(handle, full_name, year, month, session)
                if n > 0:
                    repo_hits.append(f"{full_name}:{n}")
                    monthly_total += n
                time.sleep(INTER_REQUEST_SLEEP)

            dev["commits_per_month"][key] = monthly_total

            detail = f"  ({', '.join(repo_hits)})" if repo_hits else ""
            print(f"  {key}: {monthly_total}{detail}")

    print("\nDone. developers.json updated.")


if __name__ == "__main__":
    main()
