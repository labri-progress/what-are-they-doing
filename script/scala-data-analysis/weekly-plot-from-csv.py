#!/usr/bin/env python3
"""Plot per-developer stacked bar charts from Scala-generated CSV.

Reads:
- figures/agent-coevolution-periods.csv  (one row per developer/period/agent)

Outputs:
- One PNG per developer: figures/agent-coevolution-{handle}.png

Usage:
    python script/plot-from-csv.py
    python script/plot-from-csv.py --periods-csv figures/agent-coevolution-periods.csv
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
FIGURES_DIR = REPO_ROOT / "figures"
DEFAULT_PERIODS_CSV = FIGURES_DIR / "agent-coevolution-periods.csv"

# ── Colour palette (must match Scala script) ────────────────────────────────

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


# ── CSV reading ──────────────────────────────────────────────────────────────

def read_periods_csv(path: Path) -> dict[str, dict[str, dict[str, int]]]:
    """Return {developer: {period_iso: {agent: count}}}"""
    result: dict[str, dict[str, dict[str, int]]] = {}
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            dev = row["developer"]
            period = row["period_iso"]
            agent = row["agent"]
            count = int(row["count"])
            total = int(row["total_commits"])
            if dev not in result:
                result[dev] = {}
            if period not in result[dev]:
                result[dev][period] = {"__total__": total}
            result[dev][period][agent] = count
    return result


def summarize_periods(periods: dict[str, dict[str, int]]) -> dict[str, str]:
    total_snapshot = sum(data["__total__"] for data in periods.values())
    total_records = sum(
        sum(count for agent, count in data.items() if agent != "__total__")
        for data in periods.values()
    )
    agent_records = sum(
        sum(count for agent, count in data.items() if agent not in {"__total__", "no agent"})
        for data in periods.values()
    )
    agent_pct = (agent_records / total_records * 100.0) if total_records else 0.0
    return {
        "total_snapshot": f"{total_snapshot}",
        "total_records": f"{total_records}",
        "agent_records": f"{agent_records}",
        "agent_pct": f"{agent_pct:.1f}",
    }


# ── Plotting ─────────────────────────────────────────────────────────────────

def plot_developer(
    handle: str,
    periods: dict[str, dict[str, int]],
    summary: dict,
    output_path: Path,
) -> None:
    """Create a stacked bar chart for one developer from CSV data."""
    sorted_periods = sorted(periods.items(), key=lambda x: x[0])
    labels = [p[0] for p in sorted_periods]

    # Determine all agents present
    all_agents: set[str] = set()
    for _, data in sorted_periods:
        for agent in data.keys():
            if agent != "__total__":
                all_agents.add(agent)

    # Consistent ordering
    ordered_agents: list[str] = []
    for agent in AGENT_COLORS:
        if agent in all_agents and agent != "no agent":
            ordered_agents.append(agent)
    for agent in sorted(all_agents):
        if agent not in ordered_agents and agent != "no agent":
            ordered_agents.append(agent)
    if "no agent" in all_agents:
        ordered_agents.append("no agent")

    # Build data arrays
    data_by_agent: dict[str, list[int]] = {
        agent: [data.get(agent, 0) for _, data in sorted_periods]
        for agent in ordered_agents
    }

    xs = np.arange(len(labels))
    width = 0.75

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

    # Total commits line (same scale)
    totals = [data["__total__"] for _, data in sorted_periods]
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
        f"Agent Usage Over Time — @{handle}",
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

    # Summary text
    summary_text = (
        f"Total snapshot commits: {summary.get('total_snapshot', '?')}  |  "
        f"Embedded records: {summary.get('total_records', '?')}  |  "
        f"Agent-attributed: {summary.get('agent_records', '?')} ({summary.get('agent_pct', '?')}%)  |  "
        f"Periods: {len(sorted_periods)}"
    )
    fig.text(0.5, 0.01, summary_text, ha="center", va="bottom", fontsize=9)

    fig.tight_layout(rect=(0, 0.03, 1, 0.97))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


# ── CLI ──────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plot per-developer stacked bar charts from CSV data."
    )
    parser.add_argument(
        "--periods-csv",
        type=Path,
        default=DEFAULT_PERIODS_CSV,
        help=f"Path to periods CSV (default: {DEFAULT_PERIODS_CSV.relative_to(REPO_ROOT)}).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=FIGURES_DIR,
        help=f"Directory for PNG output (default: {FIGURES_DIR.relative_to(REPO_ROOT)}).",
    )
    parser.add_argument(
        "--developer",
        help="Only plot this developer (default: all in CSV).",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()

    if not args.periods_csv.exists():
        raise SystemExit(f"Periods CSV not found: {args.periods_csv}")
    periods_data = read_periods_csv(args.periods_csv)

    developers = list(periods_data.keys())
    if args.developer:
        if args.developer not in developers:
            raise SystemExit(f"Developer '{args.developer}' not found in CSV.")
        developers = [args.developer]

    for handle in developers:
        output_path = args.output_dir / f"agent-coevolution-{handle}.svg"
        plot_developer(handle, periods_data[handle], summarize_periods(periods_data[handle]), output_path)
        print(f"  @{handle} → {output_path}")

    print(f"\nDone. {len(developers)} chart(s) saved.")


if __name__ == "__main__":
    main()
