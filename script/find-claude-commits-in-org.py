import json
import re
import requests
import keyring
from pathlib import Path

GITHUB_TOKEN = keyring.get_password("login2","github_token_2")       # required
OWNER = "anthropics"                     # user/org to scan
TARGET_EMAIL = "noreply@anthropic.com"  # commit author email
DATA_FILE = Path(__file__).parent.parent / "data" / "claude-repos.json"

session = requests.Session()
session.headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
session.headers["Accept"] = "application/vnd.github+json"

def get_repos(owner):
    repos = []
    page = 1
    while True:
        r = session.get(
            f"https://api.github.com/users/{owner}/repos",
            params={"per_page": 100, "page": page},
        )
        r.raise_for_status()
        batch = r.json()
        if not batch:
            break
        repos.extend(batch)
        page += 1
    return repos

def count_email_commits(owner, repo, email):
    total = 0
    page = 1
    while True:
        r = session.get(
            f"https://api.github.com/repos/{owner}/{repo}/commits",
            params={"author": email, "per_page": 100, "page": page},
        )
        # 409 = empty repo, skip it
        if r.status_code == 409:
            break

        r.raise_for_status()
        commits = r.json()
        if not commits:
            break
        total += len(commits)
        page += 1
    return total

def count_coauthored_commits(owner, repo, email):
    r = session.get(
        f"https://api.github.com/repos/{owner}/{repo}/commits",
        params={"per_page": 100},
    )
    if r.status_code == 409:
        return 0
    r.raise_for_status()
    commits = r.json()
    pattern = re.compile(r"^Co-authored-by:.*<" + re.escape(email) + r">", re.IGNORECASE | re.MULTILINE)
    return sum(1 for c in commits if pattern.search(c["commit"]["message"]))


def load_results():
    if DATA_FILE.exists():
        with open(DATA_FILE) as f:
            return {entry["repo"]: entry for entry in json.load(f)}
    return {}

def save_results(results_dict):
    DATA_FILE.parent.mkdir(parents=True, exist_ok=True)
    entries = sorted(results_dict.values(), key=lambda x: x["authored"] + x["coauthored"], reverse=True)
    with open(DATA_FILE, "w") as f:
        json.dump(entries, f, indent=2)

def main():
    repos = get_repos(OWNER)
    # print(repos)
    results = load_results()

    for repo in repos:
        name = repo["name"]
        if name in results:
            print(f"Skipping {name} (already scanned)")
            continue
        print(f"Scanning {name}…")
        authored = count_email_commits(OWNER, name, TARGET_EMAIL)
        coauthored = count_coauthored_commits(OWNER, name, TARGET_EMAIL)
        results[name] = {"repo": name, "authored": authored, "coauthored": coauthored}
        save_results(results)

    entries = sorted(results.values(), key=lambda x: x["authored"] + x["coauthored"], reverse=True)
    print(f"\nRepositories with commits by {TARGET_EMAIL}")
    print(f"{'repo':<40} {'authored':>8} {'co-authored':>12}")
    print("-" * 62)
    for entry in entries:
        if entry["authored"] or entry["coauthored"]:
            print(f"{entry['repo']:<40} {entry['authored']:>8} {entry['coauthored']:>12}")

if __name__ == "__main__":
    main()