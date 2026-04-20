#!/usr/bin/env python3
"""Print a horizontal UTC-hour histogram for commits in a data JSON file.

Usage:
    python script/histogram-commit-time.py data/steipete-2025-12.json
"""

import json
import sys
from collections import Counter
from pathlib import Path

BAR_CHAR = "█"
MAX_BAR_WIDTH = 50


def load_commits(path: Path) -> list[str]:
    """Return all author-date strings from a single JSON file."""
    data = json.loads(path.read_text())
    dates = []
    for day_data in data.get("days", {}).values():
        for commit in day_data.get("commits", []):
            d = commit.get("commit", {}).get("author", {}).get("date")
            if d:
                dates.append(d)
    return dates


def parse_hour(date_str: str) -> int | None:
    """Extract UTC hour from an ISO 8601 string like '2025-12-01T14:32:00Z'."""
    try:
        # Works for both ...T14:32:00Z and ...T14:32:00+00:00
        time_part = date_str[11:13]
        return int(time_part)
    except (IndexError, ValueError):
        return None


def render(counts: Counter, total: int, label: str) -> None:
    max_count = max(counts.values(), default=1)
    scale = MAX_BAR_WIDTH / max_count

    print(f"Commit time histogram (UTC) — {label}")
    print(f"Total commits: {total}  |  peak hour: {counts.most_common(1)[0][0]:02d}:xx UTC")
    print()

    for hour in range(24):
        count = counts.get(hour, 0)
        bar = BAR_CHAR * round(count * scale)
        print(f"  {hour:02d}  │{bar:<{MAX_BAR_WIDTH}}  {count}")

    print()
    # Compact summary: label every 6 hours
    print("       00          06          12          18          23")


def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <data-file.json>", file=sys.stderr)
        sys.exit(1)

    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"File not found: {path}", file=sys.stderr)
        sys.exit(1)

    dates = load_commits(path)
    if not dates:
        print("No commits found.", file=sys.stderr)
        sys.exit(1)

    hours = [h for d in dates if (h := parse_hour(d)) is not None]
    counts: Counter = Counter(hours)

    render(counts, len(hours), path.stem)


if __name__ == "__main__":
    main()
