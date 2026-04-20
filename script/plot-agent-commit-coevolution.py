#!/usr/bin/env python3
"""Plot the co-evolution of commit activity and agent use over time.

The script aggregates all locally sampled developers, buckets the data by day,
week, or month, and compares:

- total commits from daily snapshot totals
- embedded commit records stored in snapshot files
- commit records with at least one agent signal

Outputs:
- a PNG figure
- a text summary with correlation metrics
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from statistics import mean
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = REPO_ROOT / "data"
COMMITS_DIR = DATA_DIR / "commits"
DEVELOPERS_FILE = REPO_ROOT / "developers.json"
AGENTS_DIR = REPO_ROOT / "agent-mining" / "agents"
DEFAULT_OUTPUT = REPO_ROOT / "figures" / "agent-commit-coevolution.png"
DEFAULT_SUMMARY = REPO_ROOT / "figures" / "agent-commit-coevolution.txt"
SNAPSHOT_RE = re.compile(r"^(?P<handle>.+)-(?P<month>\d{4}-\d{2})$")

sys.path.insert(0, str(REPO_ROOT / "agent-mining"))
from heuristic import load_heuristics, _match_pattern  # noqa: E402


@dataclass
class PeriodStats:
    label: str
    total_commits: int = 0
    commit_records: int = 0
    agent_commit_records: int = 0

    @property
    def agent_share(self) -> float:
        if self.commit_records == 0:
            return 0.0
        return self.agent_commit_records / self.commit_records * 100.0

    @property
    def coverage(self) -> float:
        if self.total_commits == 0:
            return 0.0
        return self.commit_records / self.total_commits * 100.0


def parse_day(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"Invalid date '{value}'. Expected YYYY-MM-DD."
        ) from exc


def resolve_output(path: Path) -> Path:
    return path if path.is_absolute() else REPO_ROOT / path


def choose_granularity(day_count: int, requested: str) -> str:
    if requested != "auto":
        return requested
    if day_count <= 45:
        return "day"
    if day_count <= 370:
        return "week"
    return "month"


def bucket_start(day_value: date, granularity: str) -> date:
    if granularity == "day":
        return day_value
    if granularity == "week":
        return day_value - timedelta(days=day_value.weekday())
    if granularity == "month":
        return day_value.replace(day=1)
    raise ValueError(f"Unsupported granularity: {granularity}")


def bucket_label(day_value: date, granularity: str) -> str:
    if granularity == "day":
        return day_value.isoformat()
    if granularity == "week":
        return f"Week of {day_value.isoformat()}"
    return day_value.strftime("%Y-%m")


def load_developers() -> set[str]:
    developers = json.loads(DEVELOPERS_FILE.read_text())
    return {entry["handle"] for entry in developers}


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


def load_period_stats(
    granularity: str, start: date | None, end: date | None
) -> tuple[list[date], list[PeriodStats], list[str]]:
    tracked_handles = load_developers()
    heuristics_by_agent = load_heuristics(str(AGENTS_DIR))
    periods: defaultdict[date, PeriodStats] = defaultdict(lambda: PeriodStats(label=""))
    sampled_handles: set[str] = set()

    for path in sorted(DATA_DIR.glob("*.json")):
        match = SNAPSHOT_RE.match(path.stem)
        if not match:
            continue
        handle = match.group("handle")
        if handle not in tracked_handles:
            continue

        sampled_handles.add(handle)
        payload = json.loads(path.read_text())
        for day_str, day_info in payload.get("days", {}).items():
            day_value = date.fromisoformat(day_str)

            if start and day_value < start:
                continue
            if end and day_value > end:
                continue

            key = bucket_start(day_value, granularity)
            period = periods[key]
            period.label = bucket_label(key, granularity)
            period.total_commits += int(day_info.get("total_count", 0))

            for commit in day_info.get("commits", []):
                period.commit_records += 1
                detail = load_commit_detail(commit.get("sha", ""))
                if detect_agents(commit, detail, heuristics_by_agent):
                    period.agent_commit_records += 1

    if not periods:
        raise SystemExit("No snapshot data found for the selected range.")

    period_keys = sorted(periods)
    stats = [periods[key] for key in period_keys]
    return period_keys, stats, sorted(sampled_handles)


def pearson(xs: list[float], ys: list[float]) -> float:
    if len(xs) != len(ys) or len(xs) < 2:
        return math.nan
    mean_x = mean(xs)
    mean_y = mean(ys)
    num = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    den_x = math.sqrt(sum((x - mean_x) ** 2 for x in xs))
    den_y = math.sqrt(sum((y - mean_y) ** 2 for y in ys))
    if den_x == 0 or den_y == 0:
        return math.nan
    return num / (den_x * den_y)


def linear_fit(xs: list[float], ys: list[float]) -> tuple[float, float]:
    n = len(xs)
    sum_x = sum(xs)
    sum_y = sum(ys)
    sum_xx = sum(x * x for x in xs)
    sum_xy = sum(x * y for x, y in zip(xs, ys))
    denom = n * sum_xx - sum_x * sum_x
    if denom == 0:
        return 0.0, ys[0] if ys else 0.0
    slope = (n * sum_xy - sum_x * sum_y) / denom
    intercept = (sum_y - slope * sum_x) / n
    return slope, intercept


def summarize(
    stats: list[PeriodStats],
    granularity: str,
    sampled_handles: list[str],
    start: date,
    end: date,
) -> str:
    total_commits = sum(item.total_commits for item in stats)
    total_records = sum(item.commit_records for item in stats)
    total_agent_records = sum(item.agent_commit_records for item in stats)
    xs = [float(item.total_commits) for item in stats]
    ys = [float(item.agent_commit_records) for item in stats]
    agent_share_series = [item.agent_share for item in stats]
    raw_r = pearson(xs, ys)
    share_r = pearson(xs, agent_share_series)
    max_period = max(stats, key=lambda item: item.total_commits)
    max_agent_period = max(stats, key=lambda item: item.agent_commit_records)

    lines = [
        f"Range: {start.isoformat()} to {end.isoformat()}",
        f"Granularity: {granularity}",
        f"Sampled developers aggregated: {len(sampled_handles)} ({', '.join(sampled_handles)})",
        f"Periods: {len(stats)}",
        f"Total commits: {total_commits:,}",
        f"Embedded commit records: {total_records:,}",
        f"Agent-signaled commit records: {total_agent_records:,}",
        (
            f"Overall agent share of commit records: {total_agent_records / total_records * 100:.1f}%"
            if total_records
            else "Overall agent share of commit records: -"
        ),
        f"Correlation, total commits vs agent-signaled records: {raw_r:.4f}",
        f"Correlation, total commits vs agent share: {share_r:.4f}",
        (
            f"Peak commit period: {max_period.label} ({max_period.total_commits:,} commits)"
            if max_period
            else "Peak commit period: -"
        ),
        (
            f"Peak agent-signaled period: {max_agent_period.label} ({max_agent_period.agent_commit_records:,} records)"
            if max_agent_period
            else "Peak agent-signaled period: -"
        ),
    ]
    return "\n".join(lines)


def plot(
    stats: list[PeriodStats],
    summary_text: str,
    output_path: Path,
) -> None:
    labels = [item.label for item in stats]
    total_commits = [item.total_commits for item in stats]
    agent_records = [item.agent_commit_records for item in stats]

    xs = list(range(len(stats)))
    fig, ax_main = plt.subplots(figsize=(15, 6.5))

    bar_color = "#264653"
    line_color = "#d62828"

    ax_main.bar(xs, total_commits, color=bar_color, alpha=0.85, label="total commits")
    ax_main.set_ylabel("Total commits")
    ax_main.grid(axis="y", alpha=0.25)
    ax_main.set_title(
        "Commit Volume and Agent-Signaled Commits Over Time",
        fontweight="bold",
    )
    ax_main.set_xlabel("Period")
    ax_main.set_xticks(xs)
    ax_main.set_xticklabels(labels, rotation=45, ha="right")

    ax_main_twin = ax_main.twinx()
    ax_main_twin.plot(
        xs,
        agent_records,
        color=line_color,
        marker="o",
        linewidth=2.4,
        label="agent-signaled commit records",
    )
    ax_main_twin.set_ylabel("Agent-signaled commit records")

    combined_handles, combined_labels = [], []
    for axis in (ax_main, ax_main_twin):
        handles, axis_labels = axis.get_legend_handles_labels()
        combined_handles.extend(handles)
        combined_labels.extend(axis_labels)
    ax_main.legend(combined_handles, combined_labels, loc="upper left")

    fig.text(
        0.5,
        0.96,
        "Bars show total commits from daily snapshot totals; the line shows commit records with at least one detected agent signal.",
        ha="center",
        va="top",
        fontsize=10,
    )

    fig.tight_layout(rect=(0, 0, 1, 0.93))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plot aggregate commit activity and agent use over time."
    )
    parser.add_argument(
        "--granularity",
        choices=("auto", "day", "week", "month"),
        default="auto",
        help="Time bucket size for the chart (default: auto).",
    )
    parser.add_argument(
        "--start",
        type=parse_day,
        help="First day to include (YYYY-MM-DD). Defaults to the earliest local snapshot day.",
    )
    parser.add_argument(
        "--end",
        type=parse_day,
        help="Last day to include (YYYY-MM-DD). Defaults to the latest local snapshot day.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"PNG output path (default: {DEFAULT_OUTPUT.relative_to(REPO_ROOT)}).",
    )
    parser.add_argument(
        "--summary-output",
        type=Path,
        default=DEFAULT_SUMMARY,
        help=f"Text summary path (default: {DEFAULT_SUMMARY.relative_to(REPO_ROOT)}).",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()

    all_days: list[date] = []
    tracked_handles = load_developers()
    for path in sorted(DATA_DIR.glob("*.json")):
        match = SNAPSHOT_RE.match(path.stem)
        if not match or match.group("handle") not in tracked_handles:
            continue
        payload = json.loads(path.read_text())
        for day_str in payload.get("days", {}):
            all_days.append(date.fromisoformat(day_str))
    if not all_days:
        raise SystemExit("No local snapshot data found.")

    data_start = min(all_days)
    data_end = max(all_days)
    start = max(args.start or data_start, data_start)
    end = min(args.end or data_end, data_end)
    if start > end:
        raise SystemExit("--start must be on or before --end.")

    granularity = choose_granularity((end - start).days + 1, args.granularity)
    period_keys, stats, sampled_handles = load_period_stats(granularity, start, end)
    summary_text = summarize(stats, granularity, sampled_handles, start, end)

    output_path = resolve_output(args.output)
    summary_path = resolve_output(args.summary_output)
    plot(stats, summary_text, output_path)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(summary_text + "\n")

    print(summary_text)
    print()
    print(f"Figure saved to {output_path}")
    print(f"Summary saved to {summary_path}")


if __name__ == "__main__":
    main()
