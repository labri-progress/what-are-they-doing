#!/usr/bin/env python3
"""Plot aggregate commit counts for all tracked developers as a bar chart.

The script reads per-developer monthly snapshots from data/<handle>-YYYY-MM.json,
expands them to daily counts, and then aggregates the combined series by day,
week, or month.

Examples:
    python3 script/plot-aggregate-commits.py
    python3 script/plot-aggregate-commits.py --granularity day
    python3 script/plot-aggregate-commits.py --granularity month --output figures/all-commits-monthly.png
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO_ROOT = Path(__file__).parent.parent
DATA_DIR = REPO_ROOT / "data"
DEVELOPERS_FILE = REPO_ROOT / "developers.json"
DEFAULT_OUTPUT = REPO_ROOT / "figures" / "aggregate-commits-over-time.png"


def parse_day(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"Invalid date '{value}'. Expected YYYY-MM-DD."
        ) from exc


def load_tracked_handles() -> list[str]:
    developers = json.loads(DEVELOPERS_FILE.read_text())
    return [entry["handle"] for entry in developers]


def load_daily_totals(handles: list[str]) -> dict[date, int]:
    daily_totals: Counter[date] = Counter()

    for handle in handles:
        for path in sorted(DATA_DIR.glob(f"{handle}-????-??.json")):
            payload = json.loads(path.read_text())
            for day_str, day_info in payload.get("days", {}).items():
                daily_totals[date.fromisoformat(day_str)] += day_info.get("total_count", 0)

    return dict(sorted(daily_totals.items()))


def choose_granularity(day_count: int, requested: str) -> str:
    if requested != "auto":
        return requested
    if day_count <= 45:
        return "day"
    if day_count <= 240:
        return "week"
    return "month"


def aggregate_counts(
    daily_totals: dict[date, int], granularity: str, start: date, end: date
) -> list[tuple[date, int]]:
    period_totals: Counter[date] = Counter()
    current = start

    # Fill gaps so the chart reflects the whole considered period.
    while current <= end:
        count = daily_totals.get(current, 0)
        if granularity == "day":
            key = current
        elif granularity == "week":
            key = current - timedelta(days=current.weekday())
        elif granularity == "month":
            key = current.replace(day=1)
        else:
            raise ValueError(f"Unsupported granularity: {granularity}")
        period_totals[key] += count
        current += timedelta(days=1)

    return sorted(period_totals.items())


def format_labels(periods: list[tuple[date, int]], granularity: str) -> list[str]:
    if granularity == "day":
        return [bucket.strftime("%Y-%m-%d") for bucket, _ in periods]
    if granularity == "week":
        return [f"Week of {bucket.strftime('%Y-%m-%d')}" for bucket, _ in periods]
    return [bucket.strftime("%Y-%m") for bucket, _ in periods]


def plot_bars(
    periods: list[tuple[date, int]],
    granularity: str,
    handles: list[str],
    start: date,
    end: date,
    output_path: Path,
) -> None:
    labels = format_labels(periods, granularity)
    counts = [count for _, count in periods]
    positions = list(range(len(periods)))

    width = max(10, min(18, len(periods) * 0.45))
    fig, ax = plt.subplots(figsize=(width, 6))
    bars = ax.bar(positions, counts, color="#2f6db2", edgecolor="#1b3f69", linewidth=0.8)

    title_granularity = {
        "day": "Daily",
        "week": "Weekly",
        "month": "Monthly",
    }[granularity]
    ax.set_title(f"{title_granularity} GitHub Commits Across All Tracked Developers", fontweight="bold")
    ax.set_ylabel("Commits")
    ax.set_xlabel("Period")
    ax.set_xticks(positions)
    ax.set_xticklabels(labels, rotation=45, ha="right")
    ax.grid(axis="y", alpha=0.25)
    ax.set_axisbelow(True)

    total_commits = sum(counts)
    fig.text(
        0.5,
        0.98,
        f"{start.isoformat()} to {end.isoformat()} | {len(handles)} developers | {total_commits} commits",
        ha="center",
        va="top",
        fontsize=10,
    )

    if len(periods) <= 24:
        for bar, count in zip(bars, counts):
            ax.text(
                bar.get_x() + bar.get_width() / 2,
                bar.get_height(),
                str(count),
                ha="center",
                va="bottom",
                fontsize=8,
            )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plot aggregate commit counts for all tracked developers."
    )
    parser.add_argument(
        "--granularity",
        choices=("auto", "day", "week", "month"),
        default="auto",
        help="Aggregation level for the chart (default: auto).",
    )
    parser.add_argument(
        "--start",
        type=parse_day,
        help="First day to include (YYYY-MM-DD). Defaults to the earliest day in data/.",
    )
    parser.add_argument(
        "--end",
        type=parse_day,
        help="Last day to include (YYYY-MM-DD). Defaults to the latest day in data/.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Where to save the PNG chart (default: {DEFAULT_OUTPUT.relative_to(REPO_ROOT)}).",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    handles = load_tracked_handles()
    daily_totals = load_daily_totals(handles)

    if not daily_totals:
        raise SystemExit("No commit snapshots found under data/.")

    data_start = min(daily_totals)
    data_end = max(daily_totals)
    start = args.start or data_start
    end = args.end or data_end

    if start > end:
        raise SystemExit("--start must be on or before --end.")
    if end < data_start or start > data_end:
        raise SystemExit(
            f"Requested range {start.isoformat()}..{end.isoformat()} falls outside available data "
            f"{data_start.isoformat()}..{data_end.isoformat()}."
        )

    start = max(start, data_start)
    end = min(end, data_end)

    day_count = (end - start).days + 1
    granularity = choose_granularity(day_count, args.granularity)
    periods = aggregate_counts(daily_totals, granularity, start, end)

    if not periods:
        raise SystemExit("No commit data found for the selected range.")

    output_path = args.output if args.output.is_absolute() else REPO_ROOT / args.output
    plot_bars(periods, granularity, handles, start, end, output_path)
    print(
        f"Saved {granularity} aggregate chart to {output_path} "
        f"for {start.isoformat()}..{end.isoformat()}."
    )


if __name__ == "__main__":
    main()
