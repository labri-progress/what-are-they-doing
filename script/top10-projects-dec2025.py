#!/usr/bin/env python3
"""Show the top-10 repositories where steipete committed in December 2025.

Token is retrieved from the system keyring:
  service  = "login2"
  username = "github_token"
"""

import sys
import time
from collections import Counter

import keyring
import requests

HANDLE = "steipete"
SINCE = "2025-12-01"
UNTIL = "2025-12-31"
QUERY = f"author:{HANDLE} committer-date:{SINCE}..{UNTIL}"


def build_session(token: str) -> requests.Session:
    s = requests.Session()
    s.headers.update(
        {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github.cloak-preview+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
    )
    return s


def fetch_all_commits(session: requests.Session) -> list[dict]:
    """Page through all search results and return every commit item."""
    commits = []
    page = 1
    per_page = 100

    while True:
        resp = session.get(
            "https://api.github.com/search/commits",
            params={"q": QUERY, "per_page": per_page, "page": page},
        )

        if resp.status_code in (403, 429):
            reset = int(resp.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait = max(reset - time.time(), 0) + 2
            print(f"Rate limited. Waiting {wait:.0f}s …", file=sys.stderr)
            time.sleep(wait)
            continue  # retry same page

        resp.raise_for_status()
        data = resp.json()

        items = data.get("items", [])
        commits.extend(items)

        total = data.get("total_count", 0)
        print(
            f"  page {page}: fetched {len(items)} commits "
            f"(total reported: {total})",
            file=sys.stderr,
        )

        # GitHub search API caps results at 1 000 items
        if len(items) < per_page or len(commits) >= min(total, 1000):
            break

        page += 1
        time.sleep(1)  # be polite between pages

    return commits


def main() -> None:
    token = keyring.get_password("login2", "github_token")
    if not token:
        sys.exit(
            "No GitHub token found in keyring "
            "(service='login2', username='github_token')"
        )

    session = build_session(token)

    print(f"Fetching commits for {HANDLE} in Dec 2025 …", file=sys.stderr)
    commits = fetch_all_commits(session)
    print(f"Total commits collected: {len(commits)}\n", file=sys.stderr)

    repo_counts: Counter = Counter()
    for item in commits:
        repo = item["repository"]["full_name"]
        repo_counts[repo] += 1

    print(f"Top-10 repositories for @{HANDLE} — December 2025")
    print(f"{'Rank':<5} {'Commits':>7}  Repository")
    print("-" * 55)
    for rank, (repo, count) in enumerate(repo_counts.most_common(10), start=1):
        print(f"{rank:<5} {count:>7}  {repo}")


if __name__ == "__main__":
    main()
