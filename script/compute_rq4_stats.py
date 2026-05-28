#!/usr/bin/env python3
"""Compute summary statistics for RQ4 (repository switching).

Reads CSVs in an exports directory and writes per-developer summary CSVs
and a short overall report.
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np
import pandas as pd


def bootstrap_ci(data: np.ndarray, n_boot=1000, alpha=0.05):
    boots = []
    n = len(data)
    for _ in range(n_boot):
        sample = np.random.choice(data, size=n, replace=True)
        boots.append(sample.mean())
    low = np.percentile(boots, 100 * (alpha / 2))
    high = np.percentile(boots, 100 * (1 - alpha / 2))
    return low, high


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--exports", default="data/exports-RQ4", help="exports dir")
    p.add_argument("--out", default=None, help="output dir (defaults to exports)")
    args = p.parse_args(argv)

    exports = pathlib.Path(args.exports)
    out_dir = pathlib.Path(args.out or args.exports)
    out_dir.mkdir(parents=True, exist_ok=True)

    weekly_fp = exports / "weekly_summary.csv"
    day_fp = exports / "day_summary.csv"
    sessions_fp = exports / "sessions.csv"

    if not weekly_fp.exists():
        print("missing", weekly_fp, file=sys.stderr)
        return 2

    weekly = pd.read_csv(weekly_fp)
    day = pd.read_csv(day_fp)
    sessions = pd.read_csv(sessions_fp)

    # Ensure numeric
    weekly["active_repos"] = pd.to_numeric(weekly.get("active_repos_ge2", weekly.get("active_repos", "")), errors="coerce").fillna(0).astype(int)
    weekly["gini"] = pd.to_numeric(weekly["gini"], errors="coerce")

    day["switches"] = pd.to_numeric(day.get("switches", 0), errors="coerce").fillna(0).astype(int)
    sessions["switches_in_session"] = pd.to_numeric(sessions.get("switches_in_session", 0), errors="coerce").fillna(0).astype(int)

    # 1) Average number of repositories per week (across all developer-weeks) + 95% CI
    active_vals = weekly["active_repos"].values
    mean_active = active_vals.mean()
    ci_low, ci_high = bootstrap_ci(active_vals, n_boot=2000)

    # 2) Per-developer min/max/mean/median active repos per week
    perdev_active = (
        weekly.groupby("developer")["active_repos"].agg(["min", "max", "mean", "median"]).reset_index()
    )
    perdev_active.to_csv(out_dir / "rq4_active_repos_per_developer.csv", index=False)

    # 3) Median Gini of developer-weeks (overall), plus per-developer median
    overall_median_gini = weekly["gini"].median()
    perdev_gini = weekly.groupby("developer")["gini"].median().reset_index().rename(columns={"gini": "median_gini"})
    perdev_gini.to_csv(out_dir / "rq4_gini_per_developer.csv", index=False)

    # 4) Day-level repo switch stats: per-developer max/min/median and overall maxima
    perdev_day_switches = (
        day.groupby("developer")["switches"].agg(["min", "median", "max"]).reset_index()
    )
    perdev_day_switches.to_csv(out_dir / "rq4_day_switches_per_developer.csv", index=False)

    # 5) Session-level: per-developer median switches per session
    perdev_session = (
        sessions.groupby("developer")["switches_in_session"].median().reset_index().rename(columns={"switches_in_session": "median_switches_per_session"})
    )
    perdev_session.to_csv(out_dir / "rq4_session_switches_per_developer.csv", index=False)

    # 6) Additional handy outputs
    # Top daily switchers
    top_daily = perdev_day_switches.sort_values("max", ascending=False).head(20)
    top_daily.to_csv(out_dir / "rq4_top_daily_switchers.csv", index=False)

    # Write a short summary
    summary_lines = [
        f"RQ4 summary generated from exports: {exports}",
        "",
        f"Average active repositories per developer-week: {mean_active:.2f}",
        f"95% bootstrap CI (mean): [{ci_low:.2f}, {ci_high:.2f}]",
        "",
        f"Overall median Gini (across developer-weeks): {overall_median_gini:.3f}",
        "",
        "Top daily switches (developer, max):",
    ]

    for _, r in top_daily.iterrows():
        summary_lines.append(f" - {r['developer']}: {int(r['max'])}")

    summary_lines.append("")
    summary_lines.append("Files written:")
    summary_lines.append(f" - {out_dir / 'rq4_active_repos_per_developer.csv'}")
    summary_lines.append(f" - {out_dir / 'rq4_gini_per_developer.csv'}")
    summary_lines.append(f" - {out_dir / 'rq4_day_switches_per_developer.csv'}")
    summary_lines.append(f" - {out_dir / 'rq4_session_switches_per_developer.csv'}")
    summary_lines.append(f" - {out_dir / 'rq4_top_daily_switchers.csv'}")

    (out_dir / "rq4_summary.txt").write_text("\n".join(summary_lines))

    print("\n".join(summary_lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
