#!/usr/bin/env python3
"""Detect which coding agents are used in a developer's commits.

Uses agent-mining heuristics to match three signal types per commit:
  - commit_message  co-author trailers + message prefix patterns
  - changed_files   agent config files (.cursor/, CLAUDE.md, …)

Commit file details are read from data/commits/{sha}.json (pre-fetched by
analyze-commit-quality.py). Commits without a cache entry are still checked
via the message/author signals.

Usage:
    python script/detect-agents.py data/steipete-2025-12.json
"""

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Make agent-mining importable
REPO_ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(REPO_ROOT / "agent-mining"))

from heuristic import load_heuristics, _match_pattern  # noqa: E402

AGENTS_DIR = REPO_ROOT / "agent-mining" / "agents"
COMMITS_DIR = REPO_ROOT / "data" / "commits"

BAR_CHAR = "█"
BAR_WIDTH = 40


# ── Matching ──────────────────────────────────────────────────────────────────

def match_files(heuristics_list, changed_filenames: list[str]) -> bool:
    """Return True if any changed file matches any file pattern in any heuristic."""
    for h in heuristics_list:
        for pattern in h.files:
            for filename in changed_filenames:
                if _match_pattern(pattern, filename):
                    return True
    return False


def detect_agents(
    commit_item: dict,
    all_heuristics: dict[str, list],
    detail: dict,
) -> list[str]:
    """Return list of agent names that match this commit."""
    # Prefer the full message from the detail cache — the search API truncates
    # the body, which cuts off Co-Authored-By trailers.
    msg = detail.get("message") or commit_item.get("commit", {}).get("message", "")
    author = commit_item.get("commit", {}).get("author", {})
    commit_author = f"{author.get('name', '')} <{author.get('email', '')}>"
    filenames = [f["filename"] for f in detail.get("files", [])]

    matched = []
    for agent_name, heuristics_list in all_heuristics.items():
        for h in heuristics_list:
            if h.match_commit(msg, commit_author):
                matched.append(agent_name)
                break
        else:
            if filenames and match_files(heuristics_list, filenames):
                matched.append(agent_name)

    return matched


# ── Data loading ──────────────────────────────────────────────────────────────

def load_commits(path: Path) -> list[dict]:
    data = json.loads(path.read_text())
    commits = []
    for day_data in data.get("days", {}).values():
        commits.extend(day_data.get("commits", []))
    return commits


def load_cached_detail(sha: str) -> dict:
    """Return {"message": str, "files": list}.
    Handles both the new dict format and the old bare-list format.
    """
    p = COMMITS_DIR / f"{sha}.json"
    if not p.exists():
        return {"message": "", "files": []}
    raw = json.loads(p.read_text())
    if isinstance(raw, list):          # old format: bare files array
        return {"message": "", "files": raw}
    return raw


# ── Display ───────────────────────────────────────────────────────────────────

def bar(count: int, total: int, max_count: int) -> str:
    filled = round(count / max_count * BAR_WIDTH) if max_count else 0
    pct = f" {count / total * 100:.0f}%"
    block = BAR_CHAR * filled
    if filled >= len(pct) + 1:
        return block[: filled - len(pct)] + pct
    return block + pct


def section(title: str) -> None:
    print(f"\n{title}")
    print("─" * len(title))


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <data-file.json>", file=sys.stderr)
        sys.exit(1)

    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"File not found: {path}", file=sys.stderr)
        sys.exit(1)

    all_heuristics = load_heuristics(str(AGENTS_DIR))
    commits = load_commits(path)
    n = len(commits)

    cached = sum(1 for c in commits if (COMMITS_DIR / f"{c.get('sha','')}.json").exists())
    full_msg = sum(1 for c in commits
                   if load_cached_detail(c.get("sha","")).get("message"))
    print(f"Commits: {n}  |  cached: {cached}  |  full message: {full_msg}  |  agents: {len(all_heuristics)}")
    if full_msg < n:
        print(f"  tip: run analyze-commit-quality.py first to fetch full messages", file=sys.stderr)

    # Per-commit detection
    agent_hits: Counter = Counter()
    examples: dict[str, list[str]] = defaultdict(list)
    agent_commit_count: Counter = Counter()
    multi_agent_commits = 0

    for commit in commits:
        sha = commit.get("sha", "")
        detail = load_cached_detail(sha)
        matched = detect_agents(commit, all_heuristics, detail)

        if len(matched) > 1:
            multi_agent_commits += 1
        if matched:
            agent_commit_count["any"] += 1

        for agent in matched:
            agent_hits[agent] += 1
            msg = commit.get("commit", {}).get("message", "").split("\n")[0][:70]
            if len(examples[agent]) < 3:
                examples[agent].append(f"{sha[:7]}  {msg}")

    # ── Report ───────────────────────────────────────────────────────────────
    print(f"\nAgent Detection — {path.stem}")
    print("=" * 55)
    ai_commits = agent_commit_count["any"]
    print(f"  Commits with ≥1 agent signal : {ai_commits} / {n}  ({ai_commits/n*100:.0f}%)")
    print(f"  Commits with >1 agent signal : {multi_agent_commits}")

    if not agent_hits:
        print("\n  No agent signals detected.")
        return

    section(f"Agents detected  (n={n} commits)")
    max_c = agent_hits.most_common(1)[0][1]
    for agent, count in agent_hits.most_common():
        b = bar(count, n, max_c)
        print(f"  {agent:<25} {b:<{BAR_WIDTH + 6}} {count:>4}")

    section("Example commits per agent")
    for agent, count in agent_hits.most_common():
        print(f"\n  {agent}  ({count} commits)")
        for ex in examples[agent]:
            print(f"    {ex}")


if __name__ == "__main__":
    main()
