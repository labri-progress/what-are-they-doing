#!/usr/bin/env python3
"""Analyze how a developer switches between repositories over time.

Usage:
    python script/analyze-repo-switching.py developers.json --author steipete
    python script/analyze-repo-switching.py developers.json --author steipete --month 2025-12
    python script/analyze-repo-switching.py developers.json --author steipete --month 2025-12 --refresh

The script resolves the developer from developers.json, loads
data/{author}-{YYYY-MM}.json when present, or collects a fresh sampled month of
commits from the GitHub commit search API. It then treats the commits as a
time-ordered repository stream and prints terminal visualizations covering:

- repository concentration
- consecutive repository switches
- contiguous runs on the same repository
- bounce-back behavior (A -> B -> A)
- day-by-day timeline stripes
- most common repository hand-offs

Important: this is sample-based analysis. When a day has more than the search
API can return, the collected stream is only a slice of the month and should be
read as directional rather than exhaustive.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
from calendar import monthrange
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from statistics import median
from typing import Any
import csv
import os
import re

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = REPO_ROOT / "data"
TOKEN_SERVICE = "login2"
TOKEN_USERNAME = "github_token"

PER_PAGE = 100
MAX_SEARCH_RESULTS = 1000
DEFAULT_DAY_PAGES = 3
BAR_WIDTH = 32
TIMELINE_WIDTH = 56
LEGEND_SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

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


@dataclass(frozen=True)
class CommitRecord:
    sha: str
    repo: str
    committed_at: datetime
    day: str
    message: str
    url: str


@dataclass(frozen=True)
class RepoRun:
    repo: str
    start_at: datetime
    end_at: datetime
    start_day: str
    end_day: str
    commits: int
    duration_minutes: int


@dataclass(frozen=True)
class DaySummary:
    day: str
    commits: int
    unique_repos: int
    switches: int
    sequence: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze how a developer distributes work across repositories."
    )
    parser.add_argument("developers_file", help="Path to developers.json")
    parser.add_argument("--author", required=False, help="GitHub handle to inspect")
    parser.add_argument(
        "--month",
        type=parse_month_arg,
        metavar="YYYY-MM",
        help="Month to inspect. Defaults to the latest local sample for the author, otherwise the latest non-zero month in developers.json.",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="Fetch a fresh sampled month from GitHub even if a local data file exists.",
    )
    parser.add_argument(
        "--day-pages",
        type=int,
        default=DEFAULT_DAY_PAGES,
        help="When fetching, sample up to this many pages per day (default: 3).",
    )
    parser.add_argument(
        "--top-repos",
        type=int,
        default=10,
        help="How many repositories to show in the distribution chart (default: 10).",
    )
    parser.add_argument(
        "--timeline-width",
        type=int,
        default=TIMELINE_WIDTH,
        help="Width of the per-day timeline stripes (default: 56).",
    )
    parser.add_argument(
        "--export-all",
        action="store_true",
        help="Process all local monthly sample JSON files in data/ and export aggregated CSVs to data/exports-RQ4.",
    )
    parser.add_argument(
        "--export-dir",
        type=str,
        default=str(DATA_DIR / "exports-RQ4"),
        help="Directory to write CSV exports when using --export-all (default: data/exports-RQ4)",
    )
    return parser.parse_args()


def parse_month_arg(value: str) -> tuple[int, int]:
    try:
        year_str, month_str = value.split("-")
        year = int(year_str)
        month = int(month_str)
        if year < 2008 or not 1 <= month <= 12:
            raise ValueError
        return year, month
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError(
            f"Invalid month '{value}'. Expected YYYY-MM, for example 2025-12."
        )


def parse_github_datetime(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def load_developers(path: Path) -> list[dict[str, Any]]:
    text = path.read_text()
    text = re.sub(r"\}\s*\{", "},{", text)
    return json.loads(text)


def resolve_developer(
    developers: list[dict[str, Any]], author: str
) -> dict[str, Any] | None:
    author_folded = author.casefold()
    for developer in developers:
        handle = str(developer.get("handle", ""))
        if handle.casefold() == author_folded:
            return developer
    return None


def parse_developer_month_key(key: str) -> tuple[int, int]:
    month_abbr, year_str = key.split("-")
    return int(year_str), MONTHS[month_abbr]


def latest_nonzero_month(developer: dict[str, Any]) -> tuple[int, int]:
    commits_per_month = developer.get("commits_per_month", {})
    available = []
    for key, count in commits_per_month.items():
        if isinstance(count, int) and count > 0:
            available.append(parse_developer_month_key(key))
    if not available:
        raise SystemExit("No non-zero month found for this developer in developers.json.")
    return max(available)


def month_string(year: int, month: int) -> str:
    return f"{year}-{month:02d}"


def sample_path(handle: str, year: int, month: int) -> Path:
    return DATA_DIR / f"{handle}-{month_string(year, month)}.json"


def latest_local_sample_month(handle: str) -> tuple[int, int] | None:
    pattern = f"{handle}-*.json"
    available = []
    for path in DATA_DIR.glob(pattern):
        suffix = path.stem.removeprefix(f"{handle}-")
        try:
            available.append(parse_month_arg(suffix))
        except argparse.ArgumentTypeError:
            continue
    return max(available) if available else None


def build_session():
    try:
        import keyring
        import requests
    except ImportError as exc:
        raise SystemExit(
            "Fetching requires the 'keyring' and 'requests' packages to be installed."
        ) from exc

    token = keyring.get_password(TOKEN_SERVICE, TOKEN_USERNAME)
    if not token:
        raise SystemExit(
            "No GitHub token found in keyring "
            f"(service='{TOKEN_SERVICE}', username='{TOKEN_USERNAME}')."
        )

    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github.cloak-preview+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
    )
    return session


def get_with_retry(session, url: str, params: dict[str, Any]) -> dict[str, Any]:
    while True:
        response = session.get(url, params=params)

        if response.status_code in (403, 429):
            reset = int(response.headers.get("X-RateLimit-Reset", time.time() + 60))
            wait_seconds = max(reset - time.time(), 0) + 2
            print(f"Rate limited. Waiting {wait_seconds:.0f}s...", file=sys.stderr)
            time.sleep(wait_seconds)
            continue

        if response.status_code == 422:
            return {"total_count": 0, "items": [], "_validation_error": response.text}

        response.raise_for_status()
        return response.json()


def choose_pages(total_count: int, day_pages: int) -> list[int]:
    reachable = min(total_count, MAX_SEARCH_RESULTS)
    max_page = max(1, math.ceil(reachable / PER_PAGE))
    if max_page <= day_pages:
        return list(range(1, max_page + 1))

    if day_pages <= 1:
        return [1]

    pages = set()
    for step in range(day_pages):
        fraction = step / (day_pages - 1)
        pages.add(1 + round((max_page - 1) * fraction))
    return sorted(pages)


def fetch_day(handle: str, day: date, session, day_pages: int) -> dict[str, Any]:
    date_str = day.isoformat()
    url = "https://api.github.com/search/commits"
    query = f"author:{handle} committer-date:{date_str}..{date_str}"

    params = {
        "q": query,
        "per_page": PER_PAGE,
        "page": 1,
        "sort": "committer-date",
        "order": "asc",
    }
    data = get_with_retry(session, url, params)

    if data.get("_validation_error"):
        params.pop("sort", None)
        params.pop("order", None)
        data = get_with_retry(session, url, params)

    total_count = int(data.get("total_count", 0) or 0)
    if total_count == 0:
        return {"total_count": 0, "pages": [1], "commits": []}

    pages = choose_pages(total_count, max(day_pages, 1))
    commits_by_sha = {}

    for page in pages:
        page_params = dict(params)
        page_params["page"] = page
        page_data = get_with_retry(session, url, page_params)
        for commit in page_data.get("items", []):
            sha = commit.get("sha")
            if sha:
                commits_by_sha[sha] = commit

    commits = list(commits_by_sha.values())
    commits.sort(
        key=lambda item: (
            item.get("commit", {}).get("committer", {}).get("date", ""),
            item.get("sha", ""),
        )
    )
    return {"total_count": total_count, "pages": pages, "commits": commits}


def collect_month_sample(
    handle: str, year: int, month: int, out_file: Path, day_pages: int
) -> dict[str, Any]:
    session = build_session()
    days_in_month = monthrange(year, month)[1]
    month_str = month_string(year, month)
    result = {
        "developer": handle,
        "month": month_str,
        "sampling": {
            "per_page": PER_PAGE,
            "day_pages": day_pages,
            "page_strategy": "evenly spaced pages per day, then sorted by committer date",
        },
        "days": {},
    }

    print(f"Collecting sampled commits for @{handle} {month_str}...", file=sys.stderr)
    for day_num in range(1, days_in_month + 1):
        current_day = date(year, month, day_num)
        fetched = fetch_day(handle, current_day, session, day_pages)
        day_str = current_day.isoformat()
        result["days"][day_str] = {
            "total_count": fetched["total_count"],
            "sampled": len(fetched["commits"]),
            "pages": fetched["pages"],
            "commits": fetched["commits"],
        }

        indicator = ""
        if fetched["total_count"] > len(fetched["commits"]):
            indicator = f" (sampled {len(fetched['commits'])} of {fetched['total_count']})"
        print(f"  {day_str}: {len(fetched['commits'])} commits{indicator}", file=sys.stderr)
        time.sleep(2)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    out_file.write_text(json.dumps(result, indent=2) + "\n")
    return result


def load_or_collect_sample(
    handle: str, year: int, month: int, refresh: bool, day_pages: int
) -> tuple[dict[str, Any], Path]:
    path = sample_path(handle, year, month)
    if path.exists() and not refresh:
        return json.loads(path.read_text()), path
    return collect_month_sample(handle, year, month, path, day_pages), path


def extract_commits(sample: dict[str, Any]) -> tuple[list[CommitRecord], dict[str, dict[str, int]]]:
    commits: list[CommitRecord] = []
    day_totals: dict[str, dict[str, int]] = {}

    for day, day_data in sorted(sample.get("days", {}).items()):
        day_commits = day_data.get("commits", [])
        day_totals[day] = {
            "sampled": len(day_commits),
            "total_count": int(day_data.get("total_count", len(day_commits)) or 0),
        }
        for item in day_commits:
            commit_meta = item.get("commit", {})
            repo_meta = item.get("repository", {})
            committed_at = (
                commit_meta.get("committer", {}).get("date")
                or commit_meta.get("author", {}).get("date")
                or f"{day}T00:00:00Z"
            )
            commits.append(
                CommitRecord(
                    sha=item.get("sha", ""),
                    repo=(repo_meta.get("full_name") or repo_meta.get("name") or None)
                    if isinstance(repo_meta, dict)
                    else None,
                    committed_at=parse_github_datetime(committed_at),
                    day=day,
                    message=(lambda m: (m.splitlines()[0].strip() if (m or "").splitlines() else ""))(commit_meta.get("message", "")),
                    url=item.get("html_url") or item.get("url") or "",
                )
            )

    commits.sort(key=lambda commit: (commit.committed_at, commit.sha))
    # Fill missing repo names from URLs when possible
    for c in commits:
        if not c.repo or c.repo == "(unknown)":
            html = c.url or ""
            m = re.search(r"github\.com/([^/]+/[^/]+)/", html)
            if m:
                # mutate dataclass by creating a new one — simpler to replace in place
                c_repo = m.group(1)
                # dataclass is frozen; recreate object
                new = CommitRecord(
                    sha=c.sha,
                    repo=c_repo,
                    committed_at=c.committed_at,
                    day=c.day,
                    message=c.message,
                    url=c.url,
                )
                commits[commits.index(c)] = new

    return commits, day_totals


def build_runs(commits: list[CommitRecord]) -> list[RepoRun]:
    if not commits:
        return []

    runs: list[RepoRun] = []
    start = 0
    while start < len(commits):
        end = start
        while end + 1 < len(commits) and commits[end + 1].repo == commits[start].repo:
            end += 1

        start_commit = commits[start]
        end_commit = commits[end]
        duration = max(
            0, int((end_commit.committed_at - start_commit.committed_at).total_seconds() // 60)
        )
        runs.append(
            RepoRun(
                repo=start_commit.repo,
                start_at=start_commit.committed_at,
                end_at=end_commit.committed_at,
                start_day=start_commit.day,
                end_day=end_commit.day,
                commits=end - start + 1,
                duration_minutes=duration,
            )
        )
        start = end + 1

    return runs


def build_day_summaries(commits: list[CommitRecord]) -> list[DaySummary]:
    grouped: dict[str, list[CommitRecord]] = defaultdict(list)
    for commit in commits:
        grouped[commit.day].append(commit)

    summaries = []
    for day in sorted(grouped):
        day_commits = grouped[day]
        sequence = [commit.repo for commit in day_commits]
        switches = sum(
            1 for left, right in zip(sequence, sequence[1:]) if left != right
        )
        summaries.append(
            DaySummary(
                day=day,
                commits=len(day_commits),
                unique_repos=len(set(sequence)),
                switches=switches,
                sequence=sequence,
            )
        )
    return summaries


def effective_repo_count(repo_counts: Counter[str]) -> float:
    total = sum(repo_counts.values())
    if total == 0:
        return 0.0
    sum_of_squares = sum((count / total) ** 2 for count in repo_counts.values())
    if sum_of_squares == 0:
        return 0.0
    return 1 / sum_of_squares


def bounce_back_rate(sequence: list[str], window: int = 5) -> tuple[int, int]:
    opportunities = 0
    bounced = 0
    for index in range(len(sequence) - 2):
        current_repo = sequence[index]
        next_repo = sequence[index + 1]
        if current_repo == next_repo:
            continue
        opportunities += 1
        lookahead = sequence[index + 2 : index + 2 + window]
        if current_repo in lookahead:
            bounced += 1
    return bounced, opportunities


def bucket_streak_lengths(runs: list[RepoRun]) -> Counter[str]:
    buckets = Counter()
    for run in runs:
        if run.commits == 1:
            buckets["1"] += 1
        elif run.commits <= 3:
            buckets["2-3"] += 1
        elif run.commits <= 7:
            buckets["4-7"] += 1
        else:
            buckets["8+"] += 1
    return buckets


# --- RQ4 helpers: Gini, sessionization, weekly aggregation -----------------
def gini_coefficient_from_counts(counter: Counter[str]) -> float:
    """Compute the Gini coefficient for the distribution given by the counter values.

    Returns a value between 0 (perfect equality) and 1 (max inequality).
    """
    values = sorted([v for v in counter.values() if v >= 0])
    n = len(values)
    total = sum(values)
    if n == 0 or total == 0:
        return 0.0
    cumulative = 0
    for i, val in enumerate(values, 1):
        cumulative += i * val
    gini = (2 * cumulative) / (n * total) - (n + 1) / n
    # numeric guard
    return max(0.0, min(1.0, gini))


def sessionize_commits(commits: list[CommitRecord], threshold_minutes: int = 30) -> list[list[CommitRecord]]:
    """Split a time-ordered commit stream into sessions separated by > threshold_minutes inactivity.

    Returns a list of sessions, each a list of CommitRecord in chronological order.
    """
    if not commits:
        return []
    sessions: list[list[CommitRecord]] = []
    current: list[CommitRecord] = [commits[0]]
    for commit in commits[1:]:
        delta_min = (commit.committed_at - current[-1].committed_at).total_seconds() / 60.0
        if delta_min > threshold_minutes:
            sessions.append(current)
            current = [commit]
        else:
            current.append(commit)
    if current:
        sessions.append(current)
    return sessions


def session_switch_counts(sessions: list[list[CommitRecord]]) -> list[int]:
    """Return the number of repository switches inside each session."""
    counts: list[int] = []
    for s in sessions:
        seq = [c.repo for c in s]
        switches = sum(1 for a, b in zip(seq, seq[1:]) if a != b)
        counts.append(switches)
    return counts


def group_commits_by_iso_week(commits: list[CommitRecord]) -> dict[str, list[CommitRecord]]:
    groups: dict[str, list[CommitRecord]] = defaultdict(list)
    for c in commits:
        iso = c.committed_at.isocalendar()
        # iso is (year, week, weekday) - use year-week key
        key = f"{iso.year}-{iso.week:02d}"
        groups[key].append(c)
    return dict(sorted(groups.items()))


# --- Export / batch processing helpers ---------------------------------
SAMPLE_FILE_RE = re.compile(r"(?P<dev>.+)-(?P<year>\d{4})-(?P<month>\d{2})(?:-\d+)?$")


def find_sample_groups(data_dir: Path) -> dict[tuple[str, str], list[Path]]:
    """Return mapping (developer, month) -> list of sample file paths."""
    groups: dict[tuple[str, str], list[Path]] = defaultdict(list)
    for path in data_dir.glob("*.json"):
        m = SAMPLE_FILE_RE.match(path.stem)
        if not m:
            continue
        dev = m.group("dev")
        month = f"{m.group('year')}-{m.group('month')}"
        groups[(dev, month)].append(path)
    return dict(sorted(groups.items()))


def aggregate_day_totals(all_day_totals: dict[str, dict[str, int]], new: dict[str, dict[str, int]]) -> None:
    """Merge day_totals from one sample into the aggregator in-place.

    We sum 'sampled' values and take max of 'total_count' to be conservative.
    """
    for day, vals in new.items():
        if day not in all_day_totals:
            all_day_totals[day] = {"sampled": vals.get("sampled", 0), "total_count": int(vals.get("total_count", 0) or 0)}
        else:
            all_day_totals[day]["sampled"] += vals.get("sampled", 0)
            all_day_totals[day]["total_count"] = max(all_day_totals[day]["total_count"], int(vals.get("total_count", 0) or 0))


def export_all_samples(data_dir: Path, export_dir: Path) -> None:
    export_dir = Path(export_dir)
    export_dir.mkdir(parents=True, exist_ok=True)

    groups = find_sample_groups(data_dir)

    # prepare CSV writers
    commits_f = open(export_dir / "commits.csv", "w", newline="", encoding="utf-8")
    day_f = open(export_dir / "day_summary.csv", "w", newline="", encoding="utf-8")
    weekly_f = open(export_dir / "weekly_summary.csv", "w", newline="", encoding="utf-8")
    sessions_f = open(export_dir / "sessions.csv", "w", newline="", encoding="utf-8")
    runs_f = open(export_dir / "runs.csv", "w", newline="", encoding="utf-8")
    handoffs_f = open(export_dir / "handoffs.csv", "w", newline="", encoding="utf-8")
    repo_dist_f = open(export_dir / "repo_distribution.csv", "w", newline="", encoding="utf-8")

    commits_w = csv.writer(commits_f)
    day_w = csv.writer(day_f)
    weekly_w = csv.writer(weekly_f)
    sessions_w = csv.writer(sessions_f)
    runs_w = csv.writer(runs_f)
    handoffs_w = csv.writer(handoffs_f)
    repo_dist_w = csv.writer(repo_dist_f)

    commits_w.writerow(["developer", "month", "commit_sha", "repo", "committed_at_iso", "day_iso", "message_preview", "url"])
    day_w.writerow(["developer", "month", "day_iso", "sampled_commits", "reported_commits", "unique_repos", "switches", "effective_repos"])
    weekly_w.writerow(["developer", "iso_week", "week_start_iso", "commits_in_week", "gini", "active_repos_ge2", "top_repo", "top_repo_share"])
    sessions_w.writerow(["developer", "month", "session_id", "start_iso", "end_iso", "duration_minutes", "commits_in_session", "switches_in_session", "unique_repos_in_session"])
    runs_w.writerow(["developer", "month", "run_id", "repo", "start_iso", "end_iso", "start_day", "end_day", "commits", "duration_minutes"])
    handoffs_w.writerow(["developer", "month", "left_repo", "right_repo", "count"])
    repo_dist_w.writerow(["developer", "month", "repo", "commits", "share"])

    try:
        for (dev, month), paths in groups.items():
            # load and aggregate commits from all files for this developer-month
            all_commits: list[CommitRecord] = []
            all_day_totals: dict[str, dict[str, int]] = {}
            for p in sorted(paths):
                sample = json.loads(Path(p).read_text())
                commits, day_totals = extract_commits(sample)
                all_commits.extend(commits)
                aggregate_day_totals(all_day_totals, day_totals)

            if not all_commits:
                continue

            # sort full commit stream
            all_commits.sort(key=lambda c: (c.committed_at, c.sha))

            # per-developer-month aggregates
            seq = [c.repo for c in all_commits]
            repo_counts = Counter(seq)
            runs = build_runs(all_commits)
            day_summaries = build_day_summaries(all_commits)
            sessions = sessionize_commits(all_commits, threshold_minutes=30)
            session_switches = session_switch_counts(sessions)
            weekly_groups = group_commits_by_iso_week(all_commits)
            handoffs = Counter((l, r) for l, r in zip(seq, seq[1:]) if l != r)

            # write commits
            for c in all_commits:
                commits_w.writerow([dev, month, c.sha, c.repo, c.committed_at.isoformat(), c.day, c.message[:200], c.url])

            # write day summaries (use day_summaries which reflect sampled commits)
            for ds in day_summaries:
                totals = all_day_totals.get(ds.day, {})
                reported = totals.get("total_count", ds.commits)
                eff = ""
                day_w.writerow([dev, month, ds.day, ds.commits, reported, ds.unique_repos, ds.switches, eff])

            # weekly summary
            for wk, wk_commits in weekly_groups.items():
                wcount = len(wk_commits)
                w_repo_counts = Counter(c.repo for c in wk_commits)
                w_gini = gini_coefficient_from_counts(w_repo_counts)
                w_active = sum(1 for v in w_repo_counts.values() if v >= 2)
                top_repo, top_count = w_repo_counts.most_common(1)[0]
                week_start = min(c.committed_at for c in wk_commits).isoformat()
                weekly_w.writerow([dev, wk, week_start, wcount, f"{w_gini:.6f}", w_active, top_repo, f"{top_count/wcount:.4f}"])

            # sessions
            for i, s in enumerate(sessions, 1):
                start = s[0].committed_at.isoformat()
                end = s[-1].committed_at.isoformat()
                duration = max(0, int((s[-1].committed_at - s[0].committed_at).total_seconds() // 60))
                seqs = [c.repo for c in s]
                switches = sum(1 for a, b in zip(seqs, seqs[1:]) if a != b)
                sessions_w.writerow([dev, month, i, start, end, duration, len(s), switches, len(set(seqs))])

            # runs
            for i, r in enumerate(runs, 1):
                runs_w.writerow([dev, month, i, r.repo, r.start_at.isoformat(), r.end_at.isoformat(), r.start_day, r.end_day, r.commits, r.duration_minutes])

            # handoffs
            for (l, r), cnt in handoffs.most_common():
                handoffs_w.writerow([dev, month, l, r, cnt])

            # repo distribution
            total_commits = len(all_commits)
            for repo, cnt in repo_counts.most_common():
                repo_dist_w.writerow([dev, month, repo, cnt, f"{cnt/total_commits:.6f}"])
    finally:
        commits_f.close()
        day_f.close()
        weekly_f.close()
        sessions_f.close()
        runs_f.close()
        handoffs_f.close()
        repo_dist_f.close()


def classify_style(
    switch_rate: float,
    median_run: float,
    top_repo_share: float,
    effective_repos: float,
    avg_daily_repos: float,
    longest_run: int,
) -> tuple[str, str]:
    if switch_rate >= 0.72 and median_run <= 2 and avg_daily_repos >= 4:
        return (
            "rapid context switcher",
            f"switches on {switch_rate:.0%} of transitions and usually stays only {median_run:.1f} commits per run",
        )
    if switch_rate <= 0.35 and median_run >= 4:
        return (
            "long-repo marathoner",
            f"stays in the same repository for a median run of {median_run:.1f} commits",
        )
    if top_repo_share >= 0.50 and longest_run >= 10:
        return (
            "anchored multitasker",
            f"one repository absorbs {top_repo_share:.0%} of sampled commits, but side trips still happen",
        )
    if effective_repos >= 6 and avg_daily_repos >= 3:
        return (
            "broad portfolio operator",
            f"work is spread across an effective {effective_repos:.1f} repositories with several repos active most days",
        )
    return (
        "clustered multitasker",
        f"switches regularly ({switch_rate:.0%}) but still works in short repository clusters",
    )


def short_repo_name(repo: str, width: int = 28) -> str:
    if len(repo) <= width:
        return repo
    return repo[: width - 3] + "..."


def section(title: str) -> None:
    print()
    print(title)
    print("-" * len(title))


def ratio_bar(value: float, width: int = BAR_WIDTH) -> str:
    filled = round(max(0.0, min(1.0, value)) * width)
    return "#" * filled + "." * (width - filled)


def count_bar(count: int, max_count: int, width: int = BAR_WIDTH) -> str:
    if max_count <= 0:
        return "." * width
    filled = round(count / max_count * width)
    return "#" * filled + "." * (width - filled)


def compress_symbols(sequence: list[str], width: int) -> str:
    if not sequence:
        return ""
    if len(sequence) <= width:
        return "".join(sequence)

    compressed = []
    length = len(sequence)
    for index in range(width):
        start = math.floor(index * length / width)
        end = math.floor((index + 1) * length / width)
        if end <= start:
            end = start + 1
        chunk = sequence[start:end]
        compressed.append(Counter(chunk).most_common(1)[0][0])
    return "".join(compressed)


def repo_symbols(repo_counts: Counter[str], limit: int = 8) -> dict[str, str]:
    mapping = {}
    for symbol, (repo, _) in zip(LEGEND_SYMBOLS, repo_counts.most_common(limit)):
        mapping[repo] = symbol
    return mapping


def print_distribution(repo_counts: Counter[str], total_commits: int, limit: int) -> None:
    if not repo_counts:
        print("No repositories found in the sample.")
        return

    max_count = repo_counts.most_common(1)[0][1]
    for repo, count in repo_counts.most_common(limit):
        share = count / total_commits if total_commits else 0.0
        print(
            f"  {short_repo_name(repo):<30} {count_bar(count, max_count)}  "
            f"{count:>4}  {share:>5.1%}"
        )


def print_streak_distribution(streaks: Counter[str]) -> None:
    order = ["1", "2-3", "4-7", "8+"]
    total = sum(streaks.values())
    max_count = max(streaks.values(), default=0)
    for label in order:
        count = streaks[label]
        share = count / total if total else 0.0
        print(
            f"  {label:<4} {count_bar(count, max_count)}  "
            f"{count:>4}  {share:>5.1%}"
        )


def print_handoffs(handoffs: Counter[tuple[str, str]], limit: int = 8) -> None:
    if not handoffs:
        print("  No repository hand-offs in this sample.")
        return

    max_count = handoffs.most_common(1)[0][1]
    for (left, right), count in handoffs.most_common(limit):
        label = f"{short_repo_name(left, 18)} -> {short_repo_name(right, 18)}"
        print(f"  {label:<42} {count_bar(count, max_count, 20)}  {count:>4}")


def print_timeline(
    summaries: list[DaySummary],
    repo_counts: Counter[str],
    width: int,
) -> None:
    if not summaries:
        print("  No active days in the sample.")
        return

    symbols = repo_symbols(repo_counts)
    if symbols:
        print("  Legend:")
        for repo, symbol in symbols.items():
            print(f"    {symbol} = {repo}")
        print("    . = all other repositories")
        print()

    for summary in summaries:
        encoded = [symbols.get(repo, ".") for repo in summary.sequence]
        stripe = compress_symbols(encoded, width)
        print(
            f"  {summary.day}  {summary.commits:>4}c  {summary.unique_repos:>2}r  "
            f"{summary.switches:>3}s  |{stripe:<{width}}|"
        )


def print_runs(runs: list[RepoRun], limit: int = 8) -> None:
    if not runs:
        print("  No contiguous repository runs found.")
        return

    for run in sorted(runs, key=lambda item: (-item.commits, item.start_at))[:limit]:
        duration = f"{run.duration_minutes}m" if run.duration_minutes else "<1m"
        print(
            f"  {short_repo_name(run.repo, 30):<30} {run.commits:>4} commits  "
            f"{run.start_day} -> {run.end_day}  span {duration}"
        )


def print_summary_line(label: str, value: str) -> None:
    print(f"  {label:<24} {value}")


def main() -> None:
    args = parse_args()
    developers_path = Path(args.developers_file)
    if not developers_path.is_file():
        raise SystemExit(f"Developers file not found: {developers_path}")

    developers = load_developers(developers_path)
    if args.export_all:
        export_all_samples(DATA_DIR, Path(args.export_dir))
        print(f"Exported CSVs to {args.export_dir}")
        return

    developer = resolve_developer(developers, args.author)
    if developer is None:
        raise SystemExit(f"Developer '{args.author}' not found in {developers_path}.")

    handle = developer["handle"]
    year, month = (
        args.month
        or latest_local_sample_month(handle)
        or latest_nonzero_month(developer)
    )
    sample, source_path = load_or_collect_sample(
        handle, year, month, refresh=args.refresh, day_pages=args.day_pages
    )

    commits, day_totals = extract_commits(sample)
    if not commits:
        print(f"No sampled commits found for @{handle} in {month_string(year, month)}.")
        return

    sequence = [commit.repo for commit in commits]
    repo_counts: Counter[str] = Counter(sequence)
    runs = build_runs(commits)
    day_summaries = build_day_summaries(commits)

    transitions = max(0, len(sequence) - 1)
    switch_count = sum(1 for left, right in zip(sequence, sequence[1:]) if left != right)
    switch_rate = switch_count / transitions if transitions else 0.0
    run_lengths = [run.commits for run in runs]
    median_run = float(median(run_lengths)) if run_lengths else 0.0
    longest_run = max(run_lengths, default=0)
    effective_repos = effective_repo_count(repo_counts)
    top_repo, top_repo_count = repo_counts.most_common(1)[0]
    top_repo_share = top_repo_count / len(commits)
    bounced, bounce_opportunities = bounce_back_rate(sequence)
    bounce_rate = bounced / bounce_opportunities if bounce_opportunities else 0.0
    avg_daily_repos = (
        sum(summary.unique_repos for summary in day_summaries) / len(day_summaries)
        if day_summaries
        else 0.0
    )
    handoffs = Counter(
        (left, right) for left, right in zip(sequence, sequence[1:]) if left != right
    )
    streak_buckets = bucket_streak_lengths(runs)
    total_reported = sum(day["total_count"] for day in day_totals.values())
    coverage = len(commits) / total_reported if total_reported else 1.0
    capped_days = sum(1 for day in day_totals.values() if day["total_count"] > MAX_SEARCH_RESULTS)
    style, style_note = classify_style(
        switch_rate=switch_rate,
        median_run=median_run,
        top_repo_share=top_repo_share,
        effective_repos=effective_repos,
        avg_daily_repos=avg_daily_repos,
        longest_run=longest_run,
    )

    # RQ4 additional metrics
    monthly_gini = gini_coefficient_from_counts(repo_counts)
    active_repos_month = sum(1 for c in repo_counts.values() if c >= 2)
    sessions = sessionize_commits(commits, threshold_minutes=30)
    session_switches = session_switch_counts(sessions)
    session_durations = [
        max(0, int((s[-1].committed_at - s[0].committed_at).total_seconds() // 60))
        for s in sessions
        if s
    ]
    weekly_groups = group_commits_by_iso_week(commits)

    print(f"\nRepository Switching Analysis - @{handle} - {month_string(year, month)}")
    print("=" * 68)
    print_summary_line("Sample source", str(source_path.relative_to(REPO_ROOT)))
    print_summary_line("Sampled commits", str(len(commits)))
    print_summary_line("Reported commits", f"{total_reported:,}")
    print_summary_line("Coverage", f"{coverage:.2%}")
    print_summary_line("Repositories touched", str(len(repo_counts)))
    print_summary_line("Active days", str(len(day_summaries)))
    print_summary_line("Style", f"{style} ({style_note})")
    print_summary_line("Active repos (>=2 commits)", str(active_repos_month))
    print_summary_line("Gini (month)", f"{monthly_gini:.3f}")

    if coverage < 0.95:
        print()
        print(
            "  Note: switching metrics below are based on the sampled commit stream, "
            "not the full set of reported commits."
        )
        if capped_days:
            print(
                f"  {capped_days} day(s) exceeded GitHub's 1,000-result search cap, "
                "so those days are especially approximate."
            )

    section("Repository Distribution")
    print_distribution(repo_counts, len(commits), args.top_repos)
    print()
    print_summary_line("Top repository", f"{top_repo} ({top_repo_share:.1%})")
    print_summary_line("Effective repo count", f"{effective_repos:.2f}")
    print_summary_line(
        "Focus ratio",
        f"[{ratio_bar(top_repo_share)}] {top_repo_share:.1%} of commits in the top repo",
    )

    section("Switching Dynamics")
    print_summary_line("Repository switches", f"{switch_count} / {transitions} transitions")
    print_summary_line("Switch rate", f"[{ratio_bar(switch_rate)}] {switch_rate:.1%}")
    print_summary_line("Median streak", f"{median_run:.1f} commits")
    print_summary_line("Longest streak", f"{longest_run} commits")
    print_summary_line("Bounce-back rate", f"{bounce_rate:.1%} ({bounced}/{bounce_opportunities})")
    print()
    print("  Streak length distribution")
    print_streak_distribution(streak_buckets)

    section("Common Hand-offs")
    print_handoffs(handoffs)

    section("Longest Repository Runs")
    print_runs(runs)

    section("Daily Flow")
    print_timeline(day_summaries, repo_counts, args.timeline_width)
    print()

    section("Weekly Concentration")
    if not weekly_groups:
        print("  No weekly data available.")
    else:
        print("  Week   Commits  Gini   ActiveRepos(>=2)")
        for week, week_commits in weekly_groups.items():
            wcount = len(week_commits)
            w_repo_counts = Counter(c.repo for c in week_commits)
            w_gini = gini_coefficient_from_counts(w_repo_counts)
            w_active = sum(1 for v in w_repo_counts.values() if v >= 2)
            print(f"  {week}  {wcount:>7}  {w_gini:>4.3f}     {w_active}")

    section("Session Metrics (30m inactivity)")
    if not sessions:
        print("  No sessions found in the sample.")
    else:
        total_sessions = len(sessions)
        avg_switches = sum(session_switches) / total_sessions if total_sessions else 0.0
        median_switches = float(median(session_switches)) if session_switches else 0.0
        avg_session_minutes = float(median(session_durations)) if session_durations else 0.0
        print_summary_line("Total sessions", str(total_sessions))
        print_summary_line("Median switches/session", f"{median_switches:.1f}")
        print_summary_line("Avg switches/session", f"{avg_switches:.2f}")
        print_summary_line("Median session length", f"{avg_session_minutes:.0f} minutes")
        print()

    # If requested, run export across all local sample files and exit
    if args.export_all:
        export_all_samples(DATA_DIR, Path(args.export_dir))
        print(f"Exported CSVs to {args.export_dir}")
        return


if __name__ == "__main__":
    main()
