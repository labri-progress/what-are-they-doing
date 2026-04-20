#!/usr/bin/env python3
"""
Find agentic AI coders on GitHub using the GraphQL API.
Searches for repos and users building autonomous coding agents.
"""

import os
import json
import time
import requests
from datetime import datetime, timedelta

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
API_URL = "https://api.github.com/graphql"

HEADERS = {
    "Authorization": f"Bearer {GITHUB_TOKEN}",
    "Content-Type": "application/json",
}

# Search queries targeting agentic AI coders
SEARCH_QUERIES = [
    "agentic coding AI autonomous agent stars:>50",
    "AI code agent autonomous programming stars:>30",
    "LLM coding agent tool use stars:>50",
    "autonomous software engineer AI agent stars:>20",
    "claude openai coding agent self-improving stars:>10",
]

REPO_SEARCH_GQL = """
query SearchRepos($query: String!, $after: String) {
  search(query: $query, type: REPOSITORY, first: 20, after: $after) {
    pageInfo {
      hasNextPage
      endCursor
    }
    nodes {
      ... on Repository {
        nameWithOwner
        description
        url
        stargazerCount
        forkCount
        pushedAt
        primaryLanguage {
          name
        }
        repositoryTopics(first: 10) {
          nodes {
            topic {
              name
            }
          }
        }
        owner {
          login
          url
          ... on User {
            name
            bio
            followers {
              totalCount
            }
            following {
              totalCount
            }
            repositories(first: 1, orderBy: {field: STARGAZERS, direction: DESC}) {
              totalCount
              nodes {
                stargazerCount
              }
            }
          }
        }
      }
    }
  }
}
"""

USER_DETAILS_GQL = """
query GetUser($login: String!) {
  user(login: $login) {
    login
    name
    bio
    url
    location
    company
    twitterUsername
    websiteUrl
    followers {
      totalCount
    }
    repositories(first: 6, orderBy: {field: STARGAZERS, direction: DESC}, privacy: PUBLIC) {
      totalCount
      nodes {
        nameWithOwner
        description
        stargazerCount
        primaryLanguage { name }
        repositoryTopics(first: 5) {
          nodes { topic { name } }
        }
      }
    }
    pinnedItems(first: 6, types: REPOSITORY) {
      nodes {
        ... on Repository {
          nameWithOwner
          description
          stargazerCount
        }
      }
    }
  }
}
"""


def run_query(query: str, variables: dict) -> dict:
    payload = {"query": query, "variables": variables}
    resp = requests.post(API_URL, headers=HEADERS, json=payload, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    if "errors" in data:
        raise RuntimeError(f"GraphQL errors: {data['errors']}")
    return data


def search_repos(query: str, max_repos: int = 20) -> list[dict]:
    repos = []
    cursor = None
    while len(repos) < max_repos:
        variables = {"query": query, "after": cursor}
        data = run_query(REPO_SEARCH_GQL, variables)
        search = data["data"]["search"]
        repos.extend(search["nodes"])
        if not search["pageInfo"]["hasNextPage"]:
            break
        cursor = search["pageInfo"]["endCursor"]
        time.sleep(0.5)
    return repos[:max_repos]


def get_user_details(login: str) -> dict:
    data = run_query(USER_DETAILS_GQL, {"login": login})
    return data["data"]["user"]


def score_repo(repo: dict) -> float:
    """Score a repo by relevance to agentic AI coding."""
    score = 0.0
    topics = [n["topic"]["name"].lower() for n in repo.get("repositoryTopics", {}).get("nodes", [])]
    desc = (repo.get("description") or "").lower()

    agentic_keywords = [
        "agent", "agentic", "autonomous", "coding-agent", "ai-agent",
        "llm-agent", "code-generation", "self-improving", "tool-use",
        "computer-use", "software-engineer", "devin", "swe-agent",
    ]

    for kw in agentic_keywords:
        if kw in topics:
            score += 3
        if kw in desc:
            score += 1

    stars = repo.get("stargazerCount", 0)
    score += min(stars / 100, 10)  # cap at 10 pts

    # Recency bonus
    pushed = repo.get("pushedAt", "")
    if pushed:
        pushed_dt = datetime.fromisoformat(pushed.replace("Z", "+00:00"))
        days_ago = (datetime.now(pushed_dt.tzinfo) - pushed_dt).days
        if days_ago < 30:
            score += 5
        elif days_ago < 90:
            score += 2

    return score


def dedupe_users(repos: list[dict]) -> dict[str, dict]:
    """Extract unique users from repos."""
    users = {}
    for repo in repos:
        owner = repo.get("owner", {})
        login = owner.get("login")
        if login and login not in users:
            users[login] = {
                "login": login,
                "name": owner.get("name"),
                "bio": owner.get("bio"),
                "url": owner.get("url"),
                "followers": owner.get("followers", {}).get("totalCount", 0),
                "total_repos": owner.get("repositories", {}).get("totalCount", 0),
                "top_repo_stars": (
                    owner.get("repositories", {}).get("nodes", [{}])[0].get("stargazerCount", 0)
                    if owner.get("repositories", {}).get("nodes") else 0
                ),
                "notable_repos": [],
            }
        if login:
            users[login]["notable_repos"].append({
                "name": repo.get("nameWithOwner"),
                "description": repo.get("description"),
                "stars": repo.get("stargazerCount"),
                "score": score_repo(repo),
            })
    return users


def main():
    if not GITHUB_TOKEN:
        print("ERROR: Set GITHUB_TOKEN environment variable first.")
        print("  export GITHUB_TOKEN=ghp_yourtoken")
        return

    print("Searching GitHub for agentic AI coders...\n")

    all_repos = []
    for q in SEARCH_QUERIES:
        print(f"Query: {q}")
        try:
            repos = search_repos(q, max_repos=15)
            all_repos.extend(repos)
            print(f"  Found {len(repos)} repos")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(1)

    print(f"\nTotal repos fetched (with dupes): {len(all_repos)}")

    # Score and rank repos
    scored_repos = sorted(all_repos, key=score_repo, reverse=True)

    # Dedupe and build user profiles
    users = dedupe_users(scored_repos)

    # Score users
    def user_score(u):
        repo_scores = [r["score"] for r in u["notable_repos"]]
        return sum(repo_scores) + u["followers"] * 0.01

    ranked_users = sorted(users.values(), key=user_score, reverse=True)

    print(f"Unique users found: {len(ranked_users)}")
    print("\n" + "=" * 60)
    print("TOP AGENTIC AI CODERS")
    print("=" * 60)

    results = []
    for i, user in enumerate(ranked_users[:30], 1):
        print(f"\n#{i} {user['login']}")
        if user.get("name"):
            print(f"  Name: {user['name']}")
        if user.get("bio"):
            print(f"  Bio: {user['bio'][:100]}")
        print(f"  GitHub: {user['url']}")
        print(f"  Followers: {user['followers']}")

        top_repos = sorted(user["notable_repos"], key=lambda r: r["score"], reverse=True)[:3]
        for repo in top_repos:
            stars = repo["stars"]
            print(f"  Repo: {repo['name']} ({stars} stars)")
            if repo.get("description"):
                print(f"    {repo['description'][:80]}")

        results.append(user)

    # Save full results
    output_file = "agentic_ai_coders.json"
    with open(f"{output_file}", "w") as f:
        json.dump({
            "generated_at": datetime.now().isoformat(),
            "total_users": len(ranked_users),
            "top_users": ranked_users[:50],
        }, f, indent=2)

    print(f"\nFull results saved to {output_file}")


if __name__ == "__main__":
    main()