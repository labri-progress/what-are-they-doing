#!/usr/bin/env python3
"""Plot per-developer stacked bar charts of agent usage over time.

For each tracked developer, the script reads their monthly snapshots,
buckets commits by day/week/month, detects agents per commit, and produces
a stacked bar chart where each segment is a different agent (plus "no agent").

Outputs:
- One PNG per developer: figures/agent-coevolution-{handle}.png
- A text summary: figures/agent-coevolution-summary.txt

Usage:
    python script/plot-agent-commit-coevolution-per-dev.py
    python script/plot-agent-commit-coevolution-per-dev.py --granularity month
    python script/plot-agent-commit-coevolution-per-dev.py --developer steipete
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

# ── Reuse shared definitions from the aggregate script ───────────────────────

import importlib.util

_OLD_SCRIPT_PATH = Path(__file__).with_name("plot-agent-commit-coevolution.py")
_spec = importlib.util.spec_from_file_location(
    "plot_agent_commit_coevolution", _OLD_SCRIPT_PATH
)
_old_mod = importlib.util.module_from_spec(_spec)  # type: ignore
sys.modules["plot_agent_commit_coevolution"] = _old_mod
_spec.loader.exec_module(_old_mod)  # type: ignore

REPO_ROOT = _old_mod.REPO_ROOT
DATA_DIR = _old_mod.DATA_DIR
COMMITS_DIR = _old_mod.COMMITS_DIR
DEVELOPERS_FILE = _old_mod.DEVELOPERS_FILE
AGENTS_DIR = _old_mod.AGENTS_DIR
SNAPSHOT_RE = _old_mod.SNAPSHOT_RE
parse_day = _old_mod.parse_day
choose_granularity = _old_mod.choose_granularity
bucket_start = _old_mod.bucket_start
bucket_label = _old_mod.bucket_label
load_commit_detail = _old_mod.load_commit_detail
detect_agents = _old_mod.detect_agents

FIGURES_DIR = REPO_ROOT / "figures"
DEFAULT_SUMMARY = FIGURES_DIR / "agent-coevolution-summary.txt"

sys.path.insert(0, str(REPO_ROOT / "agent-mining"))
from heuristic import load_heuristics  # noqa: E402


# ── Colour palette (new-script specific) ─────────────────────────────────────

AGENT_COLORS = {
    "claude_code": "#e76f51",
    "cursor": "#2a9d8f",
    "copilot": "#264653",
    "codex": "#f4a261",
    "aider": "#9b5de5",
    "devin": "#00bbf9",
    "opencode": "#fee440",
    "windsurf": "#00f5d4",
    "no agent": "#e9ecef",
}


def agent_color(agent: str) -> str:
    return AGENT_COLORS.get(agent, "#adb5bd")


# ── Data classes (new-script specific) ───────────────────────────────────────

@dataclass
class PeriodStats:
    label: str
    total_commits: int = 0
    agent_counts: Counter[str] = field(default_factory=Counter)

    @property
    def commit_records(self) -> int:
        return sum(self.agent_counts.values())

    @property
    def agent_commit_records(self) -> int:
        return self.commit_records - self.agent_counts.get("no agent", 0)

    @property
    def agent_share(self) -> float:
        if self.commit_records == 0:
            return 0.0
        return self.agent_commit_records / self.commit_records * 100.0


@dataclass
class DeveloperStats:
    handle: str
    periods: dict[date, PeriodStats] = field(default_factory=dict)

    def sorted_periods(self) -> list[tuple[date, PeriodStats]]:
        return sorted(self.periods.items())


# ── Data collection (new-script specific) ────────────────────────────────────

def load_developers() -> list[dict[str, Any]]:
    return json.loads(DEVELOPERS_FILE.read_text())


def collect_developer_stats(
    handle: str,
    granularity: str,
    start: date | None,
    end: date | None,
    heuristics_by_agent: dict[str, list[Any]],
) -> DeveloperStats | None:
    """Load all monthly snapshots for one developer and bucket by period."""
    dev_stats = DeveloperStats(handle=handle)

    for path in sorted(DATA_DIR.glob(f"{handle}-????-??.json")):
        payload = json.loads(path.read_text())
        for day_str, day_info in payload.get("days", {}).items():
            day_value = date.fromisoformat(day_str)

            if start and day_value < start:
                continue
            if end and day_value > end:
                continue

            key = bucket_start(day_value, granularity)
            period = dev_stats.periods.setdefault(
                key, PeriodStats(label=bucket_label(key, granularity))
            )
            period.total_commits += int(day_info.get("total_count", 0))

            for commit in day_info.get("commits", []):
                detail = load_commit_detail(commit.get("sha", ""))
                agents = detect_agents(commit, detail, heuristics_by_agent)
                if agents:
                    for agent in agents:
                        period.agent_counts[agent] += 1
                else:
                    period.agent_counts["no agent"] += 1

    if not dev_stats.periods:
        return None
    return dev_stats


# ── Plotting (new-script specific) ───────────────────────────────────────────

def plot_developer_stacked(
    dev_stats: DeveloperStats,
    granularity: str,
    start: date,
    end: date,
    output_path: Path,
) -> None:
    """Create a stacked bar chart for one developer."""
    sorted_periods = dev_stats.sorted_periods()
    labels = [period.label for _, period in sorted_periods]

    # Determine all agents present across all periods for this developer
    all_agents: set[str] = set()
    for _, period in sorted_periods:
        all_agents.update(period.agent_counts.keys())

    # Consistent ordering: known agents first, then others, then "no agent" last
    ordered_agents: list[str] = []
    for agent in AGENT_COLORS:
        if agent in all_agents and agent != "no agent":
            ordered_agents.append(agent)
    for agent in sorted(all_agents):
        if agent not in ordered_agents and agent != "no agent":
            ordered_agents.append(agent)
    if "no agent" in all_agents:
        ordered_agents.append("no agent")

    # Build stacked data: one array per agent
    data_by_agent: dict[str, list[int]] = {
        agent: [period.agent_counts.get(agent, 0) for _, period in sorted_periods]
        for agent in ordered_agents
    }

    xs = np.arange(len(labels))
    width = 0.75
    if granularity == "day":
        width = 0.9
    elif granularity == "week":
        width = 0.8

    fig, ax = plt.subplots(figsize=(max(10, len(labels) * 0.55), 6.5))

    bottom = np.zeros(len(labels))
    for agent in ordered_agents:
        counts = np.array(data_by_agent[agent])
        if counts.sum() == 0:
            continue
        ax.bar(
            xs,
            counts,
            width,
            bottom=bottom,
            label=agent,
            color=agent_color(agent),
            edgecolor="white",
            linewidth=0.4,
        )
        bottom += counts

    # Total commits line (from snapshot total_count) — same scale as bars
    totals = [period.total_commits for _, period in sorted_periods]
    ax.plot(
        xs,
        totals,
        color="#212529",
        marker="D",
        markersize=3,
        linewidth=1.2,
        linestyle="--",
        label="total commits (snapshot)",
        zorder=5,
    )

    ax.set_title(
        f"Agent Usage Over Time — @{dev_stats.handle}",
        fontweight="bold",
        fontsize=13,
    )
    ax.set_ylabel("Commits")
    ax.set_xlabel("Period")
    ax.set_xticks(xs)
    ax.set_xticklabels(labels, rotation=45, ha="right", fontsize=8)
    ax.grid(axis="y", alpha=0.25)
    ax.set_axisbelow(True)

    ax.legend(loc="upper left", fontsize=8, framealpha=0.9)

    # Summary text below the plot
    total_records = sum(period.commit_records for _, period in sorted_periods)
    agent_records = sum(period.agent_commit_records for _, period in sorted_periods)
    agent_pct = (agent_records / total_records * 100) if total_records else 0.0
    total_snapshot = sum(period.total_commits for _, period in sorted_periods)

    summary_text = (
        f"Total snapshot commits: {total_snapshot:,}  |  "
        f"Embedded records: {total_records:,}  |  "
        f"Agent-attributed: {agent_records:,} ({agent_pct:.1f}%)  |  "
        f"Periods: {len(sorted_periods)}  |  "
        f"Granularity: {granularity}"
    )
    fig.text(0.5, 0.01, summary_text, ha="center", va="bottom", fontsize=9)

    fig.tight_layout(rect=(0, 0.03, 1, 0.97))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


# ── Summary generation (new-script specific) ─────────────────────────────────

def generate_summary(
    all_dev_stats: list[DeveloperStats],
    granularity: str,
    start: date,
    end: date,
) -> str:
    lines = [
        f"Agent Co-evolution Summary",
        f"{'=' * 50}",
        f"Range: {start.isoformat()} to {end.isoformat()}",
        f"Granularity: {granularity}",
        f"Developers: {len(all_dev_stats)}",
        "",
    ]

    for dev in all_dev_stats:
        periods = dev.sorted_periods()
        total_snapshot = sum(p.total_commits for _, p in periods)
        total_records = sum(p.commit_records for _, p in periods)
        agent_records = sum(p.agent_commit_records for _, p in periods)
        agent_pct = (agent_records / total_records * 100) if total_records else 0.0

        all_agents: Counter[str] = Counter()
        for _, p in periods:
            for agent, count in p.agent_counts.items():
                if agent != "no agent":
                    all_agents[agent] += count
        top_agent = all_agents.most_common(1)[0] if all_agents else ("-", 0)

        lines.append(f"@{dev.handle}")
        lines.append(f"  Snapshot commits : {total_snapshot:,}")
        lines.append(f"  Embedded records : {total_records:,}")
        lines.append(f"  Agent-attributed : {agent_records:,} ({agent_pct:.1f}%)")
        lines.append(f"  Top agent        : {top_agent[0]} ({top_agent[1]:,} commits)")
        lines.append(f"  Periods          : {len(periods)}")
        lines.append("")

    return "\n".join(lines)


# ── CLI ──────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plot per-developer stacked bar charts of agent usage over time."
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
        "--developer",
        help="Only process this developer (default: all tracked developers).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=FIGURES_DIR,
        help=f"Directory for PNG output (default: {FIGURES_DIR.relative_to(REPO_ROOT)}).",
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
    developers = load_developers()
    tracked_handles = {entry["handle"] for entry in developers}

    if args.developer:
        if args.developer not in tracked_handles:
            raise SystemExit(f"Developer '{args.developer}' not found in developers.json.")
        handles_to_process = [args.developer]
    else:
        handles_to_process = sorted(tracked_handles)

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
    heuristics_by_agent = load_heuristics(str(AGENTS_DIR))

    all_dev_stats: list[DeveloperStats] = []
    for handle in handles_to_process:
        dev_stats = collect_developer_stats(handle, granularity, start, end, heuristics_by_agent)
        if dev_stats is None:
            print(f"  No data for @{handle} in selected range, skipping.", file=sys.stderr)
            continue
        all_dev_stats.append(dev_stats)

        output_path = args.output_dir / f"agent-coevolution-{handle}.png"
        plot_developer_stacked(dev_stats, granularity, start, end, output_path)
        print(f"  @{handle} → {output_path}")

    if not all_dev_stats:
        raise SystemExit("No developer data found for the selected range.")

    summary_text = generate_summary(all_dev_stats, granularity, start, end)
    summary_path = args.summary_output
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(summary_text + "\n")

    print()
    print(summary_text)
    print()
    print(f"Summary saved to {summary_path}")


if __name__ == "__main__":
    main()
