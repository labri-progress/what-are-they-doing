#!/usr/bin/env python3
"""Analyze sampled GitHub commits to explain how an author is working.

Usage:
    python script/visualize-file-touches.py developers.json --author steipete
    python script/visualize-file-touches.py developers.json --author steipete --month 2025-12 --limit 40

The script resolves the developer from developers.json, reads the sampled
monthly commit file from data/{author}-{YYYY-MM}.json, fetches each commit's
detail from the GitHub API, and prints a terminal report covering:

- file categories touched
- change size and operation mix
- language and hotspot summaries
- conventional-commit consistency
- test and documentation coupling
- engineering-signal heuristics

GitHub token is retrieved from the system keyring:
  service  = "login2"
  username = "github_token"
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

import keyring
import requests
from keyring.errors import NoKeyringError
from requests import HTTPError

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = REPO_ROOT / "data"
TOKEN_SERVICE = "login2"
TOKEN_USERNAME = "github_token"

TIMELINE_MIX_WIDTH = 20
BAR_WIDTH = 28

MONTHS = {
    "jan": 1,
    "feb": 2,
    "mar": 3,
    "apr": 4,
    "may": 5,
    "jun": 6,
    "jul": 7,
    "aug": 8,
    "sep": 9,
    "oct": 10,
    "nov": 11,
    "dec": 12,
}

CATEGORY_ORDER = [
    "code",
    "tests",
    "documentation",
    "configuration",
    "ci",
    "build",
    "assets",
    "other",
]

CATEGORY_LABELS = {
    "code": "C",
    "tests": "T",
    "documentation": "D",
    "configuration": "F",
    "ci": "I",
    "build": "B",
    "assets": "A",
    "other": "O",
}

SIZE_BUCKET_ORDER = ["XS", "S", "M", "L", "XL"]
MATCH_ORDER = ["match", "weak", "mismatch", "non-conventional"]
WORK_TYPE_ORDER = [
    "feature",
    "bugfix",
    "refactor",
    "tests",
    "docs",
    "infra",
    "maintenance",
    "mixed",
]
OPERATION_ORDER = ["added", "modified", "removed", "renamed", "other"]

CC_RE = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?\s*:\s*(?P<desc>.+)",
    re.MULTILINE,
)

CODE_EXTENSIONS = {
    ".c", ".cc", ".cpp", ".cs", ".css", ".dart", ".el", ".erl", ".ex",
    ".exs", ".go", ".h", ".hpp", ".hs", ".html", ".java", ".js", ".jsx",
    ".kt", ".kts", ".lua", ".m", ".mm", ".php", ".pl", ".py", ".r", ".rb",
    ".rs", ".scala", ".sh", ".sql", ".swift", ".ts", ".tsx", ".vue", ".zig",
}
DOC_EXTENSIONS = {".md", ".mdx", ".rst", ".adoc", ".txt"}
ASSET_EXTENSIONS = {
    ".avif", ".bmp", ".gif", ".ico", ".jpeg", ".jpg", ".pdf", ".png", ".svg",
    ".tif", ".tiff", ".webp", ".woff", ".woff2", ".mp3", ".mp4", ".ogg",
}
CONFIG_EXTENSIONS = {
    ".cfg", ".cnf", ".conf", ".editorconfig", ".env", ".ini", ".json", ".lock",
    ".properties", ".toml", ".yaml", ".yml",
}

TEST_DIR_MARKERS = {
    "test", "tests", "__tests__", "spec", "specs", "e2e", "integration", "fixtures",
}
DOC_DIR_MARKERS = {"doc", "docs", "documentation"}
CI_DIR_MARKERS = {".github", ".circleci", ".buildkite", ".azure-pipelines"}
BUILD_FILENAMES = {
    "dockerfile", "makefile", "cmakelists.txt", "build.gradle", "build.gradle.kts",
    "pom.xml", "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
    "go.mod", "go.sum", "cargo.toml", "cargo.lock", "pyproject.toml",
    "requirements.txt", "requirements-dev.txt", "setup.py", "setup.cfg",
    "gradle.properties", "justfile", "mix.exs", "gemfile", "gemfile.lock",
}
CI_FILENAMES = {"jenkinsfile", ".travis.yml", "codecov.yml", "dependabot.yml"}
DOC_FILENAMES = {"readme", "changelog", "contributing", "license", "copying", "authors"}

GENERATED_DIR_MARKERS = {
    "dist", "build", "coverage", "vendor", "node_modules", "generated",
    "__snapshots__", "snapshots",
}
GENERATED_SUFFIXES = {".lock", ".min.js", ".map", ".snap"}

LANGUAGE_BY_SUFFIX = {
    ".c": "c",
    ".cc": "cpp",
    ".cpp": "cpp",
    ".cs": "csharp",
    ".css": "css",
    ".dart": "dart",
    ".go": "go",
    ".h": "c",
    ".hpp": "cpp",
    ".html": "html",
    ".java": "java",
    ".js": "javascript",
    ".jsx": "javascript",
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".lua": "lua",
    ".m": "objc",
    ".mm": "objc",
    ".php": "php",
    ".py": "python",
    ".r": "r",
    ".rb": "ruby",
    ".rs": "rust",
    ".scala": "scala",
    ".sh": "shell",
    ".sql": "sql",
    ".swift": "swift",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".toml": "toml",
    ".vue": "vue",
    ".yaml": "yaml",
    ".yml": "yaml",
    ".zig": "zig",
}


@dataclass
class CommitSample:
    sha: str
    url: str
    repo: str
    date: str
    message: str


@dataclass
class FileInsight:
    path: str
    category: str
    language: str
    operation: str
    additions: int
    deletions: int
    changes: int
    top_dir: str
    generated: bool


@dataclass
class CommitInsight:
    sample: CommitSample
    files: list[FileInsight]
    additions: int
    deletions: int
    total_changes: int
    categories: Counter[str]
    operations: Counter[str]
    languages: Counter[str]
    directories: Counter[str]
    generated_files: int
    conventional: dict | None
    consistency: str
    consistency_note: str
    work_type: str
    size_bucket: str
    focus: str
    reviewable: bool
    atomic: bool
    badges: list[str] = field(default_factory=list)
    flags: list[str] = field(default_factory=list)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze touched files and engineering signals for one developer's sampled commits."
    )
    parser.add_argument("developers_file", help="Path to developers.json")
    parser.add_argument("--author", required=True, help="GitHub handle to inspect")
    parser.add_argument(
        "--month",
        type=parse_month_arg,
        metavar="YYYY-MM",
        help="Month to inspect. Defaults to latest sampled month available for the author.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=30,
        help="Maximum sampled commits to inspect (default: 30)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.15,
        help="Delay between GitHub API requests in seconds (default: 0.15)",
    )
    return parser.parse_args()


def parse_month_arg(value: str) -> str:
    try:
        year_str, month_str = value.split("-")
        year = int(year_str)
        month = int(month_str)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"Invalid month '{value}'. Expected YYYY-MM."
        ) from exc

    if year < 2000 or not (1 <= month <= 12):
        raise argparse.ArgumentTypeError(
            f"Invalid month '{value}'. Expected YYYY-MM."
        )
    return f"{year:04d}-{month:02d}"


def load_developers(path: Path) -> list[dict]:
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"File not found: {path}")
    except json.JSONDecodeError as exc:
        sys.exit(f"Invalid JSON in {path}: {exc}")

    if not isinstance(data, list):
        sys.exit(f"Expected a JSON list in {path}")
    return data


def find_developer(handle: str, developers: list[dict]) -> dict:
    wanted = handle.casefold()
    for dev in developers:
        dev_handle = str(dev.get("handle", ""))
        if dev_handle.casefold() == wanted:
            return dev
    sys.exit(f"Developer '{handle}' not found in developers.json")


def parse_month_key(value: str) -> tuple[int, int]:
    month_name, year_str = value.split("-")
    month = MONTHS.get(month_name.casefold())
    if not month:
        raise ValueError(f"Unsupported month key: {value}")
    return int(year_str), month


def resolve_month(dev: dict, author: str, explicit_month: str | None) -> str:
    if explicit_month:
        return explicit_month

    candidates: list[tuple[int, int, str]] = []
    for month_key, count in dev.get("commits_per_month", {}).items():
        if not count:
            continue
        try:
            year, month = parse_month_key(month_key)
        except ValueError:
            continue
        month_str = f"{year}-{month:02d}"
        if (DATA_DIR / f"{author}-{month_str}.json").is_file():
            candidates.append((year, month, month_str))

    if not candidates:
        sys.exit(
            "No sampled data file found for this developer. "
            "Run script/collect-commits-per-day.py first or pass --month."
        )

    candidates.sort()
    return candidates[-1][2]


def load_sample_commits(path: Path) -> list[CommitSample]:
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"Sample data file not found: {path}")
    except json.JSONDecodeError as exc:
        sys.exit(f"Invalid JSON in {path}: {exc}")

    commits: list[CommitSample] = []
    for day_data in data.get("days", {}).values():
        for item in day_data.get("commits", []):
            commits.append(
                CommitSample(
                    sha=item.get("sha", ""),
                    url=item.get("url", ""),
                    repo=item.get("repository", {}).get("full_name", "unknown/unknown"),
                    date=item.get("commit", {}).get("author", {}).get("date", ""),
                    message=item.get("commit", {}).get("message", "").splitlines()[0].strip(),
                )
            )

    commits.sort(key=lambda item: item.date, reverse=True)
    return [item for item in commits if item.url and item.sha]


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


def build_unauthenticated_session() -> requests.Session:
    session = requests.Session()
    session.headers.update(
        {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
    )
    return session


def get_with_retry(session: requests.Session, url: str) -> dict:
    while True:
        response = session.get(url, timeout=30)

        if response.status_code in (403, 429):
            reset = int(response.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait = max(reset - time.time(), 0) + 2
            print(f"Rate limited by GitHub API. Waiting {wait:.0f}s ...", file=sys.stderr)
            time.sleep(wait)
            continue

        response.raise_for_status()
        return response.json()


def classify_path(path_str: str) -> str:
    path = Path(path_str)
    name = path.name.casefold()
    suffix = path.suffix.casefold()
    parts = {part.casefold() for part in path.parts}

    if parts & DOC_DIR_MARKERS or any(name == item or name.startswith(f"{item}.") for item in DOC_FILENAMES):
        return "documentation"
    if parts & TEST_DIR_MARKERS or name.endswith(
        ("_test.py", "_spec.rb", ".spec.ts", ".spec.js", ".test.ts", ".test.js")
    ):
        return "tests"
    if ".github" in parts and "workflows" in parts:
        return "ci"
    if parts & CI_DIR_MARKERS or name in CI_FILENAMES:
        return "ci"
    if name in BUILD_FILENAMES:
        return "build"
    if suffix in DOC_EXTENSIONS and any(token in name for token in DOC_FILENAMES):
        return "documentation"
    if suffix in ASSET_EXTENSIONS:
        return "assets"
    if suffix in CONFIG_EXTENSIONS or name in {".gitignore", ".gitattributes", ".npmrc", ".prettierrc"}:
        return "configuration"
    if suffix in CODE_EXTENSIONS:
        return "code"
    return "other"


def classify_language(path_str: str) -> str:
    path = Path(path_str)
    name = path.name.casefold()
    suffix = path.suffix.casefold()

    if name == "dockerfile":
        return "docker"
    if name in {"makefile", "justfile"}:
        return "build-script"
    return LANGUAGE_BY_SUFFIX.get(suffix, "unknown")


def normalize_operation(status: str) -> str:
    status = (status or "").casefold()
    if status == "added":
        return "added"
    if status == "modified":
        return "modified"
    if status == "removed":
        return "removed"
    if status == "renamed":
        return "renamed"
    return "other"


def is_generated_path(path_str: str) -> bool:
    path = Path(path_str)
    name = path.name.casefold()
    parts = {part.casefold() for part in path.parts}

    if parts & GENERATED_DIR_MARKERS:
        return True
    if name.endswith(tuple(GENERATED_SUFFIXES)):
        return True
    if name in {"package-lock.json", "pnpm-lock.yaml", "yarn.lock", "cargo.lock"}:
        return True
    if ".generated." in name or name.endswith("_pb2.py"):
        return True
    return False


def top_directory(path_str: str) -> str:
    parts = [part for part in Path(path_str).parts if part not in {"", "."}]
    if not parts:
        return "(root)"
    return parts[0]


def parse_conventional(message: str) -> dict | None:
    match = CC_RE.match(message or "")
    if not match:
        return None
    return {
        "type": match.group("type").lower(),
        "scope": match.group("scope"),
        "breaking": match.group("breaking") == "!",
        "desc": match.group("desc").strip(),
    }


def size_bucket(total_changes: int) -> str:
    if total_changes <= 10:
        return "XS"
    if total_changes <= 50:
        return "S"
    if total_changes <= 200:
        return "M"
    if total_changes <= 500:
        return "L"
    return "XL"


def render_mix(counter: Counter[str], width: int = TIMELINE_MIX_WIDTH) -> str:
    total = sum(counter.values())
    if not total:
        return "." * width

    remaining = width
    pieces: list[str] = []
    non_zero = [category for category in CATEGORY_ORDER if counter.get(category, 0) > 0]

    for index, category in enumerate(non_zero):
        count = counter[category]
        if index == len(non_zero) - 1:
            chunk = remaining
        else:
            chunk = max(1, round(count / total * width))
            chunk = min(chunk, remaining - (len(non_zero) - index - 1))
        pieces.append(CATEGORY_LABELS[category] * chunk)
        remaining -= chunk

    return "".join(pieces).ljust(width, ".")


def render_bar(count: int, max_count: int, width: int = BAR_WIDTH) -> str:
    if max_count <= 0:
        return ""
    return "#" * max(1, round(count / max_count * width)) if count else ""


def short_subject(message: str, limit: int = 68) -> str:
    if len(message) <= limit:
        return message
    return message[: limit - 3] + "..."


def short_repo(repo: str, limit: int = 18) -> str:
    if len(repo) <= limit:
        return repo
    owner, _, name = repo.partition("/")
    short = name if name else repo
    return short[:limit]


def category_breakdown(counter: Counter[str]) -> str:
    parts = []
    for category in CATEGORY_ORDER:
        count = counter.get(category, 0)
        if count:
            parts.append(f"{CATEGORY_LABELS[category]}:{count}")
    return " ".join(parts) if parts else "-"


def dominant_categories(counter: Counter[str]) -> list[str]:
    return [category for category in CATEGORY_ORDER if counter.get(category, 0) > 0]


def determine_focus(file_count: int, dir_count: int, category_count: int, total_changes: int) -> str:
    score = 0
    if file_count <= 10:
        score += 1
    if dir_count <= 2:
        score += 1
    if category_count <= 3:
        score += 1
    if total_changes <= 200:
        score += 1

    if score >= 4:
        return "focused"
    if score >= 2:
        return "balanced"
    return "scattered"


def determine_work_type(conventional: dict | None, categories: Counter[str]) -> str:
    commit_type = conventional["type"] if conventional else None
    if commit_type == "feat":
        return "feature"
    if commit_type == "fix":
        return "bugfix"
    if commit_type == "refactor":
        return "refactor"
    if commit_type == "test":
        return "tests"
    if commit_type == "docs":
        return "docs"
    if commit_type in {"ci", "build"}:
        return "infra"
    if commit_type in {"chore", "style", "revert", "wip"}:
        return "maintenance"

    has_code = categories.get("code", 0) > 0
    has_tests = categories.get("tests", 0) > 0
    has_docs = categories.get("documentation", 0) > 0
    has_infra = sum(categories.get(key, 0) for key in ("ci", "build", "configuration")) > 0

    if has_docs and not has_code and not has_tests and not has_infra:
        return "docs"
    if has_tests and not has_code and not has_docs:
        return "tests"
    if has_infra and not has_code and not has_tests:
        return "infra"
    if has_code and has_tests:
        return "feature"
    if has_code:
        return "maintenance"
    return "mixed"


def evaluate_consistency(conventional: dict | None, categories: Counter[str]) -> tuple[str, str]:
    if conventional is None:
        return "non-conventional", "message does not follow conventional-commit syntax"

    commit_type = conventional["type"]
    has_code = categories.get("code", 0) > 0
    has_tests = categories.get("tests", 0) > 0
    has_docs = categories.get("documentation", 0) > 0
    has_ci = categories.get("ci", 0) > 0
    has_build = categories.get("build", 0) > 0
    has_config = categories.get("configuration", 0) > 0
    has_assets = categories.get("assets", 0) > 0
    codeish = has_code or has_tests
    infraish = has_ci or has_build or has_config

    if commit_type == "docs":
        if has_docs and not has_code:
            return "match", "docs commit mostly changes documentation"
        if has_docs:
            return "weak", "docs commit also changes non-documentation files"
        return "mismatch", "docs commit did not touch documentation files"

    if commit_type == "test":
        if has_tests:
            return "match", "test commit touched test files"
        return "mismatch", "test commit did not touch test files"

    if commit_type == "ci":
        if has_ci:
            return "match", "ci commit touched workflow or CI files"
        if infraish:
            return "weak", "ci commit touched infra files but no CI workflow files"
        return "mismatch", "ci commit did not touch infrastructure files"

    if commit_type == "build":
        if has_build:
            return "match", "build commit touched build files"
        if infraish:
            return "weak", "build commit touched configuration but not build files"
        return "mismatch", "build commit did not touch build or config files"

    if commit_type == "feat":
        if has_code or has_assets:
            return "match", "feature commit includes product code or assets"
        if has_docs or infraish:
            return "weak", "feature commit lacks product code but changes supporting files"
        return "mismatch", "feature commit did not touch product code"

    if commit_type == "fix":
        if codeish or infraish:
            return "match", "fix commit touched code, tests, or infrastructure"
        if has_docs:
            return "weak", "fix commit only changed documentation"
        return "mismatch", "fix commit did not touch code, tests, or infrastructure"

    if commit_type in {"refactor", "style", "perf"}:
        if has_code:
            return "match", f"{commit_type} commit touched code"
        if has_tests or has_docs:
            return "weak", f"{commit_type} commit did not touch code directly"
        return "mismatch", f"{commit_type} commit did not touch code files"

    if commit_type == "chore":
        if infraish or has_docs or not has_code:
            return "match", "chore commit looks like maintenance work"
        return "weak", "chore commit includes substantive code work"

    if commit_type in {"revert", "wip"}:
        return "weak", f"{commit_type} commits are hard to validate from file categories"

    if codeish or has_docs or infraish:
        return "weak", "non-standard conventional type with plausible file mix"
    return "mismatch", "commit type does not align with touched files"


def describe_dirs(counter: Counter[str], limit: int = 3) -> str:
    if not counter:
        return "-"
    parts = [name for name, _ in counter.most_common(limit)]
    return ",".join(parts)


def format_operations(counter: Counter[str]) -> str:
    parts = []
    for key in OPERATION_ORDER:
        count = counter.get(key, 0)
        if count:
            parts.append(f"{key[:3]}:{count}")
    return " ".join(parts) if parts else "-"


def fetch_commit_detail(session: requests.Session, url: str, delay: float) -> dict:
    data = get_with_retry(session, url)
    time.sleep(delay)
    return data


def analyze_commit(sample: CommitSample, detail: dict) -> CommitInsight:
    file_items = detail.get("files", [])
    files: list[FileInsight] = []
    categories: Counter[str] = Counter()
    operations: Counter[str] = Counter()
    languages: Counter[str] = Counter()
    directories: Counter[str] = Counter()
    generated_files = 0

    for item in file_items:
        path = item.get("filename", "")
        if not path:
            continue
        category = classify_path(path)
        language = classify_language(path)
        operation = normalize_operation(item.get("status", ""))
        additions = int(item.get("additions", 0))
        deletions = int(item.get("deletions", 0))
        changes = int(item.get("changes", additions + deletions))
        top_dir_name = top_directory(path)
        generated = is_generated_path(path)

        insight = FileInsight(
            path=path,
            category=category,
            language=language,
            operation=operation,
            additions=additions,
            deletions=deletions,
            changes=changes,
            top_dir=top_dir_name,
            generated=generated,
        )
        files.append(insight)

        categories[category] += 1
        operations[operation] += 1
        languages[language] += 1
        directories[top_dir_name] += 1
        if generated:
            generated_files += 1

    stats = detail.get("stats", {})
    additions = int(stats.get("additions", 0))
    deletions = int(stats.get("deletions", 0))
    total_changes = int(stats.get("total", additions + deletions))

    conventional = parse_conventional(sample.message)
    consistency, consistency_note = evaluate_consistency(conventional, categories)
    work_type = determine_work_type(conventional, categories)
    bucket = size_bucket(total_changes)

    category_count = len(dominant_categories(categories))
    dir_count = len(directories)
    file_count = len(files)
    focus = determine_focus(file_count, dir_count, category_count, total_changes)
    reviewable = file_count <= 10 and total_changes <= 200
    atomic = focus == "focused" and consistency != "mismatch"

    has_code = categories.get("code", 0) > 0
    has_tests = categories.get("tests", 0) > 0
    has_docs = categories.get("documentation", 0) > 0
    generated_heavy = file_count > 0 and generated_files / file_count >= 0.5

    badges = [bucket, focus, consistency]
    if has_code and has_tests:
        badges.append("tested")
    if has_docs:
        badges.append("docs")
    if reviewable:
        badges.append("reviewable")
    if atomic:
        badges.append("atomic")

    flags: list[str] = []
    if total_changes > 500 and has_code and not has_tests:
        flags.append("large code change without tests")
    if conventional and conventional["type"] in {"fix", "feat", "refactor"} and has_code and not has_tests and total_changes > 50:
        flags.append(f"{conventional['type']} commit changed code without tests")
    if consistency == "mismatch":
        flags.append(consistency_note)
    if focus == "scattered":
        flags.append("commit spans many files or directories")
    if generated_heavy:
        flags.append("generated-heavy change")

    return CommitInsight(
        sample=sample,
        files=files,
        additions=additions,
        deletions=deletions,
        total_changes=total_changes,
        categories=categories,
        operations=operations,
        languages=languages,
        directories=directories,
        generated_files=generated_files,
        conventional=conventional,
        consistency=consistency,
        consistency_note=consistency_note,
        work_type=work_type,
        size_bucket=bucket,
        focus=focus,
        reviewable=reviewable,
        atomic=atomic,
        badges=badges,
        flags=flags,
    )


def section(title: str) -> None:
    print()
    print(title)
    print("-" * len(title))


def print_counter_table(
    title: str,
    counter: Counter[str],
    total: int | None = None,
    top: int = 10,
    width: int = BAR_WIDTH,
) -> None:
    section(title)
    if not counter:
        print("  (none)")
        return

    total = total if total is not None else sum(counter.values())
    max_count = max(counter.values(), default=0)
    for label, count in counter.most_common(top):
        pct = (count / total * 100) if total else 0
        print(f"  {label:<18} {render_bar(count, max_count, width):<{width}}  {count:>4}  {pct:>5.1f}%")


def print_summary_line(label: str, numerator: int, denominator: int) -> None:
    pct = (numerator / denominator * 100) if denominator else 0
    print(f"  {label:<28} {numerator:>3}/{denominator:<3}  {pct:>5.1f}%")


def summarize_observations(insights: list[CommitInsight], consistency_counts: Counter[str]) -> list[str]:
    total = len(insights)
    if total == 0:
        return ["No commit details were available for analysis."]

    code_commits = [item for item in insights if item.categories.get("code", 0) > 0]
    tested_code = [item for item in code_commits if item.categories.get("tests", 0) > 0]
    focused = [item for item in insights if item.focus == "focused"]
    large = [item for item in insights if item.size_bucket in {"L", "XL"}]
    mismatched = consistency_counts["mismatch"]

    observations: list[str] = []

    if code_commits:
        ratio = len(tested_code) / len(code_commits)
        if ratio >= 0.5:
            observations.append("Code changes are often coupled with tests.")
        elif ratio >= 0.25:
            observations.append("Some code changes include tests, but the coupling is inconsistent.")
        else:
            observations.append("Most code changes land without accompanying tests.")

    focus_ratio = len(focused) / total
    if focus_ratio >= 0.6:
        observations.append("The sampled commits are mostly focused and easy to review.")
    elif focus_ratio >= 0.35:
        observations.append("The author alternates between focused and broader commits.")
    else:
        observations.append("Many commits are broad, which makes intent and reviewability weaker.")

    large_ratio = len(large) / total
    if large_ratio >= 0.35:
        observations.append("Large changes are common, which raises review and regression risk.")
    elif large_ratio <= 0.15:
        observations.append("Most commits are kept to reviewable sizes.")

    mismatch_ratio = mismatched / total
    if mismatch_ratio >= 0.25:
        observations.append("Commit messages often fail to describe the changed files accurately.")
    elif mismatch_ratio <= 0.1:
        observations.append("Commit messages usually align with the actual file changes.")

    return observations[:4]


def main() -> None:
    args = parse_args()
    developers_path = Path(args.developers_file)
    developers = load_developers(developers_path)
    dev = find_developer(args.author, developers)
    author = dev["handle"]
    month = resolve_month(dev, author, args.month)
    sample_path = DATA_DIR / f"{author}-{month}.json"

    commits = load_sample_commits(sample_path)
    if not commits:
        print("No sampled commits found.")
        sys.exit(0)

    selected = commits[: max(args.limit, 1)]

    try:
        token = keyring.get_password(TOKEN_SERVICE, TOKEN_USERNAME)
    except NoKeyringError:
        token = None
    session = build_session(token) if token else build_unauthenticated_session()
    using_auth = bool(token)
    warned_auth_fallback = False

    insights: list[CommitInsight] = []
    skipped = 0

    for commit in selected:
        try:
            detail = fetch_commit_detail(session, commit.url, args.delay)
        except HTTPError as exc:
            response = exc.response
            if using_auth and response is not None and response.status_code == 401:
                if not warned_auth_fallback:
                    print(
                        "Stored GitHub token was rejected with 401 Unauthorized. "
                        "Retrying without authentication for public repositories.",
                        file=sys.stderr,
                    )
                    warned_auth_fallback = True
                session = build_unauthenticated_session()
                using_auth = False
                try:
                    detail = fetch_commit_detail(session, commit.url, args.delay)
                except requests.RequestException as retry_exc:
                    skipped += 1
                    print(
                        f"Skipping {commit.sha[:8]} from {commit.repo}: {retry_exc}",
                        file=sys.stderr,
                    )
                    continue
            else:
                skipped += 1
                print(
                    f"Skipping {commit.sha[:8]} from {commit.repo}: {exc}",
                    file=sys.stderr,
                )
                continue
        except requests.RequestException as exc:
            skipped += 1
            print(
                f"Skipping {commit.sha[:8]} from {commit.repo}: {exc}",
                file=sys.stderr,
            )
            continue

        insights.append(analyze_commit(commit, detail))

    if not insights:
        sys.exit("No commit details could be analyzed.")

    overall_categories: Counter[str] = Counter()
    overall_operations: Counter[str] = Counter()
    overall_languages: Counter[str] = Counter()
    work_types: Counter[str] = Counter()
    size_buckets: Counter[str] = Counter()
    consistency_counts: Counter[str] = Counter()
    repo_counts: Counter[str] = Counter()
    top_dirs: Counter[str] = Counter()
    top_files: Counter[str] = Counter()
    repeated_files: Counter[str] = Counter()
    flag_counts: Counter[str] = Counter()

    focused_commits = 0
    reviewable_commits = 0
    atomic_commits = 0
    generated_heavy_commits = 0
    code_commits = 0
    code_with_tests = 0
    code_without_tests = 0
    fix_commits = 0
    fix_with_tests = 0
    feature_commits = 0
    feature_with_docs = 0
    repo_switches = 0
    total_additions = 0
    total_deletions = 0

    previous_repo: str | None = None
    flagged_commits: list[CommitInsight] = []

    for insight in insights:
        overall_categories.update(insight.categories)
        overall_operations.update(insight.operations)
        overall_languages.update(insight.languages)
        work_types[insight.work_type] += 1
        size_buckets[insight.size_bucket] += 1
        consistency_counts[insight.consistency] += 1
        repo_counts[insight.sample.repo] += 1
        total_additions += insight.additions
        total_deletions += insight.deletions

        if previous_repo is not None and previous_repo != insight.sample.repo:
            repo_switches += 1
        previous_repo = insight.sample.repo

        if insight.focus == "focused":
            focused_commits += 1
        if insight.reviewable:
            reviewable_commits += 1
        if insight.atomic:
            atomic_commits += 1
        if insight.files and insight.generated_files / len(insight.files) >= 0.5:
            generated_heavy_commits += 1

        if insight.categories.get("code", 0) > 0:
            code_commits += 1
            if insight.categories.get("tests", 0) > 0:
                code_with_tests += 1
            else:
                code_without_tests += 1

        if insight.conventional and insight.conventional["type"] == "fix":
            fix_commits += 1
            if insight.categories.get("tests", 0) > 0:
                fix_with_tests += 1

        if insight.conventional and insight.conventional["type"] == "feat":
            feature_commits += 1
            if insight.categories.get("documentation", 0) > 0:
                feature_with_docs += 1

        for dir_name, count in insight.directories.items():
            top_dirs[f"{short_repo(insight.sample.repo, 12)}:{dir_name}"] += count
        for file_item in insight.files:
            key = f"{short_repo(insight.sample.repo, 12)}:{file_item.path}"
            top_files[key] += file_item.changes or 1
            repeated_files[key] += 1

        if insight.flags:
            flagged_commits.append(insight)
            for flag in insight.flags:
                flag_counts[flag] += 1

    conventional_commits = sum(
        1 for insight in insights if insight.conventional is not None
    )

    print(f"Commit work analysis for @{author} ({month})")
    print(f"Sample source: {sample_path.relative_to(REPO_ROOT)}")
    print(f"Commits analyzed: {len(insights)} of {len(selected)} requested sampled commits")
    print(f"GitHub API mode: {'authenticated' if using_auth else 'unauthenticated'}")
    print("Note: this report describes the sampled monthly data, not every commit by the author.")
    if skipped:
        print(f"Skipped commits: {skipped}")
    print("Legend: C=code T=tests D=documentation F=configuration I=ci B=build A=assets O=other")

    section("Readout")
    for line in summarize_observations(insights, consistency_counts):
        print(f"  - {line}")

    section("Timeline")
    for index, insight in enumerate(insights, start=1):
        conventional_type = insight.conventional["type"] if insight.conventional else "n/a"
        repo_label = short_repo(insight.sample.repo)
        date_str = insight.sample.date[:10] if insight.sample.date else "unknown-date"
        mix = render_mix(insight.categories)
        subject = short_subject(insight.sample.message or "(no subject)")
        print(
            f"{index:>3}. {date_str}  {insight.sample.sha[:8]}  {repo_label:<18}  "
            f"{conventional_type:<8} {insight.size_bucket:<2}  "
            f"{len(insight.files):>3}f  +{insight.additions:<4}/-{insight.deletions:<4}  "
            f"{insight.focus:<9} {insight.consistency:<16}"
        )
        print(
            f"     work={insight.work_type:<11} dirs={describe_dirs(insight.directories):<24} "
            f"ops={format_operations(insight.operations):<26} [{mix}]"
        )
        print(f"     {category_breakdown(insight.categories)}  |  {subject}")

    section("Engineering Signals")
    print_summary_line("Conventional commits", conventional_commits, len(insights))
    print_summary_line(
        "Message/file match",
        consistency_counts["match"],
        len(insights),
    )
    print_summary_line(
        "Message/file weak-or-better",
        consistency_counts["match"] + consistency_counts["weak"],
        len(insights),
    )
    print_summary_line("Focused commits", focused_commits, len(insights))
    print_summary_line("Reviewable commits", reviewable_commits, len(insights))
    print_summary_line("Atomic commits", atomic_commits, len(insights))
    print_summary_line("Large commits (L/XL)", size_buckets["L"] + size_buckets["XL"], len(insights))
    print_summary_line("Generated-heavy commits", generated_heavy_commits, len(insights))
    print_summary_line("Code commits with tests", code_with_tests, code_commits)
    print_summary_line("Fix commits with tests", fix_with_tests, fix_commits)
    print_summary_line("Feature commits with docs", feature_with_docs, feature_commits)
    print_summary_line("Code commits without tests", code_without_tests, code_commits)
    print(f"  {'Repo switches between commits':<28} {repo_switches:>3}")
    avg_files = sum(len(item.files) for item in insights) / len(insights)
    avg_changes = sum(item.total_changes for item in insights) / len(insights)
    print(f"  {'Average files per commit':<28} {avg_files:>5.1f}")
    print(f"  {'Average changed lines':<28} {avg_changes:>5.1f}")
    print(f"  {'Net lines added/removed':<28} +{total_additions}/-{total_deletions}")

    print_counter_table(
        "Commit Size Distribution",
        Counter({bucket: size_buckets.get(bucket, 0) for bucket in SIZE_BUCKET_ORDER}),
        total=len(insights),
        top=len(SIZE_BUCKET_ORDER),
    )
    print_counter_table(
        "Work Types",
        Counter({work: work_types.get(work, 0) for work in WORK_TYPE_ORDER}),
        total=len(insights),
        top=len(WORK_TYPE_ORDER),
    )
    print_counter_table(
        "Conventional Consistency",
        Counter({label: consistency_counts.get(label, 0) for label in MATCH_ORDER}),
        total=len(insights),
        top=len(MATCH_ORDER),
    )
    print_counter_table(
        "File Categories Touched",
        Counter({category: overall_categories.get(category, 0) for category in CATEGORY_ORDER}),
        total=sum(overall_categories.values()),
        top=len(CATEGORY_ORDER),
    )
    print_counter_table(
        "Operation Mix",
        Counter({name: overall_operations.get(name, 0) for name in OPERATION_ORDER}),
        total=sum(overall_operations.values()),
        top=len(OPERATION_ORDER),
    )
    print_counter_table(
        "Languages Touched",
        overall_languages,
        total=sum(overall_languages.values()),
        top=12,
    )
    print_counter_table(
        "Repositories",
        repo_counts,
        total=len(insights),
        top=10,
    )
    print_counter_table(
        "Hot Directories",
        top_dirs,
        total=sum(top_dirs.values()),
        top=12,
    )
    print_counter_table(
        "Hot Files By Churn",
        top_files,
        total=sum(top_files.values()),
        top=12,
    )

    repeated_subset = Counter({name: count for name, count in repeated_files.items() if count >= 2})
    print_counter_table(
        "Repeatedly Touched Files",
        repeated_subset,
        total=sum(repeated_subset.values()),
        top=12,
    )

    if flag_counts:
        print_counter_table(
            "Quality Flags",
            flag_counts,
            total=sum(flag_counts.values()),
            top=10,
        )

    if flagged_commits:
        section("Flagged Commits")
        for insight in flagged_commits[:10]:
            reasons = "; ".join(insight.flags)
            print(
                f"  {insight.sample.date[:10]}  {insight.sample.sha[:8]}  "
                f"{short_repo(insight.sample.repo):<18}  {reasons}"
            )


if __name__ == "__main__":
    main()
