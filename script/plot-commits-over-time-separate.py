#!/usr/bin/env python3
"""Plot commits per month for all developers in developers.json.

Reads per-developer per-month JSON files from data/ folder
(named <handle>-<YYYY>-<MM>.json) and produces a line chart.
"""

import json
import glob
import re
import math
from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from datetime import date

REPO_ROOT = Path(__file__).parent.parent
DATA_DIR = REPO_ROOT / "data"
DEVELOPERS_FILE = REPO_ROOT / "developers.json"
OUTPUT_FILE = Path("figures/commits-over-time-separate.png")


def load_month_commit_count(filepath: Path) -> int:
    """Sum total_count across all days in a monthly data file."""
    data = json.loads(filepath.read_text())
    days = data.get("days", {})
    if isinstance(days, dict):
        return sum(day.get("total_count", 0) for day in days.values())
    return 0


def load_commits_per_developer() -> dict[str, dict[str, int]]:
    """Return {handle: {"YYYY-MM": count, ...}} from data/ files."""
    developers = json.loads(DEVELOPERS_FILE.read_text())
    handles = [dev["handle"] for dev in developers]

    result: dict[str, dict[str, int]] = {}

    for handle in handles:
        monthly: dict[str, int] = {}
        pattern = str(DATA_DIR / f"{handle}-????-??.json")
        for filepath in sorted(glob.glob(pattern)):
            m = re.search(r"-(\d{4}-\d{2})\.json$", filepath)
            if m:
                month_key = m.group(1)
                monthly[month_key] = load_month_commit_count(Path(filepath))
        if monthly:
            result[handle] = monthly

    return result


def main() -> None:
    commits = load_commits_per_developer()

    if not commits:
        print("No data found.")
        return

    all_months = sorted({m for monthly in commits.values() for m in monthly})
    month_dates = [date(int(m[:4]), int(m[5:7]), 1) for m in all_months]

    devs = sorted(commits.items())
    n = len(devs)
    ncols = min(n, 2)
    nrows = math.ceil(n / ncols)

    fig, axes = plt.subplots(
        nrows, ncols,
        figsize=(7 * ncols, 4 * nrows),
        squeeze=False,
    )
    fig.suptitle("GitHub Commits per Month", fontsize=15, fontweight="bold", y=1.01)

    for i, (handle, monthly) in enumerate(devs):
        ax = axes[i // ncols][i % ncols]
        counts = [monthly.get(m, 0) for m in all_months]
        ax.plot(month_dates, counts, marker="o", linewidth=2, color=f"C{i}")
        ax.fill_between(month_dates, counts, alpha=0.15, color=f"C{i}")
        ax.set_title(f"@{handle}", fontsize=12, fontweight="bold")
        ax.set_ylabel("Commits")
        ax.xaxis.set_major_formatter(mdates.DateFormatter("%b %Y"))
        ax.xaxis.set_major_locator(mdates.MonthLocator())
        plt.setp(ax.get_xticklabels(), rotation=45, ha="right")
        ax.grid(True, alpha=0.3)

    # Use the first unused slot for a stacked bar chart of all developers
    if n < nrows * ncols:
        ax = axes[n // ncols][n % ncols]
        x = list(range(len(all_months)))
        bottoms = [0] * len(all_months)
        for i, (handle, monthly) in enumerate(devs):
            counts = [monthly.get(m, 0) for m in all_months]
            ax.bar(x, counts, bottom=bottoms, label=f"@{handle}", color=f"C{i}")
            bottoms = [b + c for b, c in zip(bottoms, counts)]
        ax.set_title("All Developers (Stacked)", fontsize=12, fontweight="bold")
        ax.set_ylabel("Commits")
        labels = [date(int(m[:4]), int(m[5:7]), 1).strftime("%b %Y") for m in all_months]
        ax.set_xticks(x)
        ax.set_xticklabels(labels, rotation=45, ha="right")
        ax.legend(fontsize=8, loc="upper left")
        ax.grid(True, alpha=0.3, axis="y")
        # Hide any remaining unused axes beyond the stacked chart
        for j in range(n + 1, nrows * ncols):
            axes[j // ncols][j % ncols].set_visible(False)
    else:
        for j in range(n, nrows * ncols):
            axes[j // ncols][j % ncols].set_visible(False)

    plt.tight_layout()
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(OUTPUT_FILE, dpi=150, bbox_inches="tight")
    print(f"Chart saved to {OUTPUT_FILE}")
    plt.show()


if __name__ == "__main__":
    main()
