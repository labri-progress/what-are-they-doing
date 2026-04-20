#!/usr/bin/env python3
"""Generate a Zenodo-friendly Markdown summary of the local dataset.

The summary combines:
- developer registry coverage from developers.json
- commit totals from data/<handle>-YYYY-MM.json
- heuristic agent-use signals from sampled commit records

Outputs a Markdown report to stdout and, by default, to
figures/zenodo-dataset-summary.md.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = REPO_ROOT / "data"
COMMITS_DIR = DATA_DIR / "commits"
DEVELOPERS_FILE = REPO_ROOT / "developers.json"
DEFAULT_OUTPUT = REPO_ROOT / "figures" / "zenodo-dataset-summary.md"
AGENTS_DIR = REPO_ROOT / "agent-mining" / "agents"
SNAPSHOT_RE = re.compile(r"^(?P<handle>.+)-(?P<month>\d{4}-\d{2})$")

sys.path.insert(0, str(REPO_ROOT / "agent-mining"))
from heuristic import load_heuristics, _match_pattern  # noqa: E402


@dataclass
class DeveloperSummary:
    handle: str
    tracked_repos: int
    sampled_months: int = 0
    total_commits: int = 0
    active_days: int = 0
    peak_day_commits: int = 0
    first_month: str | None = None
    last_month: str | None = None
    agent_commits: int = 0
    top_agent: str = "-"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a Markdown summary of the extracted dataset."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Markdown output path (default: {DEFAULT_OUTPUT.relative_to(REPO_ROOT)}).",
    )
    parser.add_argument(
        "--stdout-only",
        action="store_true",
        help="Print the report to stdout without writing a file.",
    )
    return parser.parse_args()


def resolve_output(path: Path) -> Path:
    return path if path.is_absolute() else REPO_ROOT / path


def load_developers() -> list[dict[str, Any]]:
    return json.loads(DEVELOPERS_FILE.read_text())


def load_commit_detail(sha: str) -> dict[str, Any]:
    path = COMMITS_DIR / f"{sha}.json"
    if not path.exists():
        return {"message": "", "files": []}
    raw = json.loads(path.read_text())
    if isinstance(raw, list):
        return {"message": "", "files": raw}
    return raw


def match_files(heuristics_list: list[Any], changed_filenames: list[str]) -> bool:
    for heuristic in heuristics_list:
        for pattern in heuristic.files:
            for filename in changed_filenames:
                if _match_pattern(pattern, filename):
                    return True
    return False


def detect_agents(
    commit_item: dict[str, Any],
    detail: dict[str, Any],
    heuristics_by_agent: dict[str, list[Any]],
) -> list[str]:
    message = detail.get("message") or commit_item.get("commit", {}).get("message", "")
    author = commit_item.get("commit", {}).get("author", {})
    commit_author = f"{author.get('name', '')} <{author.get('email', '')}>"
    filenames = [item.get("filename", "") for item in detail.get("files", [])]

    matched: list[str] = []
    for agent_name, heuristics_list in heuristics_by_agent.items():
        for heuristic in heuristics_list:
            if heuristic.match_commit(message, commit_author):
                matched.append(agent_name)
                break
        else:
            if filenames and match_files(heuristics_list, filenames):
                matched.append(agent_name)
    return matched


def safe_pct(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "-"
    return f"{numerator / denominator * 100:.1f}%"


def render_markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    table = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        table.append("| " + " | ".join(row) + " |")
    return "\n".join(table)


def format_int(value: int) -> str:
    return f"{value:,}"


def summarize_dataset() -> str:
    developers = load_developers()
    heuristics_by_agent = load_heuristics(str(AGENTS_DIR))

    developer_summaries: dict[str, DeveloperSummary] = {
        dev["handle"]: DeveloperSummary(
            handle=dev["handle"],
            tracked_repos=len(dev.get("repos", [])),
        )
        for dev in developers
    }

    all_months: set[str] = set()
    snapshot_count = 0
    aggregate_daily: Counter[date] = Counter()
    sampled_developers: set[str] = set()
    agent_hits: Counter[str] = Counter()
    agent_developers: defaultdict[str, set[str]] = defaultdict(set)
    per_developer_agent_hits: defaultdict[str, Counter[str]] = defaultdict(Counter)
    total_commits = 0
    agent_commit_records = 0
    multi_agent_commit_records = 0

    for path in sorted(DATA_DIR.glob("*.json")):
        match = SNAPSHOT_RE.match(path.stem)
        if not match:
            continue
        handle = match.group("handle")
        if handle not in developer_summaries:
            continue

        payload = json.loads(path.read_text())
        month_key = str(payload.get("month", match.group("month")))
        all_months.add(month_key)
        snapshot_count += 1
        sampled_developers.add(handle)

        summary = developer_summaries[handle]
        summary.sampled_months += 1
        summary.first_month = month_key if summary.first_month is None else min(summary.first_month, month_key)
        summary.last_month = month_key if summary.last_month is None else max(summary.last_month, month_key)

        for day_str, day_info in payload.get("days", {}).items():
            day_value = date.fromisoformat(day_str)
            commits = day_info.get("commits", [])
            day_count = len(commits)
            aggregate_daily[day_value] += day_count
            total_commits += day_count
            summary.total_commits += day_count

            if day_count:
                summary.active_days += 1
                summary.peak_day_commits = max(summary.peak_day_commits, day_count)

            for commit in commits:
                detail = load_commit_detail(commit.get("sha", ""))
                matched_agents = detect_agents(commit, detail, heuristics_by_agent)
                if matched_agents:
                    agent_commit_records += 1
                    summary.agent_commits += 1
                    if len(matched_agents) > 1:
                        multi_agent_commit_records += 1
                for agent_name in matched_agents:
                    agent_hits[agent_name] += 1
                    agent_developers[agent_name].add(handle)
                    per_developer_agent_hits[handle][agent_name] += 1

    for handle, summary in developer_summaries.items():
        if per_developer_agent_hits[handle]:
            summary.top_agent = per_developer_agent_hits[handle].most_common(1)[0][0]

    tracked_repo_count = len({repo for dev in developers for repo in dev.get("repos", [])})
    sampled_repo_count = len(
        {
            repo
            for dev in developers
            if dev["handle"] in sampled_developers
            for repo in dev.get("repos", [])
        }
    )
    active_developer_days = sum(summary.active_days for summary in developer_summaries.values())
    aggregate_peak_day, aggregate_peak_count = max(aggregate_daily.items(), key=lambda item: item[1])

    overview_rows = [
        ["Observation window", f"{min(aggregate_daily).isoformat()} to {max(aggregate_daily).isoformat()}"],
        ["Tracked repositories in dataset", format_int(tracked_repo_count)],
        ["Total embedded commit records", format_int(total_commits)],
        ["Peak aggregate day", f"{aggregate_peak_day.isoformat()} ({format_int(aggregate_peak_count)} commits)"],
        [
            "Commit records with >=1 hard agent signal",
            f"{format_int(agent_commit_records)} / {format_int(total_commits)} ({safe_pct(agent_commit_records, total_commits)})",
        ],
        ["Distinct detected agent labels", format_int(len(agent_hits))],
        [
            "Most frequent detected agent",
            (
                f"{agent_hits.most_common(1)[0][0]} ({format_int(agent_hits.most_common(1)[0][1])} commits)"
                if agent_hits
                else "-"
            ),
        ],
    ]

    developer_rows: list[list[str]] = []
    for handle in sorted(developer_summaries):
        summary = developer_summaries[handle]
        month_span = (
            f"{summary.first_month}..{summary.last_month}" if summary.first_month and summary.last_month else "-"
        )
        developer_rows.append(
            [
                handle,
                month_span,
                format_int(summary.tracked_repos),
                format_int(summary.total_commits) if summary.sampled_months else "-",
                format_int(summary.active_days) if summary.sampled_months else "-",
                format_int(summary.peak_day_commits) if summary.sampled_months else "-",
                format_int(summary.agent_commits) if summary.sampled_months else "-",
                safe_pct(summary.agent_commits, summary.total_commits)
                if summary.sampled_months
                else "-",
                summary.top_agent if summary.sampled_months else "-",
            ]
        )

    agent_rows: list[list[str]] = []
    for agent_name, hit_count in agent_hits.most_common():
        agent_rows.append(
            [
                agent_name,
                format_int(hit_count),
                safe_pct(hit_count, total_commits),
                format_int(len(agent_developers[agent_name])),
            ]
        )

    notes = [
        "- `Total embedded commit records` counts the commit objects stored inside the monthly snapshot files; all agent-use metrics use this same total as the denominator.",
        "- Agent-use labels are heuristic detections from commit messages, co-author trailers, commit authors, and cached changed-file signals when available.",
        f"- The current local dataset contains monthly snapshots for {len(sampled_developers)} of the {len(developer_summaries)} tracked developers listed in `developers.json`.",
    ]

    sections = [
        "# Dataset Summary",
        "",
        "## Overview",
        render_markdown_table(["Metric", "Value"], overview_rows),
        "",
        "## Developer Coverage",
        render_markdown_table(
            [
                "Developer",
                "Month span",
                "Tracked repos",
                "Commits",
                "Active days",
                "Peak day",
                "Agent commits",
                "Agent %",
                "Top agent",
            ],
            developer_rows,
        ),
        "",
        "## Agent Signals",
        render_markdown_table(
            ["Agent", "Hits", "Share of commit records", "Developers with signal"],
            agent_rows or [["-", "-", "-", "-"]],
        ),
        "",
        "## Notes",
        *notes,
        "",
    ]
    return "\n".join(sections)


def main() -> None:
    args = parse_args()
    output_path = resolve_output(args.output)
    report = summarize_dataset()
    print(report)
    if not args.stdout_only:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(report)
        print(f"\nSaved report to {output_path}")


if __name__ == "__main__":
    main()
