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
DEFAULT_PER_DEVELOPER_OUTPUT = (
    REPO_ROOT / "figures" / "agent-commit-coevolution-per-developer.png"
)
SNAPSHOT_RE = re.compile(r"^(?P<handle>.+)-(?P<month>\d{4}-\d{2})$")

sys.path.insert(0, str(REPO_ROOT / "agent-mining"))
from heuristic import load_heuristics, _match_pattern  # noqa: E402


@dataclass
class PeriodStats:
    label: str
    key: date | None = None
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
        return f"W{day_value.isocalendar().week:02d}"
    return day_value.strftime("%Y-%m")


def display_labels(keys: list[date], granularity: str) -> list[str]:
    """Short axis labels: week numbers, with the year shown when it changes."""
    labels: list[str] = []
    previous_year: int | None = None
    for key in keys:
        if granularity == "week":
            iso = key.isocalendar()
            if iso.year != previous_year:
                labels.append(f"{iso.year} W{iso.week:02d}")
            else:
                labels.append(f"W{iso.week:02d}")
            previous_year = iso.year
        else:
            labels.append(bucket_label(key, granularity))
    return labels


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


def load_stats_by_developer(
    granularity: str, start: date | None, end: date | None
) -> tuple[list[date], dict[str, dict[date, PeriodStats]], list[str]]:
    """Bucket stats per developer, keyed by the same period keys for everyone."""
    tracked_handles = load_developers()
    heuristics_by_agent = load_heuristics(str(AGENTS_DIR))
    per_developer: dict[str, defaultdict[date, PeriodStats]] = {}
    all_keys: set[date] = set()

    for path in sorted(DATA_DIR.glob("*.json")):
        match = SNAPSHOT_RE.match(path.stem)
        if not match:
            continue
        handle = match.group("handle")
        if handle not in tracked_handles:
            continue

        periods = per_developer.setdefault(
            handle, defaultdict(lambda: PeriodStats(label=""))
        )
        payload = json.loads(path.read_text())
        for day_str, day_info in payload.get("days", {}).items():
            day_value = date.fromisoformat(day_str)

            if start and day_value < start:
                continue
            if end and day_value > end:
                continue

            key = bucket_start(day_value, granularity)
            all_keys.add(key)
            period = periods[key]
            period.key = key
            period.label = bucket_label(key, granularity)
            period.total_commits += int(day_info.get("total_count", 0))

            for commit in day_info.get("commits", []):
                period.commit_records += 1
                detail = load_commit_detail(commit.get("sha", ""))
                if detect_agents(commit, detail, heuristics_by_agent):
                    period.agent_commit_records += 1

    if not all_keys:
        raise SystemExit("No snapshot data found for the selected range.")

    period_keys = sorted(all_keys)
    # Densify: every developer gets an entry for every period, zero-filled.
    dense: dict[str, dict[date, PeriodStats]] = {}
    for handle, periods in per_developer.items():
        row: dict[date, PeriodStats] = {}
        for key in period_keys:
            period = periods.get(key)
            if period is None:
                period = PeriodStats(label=bucket_label(key, granularity), key=key)
            row[key] = period
        dense[handle] = row
    return period_keys, dense, sorted(per_developer)


def combine_period(
    per_developer: dict[str, dict[date, PeriodStats]],
    key: date,
    sampled_handles: list[str],
    granularity: str,
) -> PeriodStats:
    """Sum one period across all developers."""
    combined = PeriodStats(label=bucket_label(key, granularity), key=key)
    for handle in sampled_handles:
        period = per_developer[handle][key]
        combined.total_commits += period.total_commits
        combined.commit_records += period.commit_records
        combined.agent_commit_records += period.agent_commit_records
    return combined


def load_period_stats(
    granularity: str, start: date | None, end: date | None
) -> tuple[list[date], list[PeriodStats], list[str]]:
    period_keys, per_developer, sampled_handles = load_stats_by_developer(
        granularity, start, end
    )
    stats = [
        combine_period(per_developer, key, sampled_handles, granularity)
        for key in period_keys
    ]
    return period_keys, stats, sampled_handles


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
    granularity: str,
    output_path: Path,
) -> None:
    keys = [item.key for item in stats if item.key is not None]
    labels = display_labels(keys, granularity)
    total_commits = [item.total_commits for item in stats]
    agent_records = [item.agent_commit_records for item in stats]
    agent_shares = [item.agent_share for item in stats]

    raw_r = pearson(
        [float(value) for value in total_commits],
        [float(value) for value in agent_records],
    )
    share_r = pearson(
        [float(value) for value in total_commits],
        [float(value) for value in agent_shares],
    )

    xs = list(range(len(stats)))
    fig, ax_main = plt.subplots(figsize=(15, 6.5))

    bar_color = "#264653"
    line_color = "#d62828"
    # Lightness-separated from the red line and light enough to stand out
    # against the dark slate bars.
    share_color = "#ffa62b"

    # Total commits and agent-signaled records are both plain commit counts on
    # the same scale, so they share the left axis. The agent share is a
    # percentage and therefore gets its own right axis.
    ax_main.bar(xs, total_commits, color=bar_color, alpha=0.85, label="total commits")
    ax_main.plot(
        xs,
        agent_records,
        color=line_color,
        marker="o",
        linewidth=2.4,
        label=(
            "agent-signaled commit records"
            if math.isnan(raw_r)
            else f"agent-signaled commit records (r = {raw_r:.2f})"
        ),
    )
    ax_main.set_ylabel("Commits per period", fontsize=16)
    ax_main.set_ylim(bottom=0)
    ax_main.grid(axis="y", alpha=0.25)
    ax_main.set_xticks(xs)
    ax_main.set_xticklabels(labels, rotation=45, ha="right", fontsize=14)
    ax_main.tick_params(axis="y", labelsize=14)

    ax_share = ax_main.twinx()
    ax_share.plot(
        xs,
        agent_shares,
        color=share_color,
        marker="s",
        markersize=4,
        linewidth=1.8,
        linestyle="--",
        label=(
            "agent share of commit records"
            if math.isnan(share_r)
            else f"agent share of commit records (r = {share_r:.2f})"
        ),
    )
    ax_share.set_ylabel("Agent share of commit records (%)", fontsize=16)
    ax_share.set_ylim(bottom=0)
    ax_share.tick_params(axis="y", labelsize=14)

    handles = ax_main.get_legend_handles_labels()
    share_handles = ax_share.get_legend_handles_labels()
    ax_main.legend(
        handles[0] + share_handles[0],
        handles[1] + share_handles[1],
        loc="upper left",
        fontsize=14,
    )

    fig.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def per_developer_correlations(
    period_keys: list[date],
    per_developer: dict[str, dict[date, PeriodStats]],
) -> dict[str, dict[str, float]]:
    """Pearson correlations of the plotted series, computed per developer."""
    results: dict[str, dict[str, float]] = {}
    for handle in sorted(per_developer):
        row = per_developer[handle]
        totals = [float(row[key].total_commits) for key in period_keys]
        agents = [float(row[key].agent_commit_records) for key in period_keys]
        shares = [row[key].agent_share for key in period_keys]
        results[handle] = {
            "total_vs_agent_records": pearson(totals, agents),
            "total_vs_agent_share": pearson(totals, shares),
            "periods_with_commits": float(sum(1 for value in totals if value > 0)),
        }
    return results


def format_per_developer_correlations(
    correlations: dict[str, dict[str, float]],
    granularity: str,
) -> str:
    def fmt(value: float) -> str:
        return "n/a" if math.isnan(value) else f"{value:.4f}"

    lines = [
        f"Per-developer correlations ({granularity} buckets)",
        "r(total commits, agent-signaled records) | "
        "r(total commits, agent share) | periods with commits",
    ]
    for handle in sorted(correlations):
        entry = correlations[handle]
        lines.append(
            f"{handle}: "
            f"{fmt(entry['total_vs_agent_records'])} | "
            f"{fmt(entry['total_vs_agent_share'])} | "
            f"{int(entry['periods_with_commits'])}"
        )
    return "\n".join(lines)


def plot_per_developer(
    period_keys: list[date],
    per_developer: dict[str, dict[date, PeriodStats]],
    granularity: str,
    output_path: Path,
) -> None:
    handles = sorted(per_developer)
    if not handles:
        raise SystemExit("No developers to plot.")

    ncols = 3
    nrows = math.ceil(len(handles) / ncols)
    labels = display_labels(period_keys, granularity)
    xs = list(range(len(period_keys)))
    correlations = per_developer_correlations(period_keys, per_developer)

    bar_color = "#264653"
    line_color = "#d62828"

    fig, axes = plt.subplots(
        nrows, ncols, figsize=(5.0 * ncols, 2.6 * nrows), sharex=True
    )
    axes_flat = axes.flatten() if hasattr(axes, "flatten") else [axes]

    for index, handle in enumerate(handles):
        ax = axes_flat[index]
        row = per_developer[handle]
        totals = [row[key].total_commits for key in period_keys]
        agents = [row[key].agent_commit_records for key in period_keys]
        raw_r = correlations[handle]["total_vs_agent_records"]

        ax.bar(xs, totals, color=bar_color, alpha=0.85)
        ax.plot(xs, agents, color=line_color, marker="o", markersize=3, linewidth=1.6)
        title = handle if math.isnan(raw_r) else f"{handle}  (r = {raw_r:.2f})"
        ax.set_title(title, fontsize=10)
        ax.set_ylim(bottom=0)
        ax.grid(axis="y", alpha=0.25)
        ax.tick_params(labelsize=7)

    # Hide any unused subplots.
    for ax in axes_flat[len(handles):]:
        ax.set_visible(False)

    # Label only the bottom row with period labels, shared across the grid.
    for ax in axes_flat[: len(handles)]:
        ax.set_xticks(xs)
        ax.set_xticklabels(labels, rotation=90, fontsize=6)
        ax.set_xlim(-0.5, len(xs) - 0.5)

    fig.suptitle(
        "Per-developer co-evolution of total commits (bars) and agent-signaled "
        "commit records (line)",
        fontsize=12,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.97))
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
    parser.add_argument(
        "--per-developer",
        action="store_true",
        help=(
            "Also render a per-developer panel grid, one subplot per developer, "
            "showing the same co-evolution series."
        ),
    )
    parser.add_argument(
        "--per-developer-output",
        type=Path,
        default=DEFAULT_PER_DEVELOPER_OUTPUT,
        help=(
            "PNG output path for the per-developer grid "
            f"(default: {DEFAULT_PER_DEVELOPER_OUTPUT.relative_to(REPO_ROOT)})."
        ),
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
    period_keys, per_developer, sampled_handles = load_stats_by_developer(
        granularity, start, end
    )
    stats = [
        combine_period(per_developer, key, sampled_handles, granularity)
        for key in period_keys
    ]
    summary_text = summarize(stats, granularity, sampled_handles, start, end)
    per_developer_text = format_per_developer_correlations(
        per_developer_correlations(period_keys, per_developer), granularity
    )

    output_path = resolve_output(args.output)
    summary_path = resolve_output(args.summary_output)
    plot(stats, granularity, output_path)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(summary_text + "\n\n" + per_developer_text + "\n")

    print(summary_text)
    print()
    print(per_developer_text)
    print()
    print(f"Figure saved to {output_path}")
    print(f"Summary saved to {summary_path}")

    if args.per_developer:
        per_developer_path = resolve_output(args.per_developer_output)
        plot_per_developer(period_keys, per_developer, granularity, per_developer_path)
        print(f"Per-developer figure saved to {per_developer_path}")


if __name__ == "__main__":
    main()
