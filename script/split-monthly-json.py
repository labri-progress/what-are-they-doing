#!/usr/bin/env python3
"""Split a monthly developer JSON file into two halves by data size."""

import json
import sys
from pathlib import Path


def split_file(input_path: str) -> None:
    path = Path(input_path)
    with open(path) as f:
        data = json.load(f)

    days = data["days"]
    day_keys = sorted(days.keys())

    sizes = {k: len(json.dumps(days[k])) for k in day_keys}
    total = sum(sizes.values())
    half = total / 2

    accumulated = 0
    split_idx = len(day_keys) // 2
    for i, k in enumerate(day_keys):
        accumulated += sizes[k]
        if accumulated >= half:
            split_idx = i + 1
            break

    keys1 = day_keys[:split_idx]
    keys2 = day_keys[split_idx:]

    part1 = {**data, "days": {k: days[k] for k in keys1}}
    part2 = {**data, "days": {k: days[k] for k in keys2}}

    out1 = path.with_name(path.stem + "-1.json")
    out2 = path.with_name(path.stem + "-2.json")

    with open(out1, "w") as f:
        json.dump(part1, f)
    print(f"Wrote {out1} ({out1.stat().st_size / 1e6:.1f} MB, days {keys1[0]}..{keys1[-1]})")

    with open(out2, "w") as f:
        json.dump(part2, f)
    print(f"Wrote {out2} ({out2.stat().st_size / 1e6:.1f} MB, days {keys2[0]}..{keys2[-1]})")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <input.json>")
        sys.exit(1)
    split_file(sys.argv[1])
