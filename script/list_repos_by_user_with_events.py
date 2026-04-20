"""
Fetch all repos where a developer committed in the last 3 months
using GitHub GraphQL contributionsCollection API, then update developers.json.
"""

import argparse
import json
import os
import requests
from datetime import datetime, timezone, timedelta
import keyring


GITHUB_TOKEN = keyring.get_password("login2", "github_token")

if not GITHUB_TOKEN:
    raise EnvironmentError("Set GITHUB_TOKEN environment variable")

HEADERS = {
    "Authorization": f"Bearer {GITHUB_TOKEN}",
    "Content-Type": "application/json",
}

QUERY = """
query($login: String!, $from: DateTime!, $to: DateTime!) {
  user(login: $login) {
    contributionsCollection(from: $from, to: $to) {
      commitContributionsByRepository(maxRepositories: 100) {
        repository {
          nameWithOwner
          url
          isPrivate
        }
        contributions {
          totalCount
        }
      }
    }
  }
}
"""

def fetch_repos(username: str, from_dt: datetime, to_dt: datetime) -> list[dict]:
    variables = {
        "login": username,
        "from": from_dt.isoformat(),
        "to": to_dt.isoformat(),
    }
    response = requests.post(
        "https://api.github.com/graphql",
        headers=HEADERS,
        json={"query": QUERY, "variables": variables},
    )
    response.raise_for_status()
    data = response.json()

    if "errors" in data:
        raise RuntimeError(f"GraphQL errors: {data['errors']}")

    contributions = (
        data["data"]["user"]["contributionsCollection"]["commitContributionsByRepository"]
    )
    return contributions


def collect_all_repos(username: str) -> dict:
    """Fetch all repos and return as a dict keyed by repo name."""
    to_dt = datetime.now(timezone.utc)
    from_dt = to_dt - timedelta(days=90)

    repos = fetch_repos(username, from_dt, to_dt)
    repos.sort(key=lambda r: r["contributions"]["totalCount"], reverse=True)
    
    repos_dict = {}
    for entry in repos:
        name = entry["repository"]["nameWithOwner"]
        repos_dict[name] = {
            "url": entry["repository"]["url"],
            "commits": entry["contributions"]["totalCount"],
            "is_private": entry["repository"]["isPrivate"],
        }
    
    return repos_dict


DEVELOPERS_JSON = os.path.join(os.path.dirname(__file__), "..", "developers.json")


def update_developers_json(handle: str, repo_names: list[str]) -> None:
    """Upsert the developer's repos list in developers.json."""
    with open(DEVELOPERS_JSON, "r") as f:
        developers = json.load(f)

    for dev in developers:
        if dev["handle"].lower() == handle.lower():
            dev["repos"] = repo_names
            break
    else:
        developers.append({"handle": handle, "repos": repo_names})

    with open(DEVELOPERS_JSON, "w") as f:
        json.dump(developers, f, indent=2)
        f.write("\n")

    print(f"Updated developers.json for @{handle} with {len(repo_names)} repo(s).")


def main():
    parser = argparse.ArgumentParser(
        description="Fetch repos a developer committed to in the last 90 days."
    )
    parser.add_argument("handle", help="GitHub username")
    args = parser.parse_args()

    repos_dict = collect_all_repos(args.handle)

    if not repos_dict:
        print("No contributions found.")
        return

    print(f"Found {len(repos_dict)} repo(s):\n")
    print(f"{'Commits':<10} {'Repo'}")
    print("-" * 60)
    for name, info in repos_dict.items():
        count = info["commits"]
        private = " [private]" if info["is_private"] else ""
        print(f"{count:<10} {name}{private}")

    print(f"\nTotal repos: {len(repos_dict)}")

    update_developers_json(args.handle, list(repos_dict.keys()))


if __name__ == "__main__":
    main()