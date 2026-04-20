#!/usr/bin/env python3
"""Quick analysis of commit messages using Conventional Commits conventions.

Usage:
    python script/analyze-commits.py data/steipete-2025-12.json
    python script/analyze-commits.py --all
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path

# Conventional commit pattern: type(scope)!: description
CC_RE = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?\s*:\s*(?P<desc>.+)",
    re.MULTILINE,
)

KNOWN_TYPES = {
    "feat", "fix", "chore", "docs", "style", "refactor",
    "test", "perf", "ci", "build", "revert", "wip",
}

BAR_CHAR = "█"
BAR_WIDTH = 40


def bar(count: int, total: int, max_count: int) -> str:
    filled = round(count / max_count * BAR_WIDTH) if max_count else 0
    pct = f"{count / total * 100:.0f}%" if total else "0%"
    block = BAR_CHAR * filled
    # Embed percentage inside the bar if it fits, otherwise append after
    label = f" {pct}"
    if filled >= len(label) + 1:
        return block[: filled - len(label)] + label
    return block + label


def load_messages(path: Path) -> list[str]:
    data = json.loads(path.read_text())
    messages = []
    for day_data in data.get("days", {}).values():
        for commit in day_data.get("commits", []):
            msg = commit.get("commit", {}).get("message", "")
            if msg:
                # Use only the subject line
                messages.append(msg.split("\n")[0].strip())
    return messages


def parse_message(msg: str) -> dict | None:
    m = CC_RE.match(msg)
    if not m:
        return None
    return {
        "type": m.group("type").lower(),
        "scope": m.group("scope"),
        "breaking": m.group("breaking") == "!",
        "desc": m.group("desc").strip(),
    }


def section(title: str) -> None:
    print(f"\n{title}")
    print("─" * len(title))


def print_bar_table(counter: Counter, top: int = 15) -> None:
    if not counter:
        print("  (none)")
        return
    total = sum(counter.values())
    max_count = counter.most_common(1)[0][1]
    for key, count in counter.most_common(top):
        b = bar(count, total, max_count)
        print(f"  {key:<20} {b:<{BAR_WIDTH + 5}}  {count}")


def _count_breaking_footer(path: Path) -> int:
    data = json.loads(path.read_text())
    count = 0
    for day_data in data.get("days", {}).values():
        for commit in day_data.get("commits", []):
            msg = commit.get("commit", {}).get("message", "")
            if "BREAKING CHANGE:" in msg or "BREAKING-CHANGE:" in msg:
                count += 1
    return count


def _run_analysis(messages: list[str], breaking_footer: int, label: str) -> None:
    parsed = [parse_message(m) for m in messages]
    conventional = [p for p in parsed if p is not None]
    non_conventional = [m for m, p in zip(messages, parsed) if p is None]
    breaking = [p for p in conventional if p["breaking"]]

    type_counts: Counter = Counter(p["type"] for p in conventional)
    scope_counts: Counter = Counter(
        p["scope"] for p in conventional if p["scope"]
    )
    unknown_types = Counter(
        t for t in type_counts if t not in KNOWN_TYPES
    )

    # ── Header ──────────────────────────────────────────────────────────────
    print(f"\nConventional Commit Analysis — {label}")
    print("=" * 55)
    pct = len(conventional) / len(messages) * 100 if messages else 0
    print(f"  Total commits     : {len(messages)}")
    print(f"  Conventional      : {len(conventional)}  ({pct:.0f}%)")
    print(f"  Non-conventional  : {len(non_conventional)}")
    print(f"  Breaking changes  : {len(breaking) + breaking_footer}")

    # ── By type ─────────────────────────────────────────────────────────────
    section(f"By type  (n={len(conventional)})")
    print_bar_table(type_counts)

    if unknown_types:
        section("Unknown types (not in Conventional Commits spec)")
        for t, n in unknown_types.most_common():
            print(f"  {t:<20} {n}")

    # ── By scope ────────────────────────────────────────────────────────────
    section(f"By scope  (n={sum(scope_counts.values())}, top 15)")
    print_bar_table(scope_counts)

    # ── Non-conventional samples ─────────────────────────────────────────────
    if non_conventional:
        section(f"Non-conventional samples  (first 10 of {len(non_conventional)})")
        for msg in non_conventional[:10]:
            print(f"  {msg[:80]}")

    print()


def analyze(path: Path) -> None:
    messages = load_messages(path)
    if not messages:
        print(f"\nNo commits found in {path.name}.")
        return
    _run_analysis(messages, _count_breaking_footer(path), path.stem)


def analyze_all(paths: list[Path]) -> None:
    all_messages: list[str] = []
    total_breaking_footer = 0
    for path in paths:
        all_messages.extend(load_messages(path))
        total_breaking_footer += _count_breaking_footer(path)
    if not all_messages:
        print("No commits found.")
        return
    _run_analysis(all_messages, total_breaking_footer, f"all ({len(paths)} files)")


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "--all":
        data_dir = Path(__file__).parent.parent / "data"
        paths = sorted(data_dir.glob("*.json"))
        if not paths:
            print(f"No JSON files found in {data_dir}", file=sys.stderr)
            sys.exit(1)
        analyze_all(paths)
    elif len(sys.argv) == 2:
        path = Path(sys.argv[1])
        if not path.is_file():
            print(f"File not found: {path}", file=sys.stderr)
            sys.exit(1)
        analyze(path)
    else:
        print(f"Usage: {sys.argv[0]} <data-file.json>", file=sys.stderr)
        print(f"       {sys.argv[0]} --all", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
