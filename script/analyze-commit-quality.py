#!/usr/bin/env python3
"""Analyse commit quality: good software engineering vs dirty commits.

For each commit the script fetches changed files via the GitHub API and
applies these heuristics:

  GOOD signals
  ─────────────
  fix_with_test     fix/bug commit that also touches test files
  focused           ≤5 files, all within ≤2 top-level directories
  pure_concern      only one concern type changed (source, test, docs, …)

  DIRTY signals
  ─────────────
  fix_no_test       fix commit with zero test files changed
  scattered         >10 files spanning many unrelated directories
  huge_diff         >500 lines changed
  mixed_concerns    source + CI/config/docs bundled together
  no_test_ever      feat commit, no tests at all

Fetched commit details are cached in data/commits/{sha}.json — one file
per commit, shared across all analyses. Missing files are fetched on demand.

Usage:
    python script/analyze-commit-quality.py data/steipete-2025-12.json

Token retrieved from keyring: service='login2', username='github_token'.
"""

import json
import re
import sys
import time
from collections import Counter
from pathlib import Path, PurePosixPath

import keyring
import requests

BAR_CHAR = "█"
BAR_WIDTH = 40

CC_RE = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?\s*:\s*",
)

# ── File classification ───────────────────────────────────────────────────────

TEST_DIR_PARTS = {"test", "tests", "spec", "specs", "__tests__", "testing"}
TEST_NAME_RE = re.compile(
    r"(^test_|_test\.|\.test\.|tests?\.|spec\.|\.spec\.|Tests\.|Spec\.)", re.I
)
CI_TOP = {".github", ".gitlab", ".circleci", ".travis", ".buildkite", ".drone"}
SOURCE_EXTS = {
    ".swift", ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs",
    ".java", ".kt", ".c", ".cpp", ".h", ".m", ".mm", ".rb", ".php",
    ".cs", ".scala", ".ex", ".exs", ".clj", ".zig", ".lua",
}
CONFIG_EXTS = {".json", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf", ".env"}
CONFIG_NAMES = {
    ".gitignore", ".gitattributes", ".editorconfig", ".prettierrc",
    ".eslintrc", ".eslintrc.json", ".eslintrc.js", ".nvmrc", ".python-version",
}
GENERATED_NAMES = {
    "package-lock.json", "yarn.lock", "poetry.lock",
    "package.resolved", "cargo.lock", "gemfile.lock",
}
DOC_EXTS = {".md", ".rst", ".txt", ".adoc", ".rdoc"}


def classify_file(filename: str) -> str:
    p = PurePosixPath(filename)
    parts = [pt.lower() for pt in p.parts]
    name = p.name.lower()
    suffix = p.suffix.lower()

    if p.name in GENERATED_NAMES or name in GENERATED_NAMES:
        return "generated"
    if parts[0] in CI_TOP:
        return "ci"
    if name in ("makefile", "dockerfile", ".dockerignore", "justfile"):
        return "build"
    if any(part in TEST_DIR_PARTS for part in parts) or TEST_NAME_RE.search(p.name):
        return "test"
    if suffix in DOC_EXTS or parts[0] in ("docs", "doc", "documentation"):
        return "docs"
    if suffix in SOURCE_EXTS:
        return "source"
    if suffix in CONFIG_EXTS or name in CONFIG_NAMES:
        return "config"
    return "other"


# ── GitHub API ────────────────────────────────────────────────────────────────

def build_session(token: str) -> requests.Session:
    s = requests.Session()
    s.headers.update({
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    return s


def fetch_commit_detail(
    repo: str, sha: str, session: requests.Session
) -> dict:
    """Return {"message": str, "files": list} from the commit detail API."""
    url = f"https://api.github.com/repos/{repo}/commits/{sha}"
    while True:
        resp = session.get(url)
        if resp.status_code in (403, 429):
            reset = int(resp.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait = max(reset - time.time(), 0) + 2
            print(f"    rate-limited, waiting {wait:.0f}s …", file=sys.stderr)
            time.sleep(wait)
            continue
        if resp.status_code == 404:
            return {"message": "", "files": []}   # private repo or deleted
        resp.raise_for_status()
        data = resp.json()
        return {
            "message": data.get("commit", {}).get("message", ""),
            "files": data.get("files", []),
        }


# ── Quality heuristics ────────────────────────────────────────────────────────

def commit_type(message: str) -> str | None:
    m = CC_RE.match(message.split("\n")[0])
    return m.group("type").lower() if m else None


def top_level_dirs(files: list[dict]) -> set[str]:
    dirs = set()
    for f in files:
        parts = PurePosixPath(f["filename"]).parts
        dirs.add(parts[0] if len(parts) > 1 else "<root>")
    return dirs


def analyse_commit(commit_item: dict, files: list[dict]) -> dict:
    """Return a dict of quality flags and metrics for one commit."""
    msg = commit_item.get("commit", {}).get("message", "")
    ctype = commit_type(msg)

    file_types = [classify_file(f["filename"]) for f in files]
    type_set = set(file_types) - {"generated"}
    has_test = "test" in type_set
    has_source = "source" in type_set
    has_non_code = bool(type_set & {"ci", "config", "docs", "build"})

    additions = sum(f.get("additions", 0) for f in files)
    deletions = sum(f.get("deletions", 0) for f in files)
    lines = additions + deletions
    n_files = len(files)
    top_dirs = top_level_dirs(files)

    flags = []

    # Good signals
    if ctype in ("fix", "bug") and has_test:
        flags.append("fix_with_test")
    if n_files <= 5 and len(top_dirs) <= 2:
        flags.append("focused")
    if len(type_set) == 1:
        flags.append("pure_concern")

    # Dirty signals
    if ctype in ("fix", "bug") and not has_test:
        flags.append("fix_no_test")
    if n_files > 10 and len(top_dirs) > 4:
        flags.append("scattered")
    if lines > 500:
        flags.append("huge_diff")
    if has_source and has_non_code and n_files > 5:
        flags.append("mixed_concerns")
    if ctype == "feat" and not has_test and has_source:
        flags.append("feat_no_test")

    return {
        "sha": commit_item.get("sha", "")[:7],
        "repo": commit_item.get("repository", {}).get("full_name", ""),
        "message": msg.split("\n")[0][:72],
        "type": ctype,
        "n_files": n_files,
        "lines": lines,
        "file_types": dict(Counter(file_types)),
        "top_dirs": len(top_dirs),
        "flags": flags,
    }


# ── Display helpers ───────────────────────────────────────────────────────────

def bar(count: int, total: int, max_count: int) -> str:
    filled = round(count / max_count * BAR_WIDTH) if max_count else 0
    pct = f" {count / total * 100:.0f}%" if total else " 0%"
    block = BAR_CHAR * filled
    if filled >= len(pct) + 1:
        return block[: filled - len(pct)] + pct
    return block + pct


def section(title: str) -> None:
    print(f"\n{title}")
    print("─" * len(title))


def print_flag_table(
    results: list[dict], good_flags: list[str], dirty_flags: list[str]
) -> None:
    n = len(results)
    all_flags = good_flags + dirty_flags
    counts = Counter(f for r in results for f in r["flags"] if f in all_flags)
    max_c = max(counts.values(), default=1)

    print(f"\n  {'Flag':<22} {'Bar':<{BAR_WIDTH + 6}} {'n':>5}  {'%':>5}")
    print(f"  {'─'*22} {'─'*(BAR_WIDTH + 6)} {'─'*5}  {'─'*5}")

    print("  ── good ──")
    for f in good_flags:
        c = counts.get(f, 0)
        b = bar(c, n, max_c)
        print(f"  {f:<22} {b:<{BAR_WIDTH + 6}} {c:>5}  {c/n*100:>4.0f}%")

    print("  ── dirty ──")
    for f in dirty_flags:
        c = counts.get(f, 0)
        b = bar(c, n, max_c)
        print(f"  {f:<22} {b:<{BAR_WIDTH + 6}} {c:>5}  {c/n*100:>4.0f}%")


# ── Commit cache (data/commits/{sha}.json) ───────────────────────────────────

REPO_ROOT = Path(__file__).parent.parent
COMMITS_DIR = REPO_ROOT / "data" / "commits"


def commit_cache_path(sha: str) -> Path:
    return COMMITS_DIR / f"{sha}.json"


def load_cached_detail(sha: str) -> dict | None:
    """Return {"message": str, "files": list} or None if not cached.
    Also handles the old format where the file contained only a files list.
    """
    p = commit_cache_path(sha)
    if not p.exists():
        return None
    raw = json.loads(p.read_text())
    if isinstance(raw, list):          # old format: bare files array
        return {"message": "", "files": raw}
    return raw                         # new format: {message, files}


def save_cached_detail(sha: str, detail: dict) -> None:
    COMMITS_DIR.mkdir(parents=True, exist_ok=True)
    commit_cache_path(sha).write_text(json.dumps(detail, indent=2) + "\n")


# ── Main ─────────────────────────────────────────────────────────────────────

def load_commits(path: Path) -> list[dict]:
    data = json.loads(path.read_text())
    commits = []
    for day_data in data.get("days", {}).values():
        commits.extend(day_data.get("commits", []))
    return commits


def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <data-file.json>", file=sys.stderr)
        sys.exit(1)

    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"File not found: {path}", file=sys.stderr)
        sys.exit(1)

    commits = load_commits(path)
    uncached = [c for c in commits if load_cached_detail(c.get("sha", "")) is None]

    if uncached:
        token = keyring.get_password("login2", "github_token")
        if not token:
            sys.exit("No GitHub token in keyring (service='login2', username='github_token')")
        session = build_session(token)

        print(f"Fetching details for {len(uncached)} commits …", file=sys.stderr)
        for i, commit in enumerate(uncached, 1):
            sha = commit.get("sha", "")
            repo = commit.get("repository", {}).get("full_name", "")
            detail = fetch_commit_detail(repo, sha, session)
            save_cached_detail(sha, detail)
            print(f"  [{i}/{len(uncached)}] {sha[:7]}  {repo}", file=sys.stderr)
            time.sleep(0.5)
    else:
        print(f"All {len(commits)} commits already cached in {COMMITS_DIR.relative_to(REPO_ROOT)}/", file=sys.stderr)

    # Analyse
    results = []
    for commit in commits:
        sha = commit.get("sha", "")
        detail = load_cached_detail(sha) or {"message": "", "files": []}
        results.append(analyse_commit(commit, detail["files"]))

    n = len(results)
    good_flags  = ["fix_with_test", "focused", "pure_concern"]
    dirty_flags = ["fix_no_test", "feat_no_test", "scattered", "huge_diff", "mixed_concerns"]

    # ── Report ───────────────────────────────────────────────────────────────
    print(f"\nCommit Quality Analysis — {path.stem}")
    print("=" * 55)
    print(f"  Commits analysed : {n}")

    avg_files = sum(r["n_files"] for r in results) / n if n else 0
    avg_lines = sum(r["lines"] for r in results) / n if n else 0
    print(f"  Avg files/commit : {avg_files:.1f}")
    print(f"  Avg lines/commit : {avg_lines:.1f}")

    print_flag_table(results, good_flags, dirty_flags)

    # File type breakdown
    section(f"File type breakdown  (n={n})")
    type_total: Counter = Counter()
    for r in results:
        type_total.update(r["file_types"])
    grand = sum(type_total.values())
    max_c = type_total.most_common(1)[0][1] if type_total else 1
    for ftype, count in type_total.most_common():
        b = bar(count, grand, max_c)
        print(f"  {'  '+ftype:<22} {b:<{BAR_WIDTH + 6}} {count:>5}")

    # Worst offenders
    dirty_counts = {r["sha"]: len([f for f in r["flags"] if f in dirty_flags])
                    for r in results}
    worst = sorted(results, key=lambda r: (dirty_counts[r["sha"]], r["lines"]), reverse=True)[:8]

    section("Dirtiest commits")
    for r in worst:
        if not dirty_counts[r["sha"]]:
            break
        flags_str = ", ".join(r["flags"])
        print(f"  {r['sha']}  {r['repo']}")
        print(f"    {r['message']}")
        print(f"    files={r['n_files']}  lines={r['lines']}  dirs={r['top_dirs']}  [{flags_str}]")

    # Best examples
    good_counts = {r["sha"]: len([f for f in r["flags"] if f in good_flags])
                   for r in results}
    best = sorted(results, key=lambda r: good_counts[r["sha"]], reverse=True)[:5]

    section("Cleanest commits")
    for r in best:
        if not good_counts[r["sha"]]:
            break
        flags_str = ", ".join(r["flags"])
        print(f"  {r['sha']}  {r['repo']}")
        print(f"    {r['message']}")
        print(f"    files={r['n_files']}  lines={r['lines']}  [{flags_str}]")

    print()


if __name__ == "__main__":
    main()
