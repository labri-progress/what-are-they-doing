#!/usr/bin/env python3
"""Plot RQ4 visualizations from exporter CSVs in `data/exports-RQ4`.

Produces four plots (PNG) into an output directory:
- active_repos_per_week_boxplot.png
- gini_per_week_boxplot.png
- switches_per_session_boxplot.png
- switches_per_week_boxplot.png

Usage:
    python3 script/plot_rq4.py --export-dir data/exports-RQ4 --out-dir data/exports-RQ4/plots
"""
from __future__ import annotations

import argparse
from pathlib import Path
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt
from datetime import datetime
from typing import Optional
import os


def ensure_outdir(p: Path):
    p.mkdir(parents=True, exist_ok=True)


def load_weekly(export_dir: Path) -> pd.DataFrame:
    return pd.read_csv(export_dir / "weekly_summary.csv", parse_dates=["week_start_iso"])


def load_sessions(export_dir: Path) -> pd.DataFrame:
    df = pd.read_csv(export_dir / "sessions.csv", parse_dates=["start_iso", "end_iso"]) 
    return df


def load_commits(export_dir: Path) -> pd.DataFrame:
    df = pd.read_csv(export_dir / "commits.csv", parse_dates=["committed_at_iso"]) 
    # normalize column names if necessary
    if "committed_at_iso" not in df.columns and "committed_at" in df.columns:
        df["committed_at_iso"] = pd.to_datetime(df["committed_at"])
    return df


def compute_switches_per_week_from_commits(df_commits: pd.DataFrame) -> pd.DataFrame:
    # expects columns: developer, committed_at_iso, repo
    df = df_commits.copy()
    df["committed_at_iso"] = pd.to_datetime(df["committed_at_iso"], utc=True)
    df = df.sort_values(["developer", "committed_at_iso"])

    rows = []
    for dev, g in df.groupby("developer"):
        prev_week = None
        prev_repo = None
        counts = {}
        for _, row in g.iterrows():
            wk = row["committed_at_iso"].strftime("%G-%V")
            repo = row.get("repo") or "(unknown)"
            if wk != prev_week:
                prev_repo = None
                prev_week = wk
            if prev_repo is not None and repo != prev_repo:
                counts[wk] = counts.get(wk, 0) + 1
            prev_repo = repo
        for wk, cnt in counts.items():
            rows.append({"developer": dev, "iso_year_week": wk, "switches_in_week": cnt})

    if not rows:
        return pd.DataFrame(columns=["developer", "iso_year_week", "switches_in_week"])
    return pd.DataFrame(rows)


def plot_active_repos_week(df_week: pd.DataFrame, out: Path, author: str | None = None):
    d = df_week.copy()
    if author:
        d = d[d.developer == author]
    plt.figure(figsize=(8, 6))
    sns.boxplot(x="developer", y="active_repos_ge2", data=d)
    plt.xticks(rotation=45)
    plt.title("Active repositories per week (>=2 commits)")
    plt.tight_layout()
    plt.savefig(out / "active_repos_per_week_boxplot.png")
    plt.close()


def plot_gini_week(df_week: pd.DataFrame, out: Path, author: str | None = None):
    d = df_week.copy()
    if author:
        d = d[d.developer == author]
    plt.figure(figsize=(8, 6))
    sns.boxplot(x="developer", y="gini", data=d)
    plt.xticks(rotation=45)
    plt.title("Repository concentration (Gini) per week")
    plt.tight_layout()
    plt.savefig(out / "gini_per_week_boxplot.png")
    plt.close()


def plot_switches_per_session(df_sessions: pd.DataFrame, out: Path, author: str | None = None):
    d = df_sessions.copy()
    if author:
        d = d[d.developer == author]
    plt.figure(figsize=(8, 6))
    sns.boxplot(x="developer", y="switches_in_session", data=d)
    plt.xticks(rotation=45)
    plt.title("Repository switches per session")
    plt.tight_layout()
    plt.savefig(out / "switches_per_session_boxplot.png")
    plt.close()


def plot_switches_per_week(df_sessions: pd.DataFrame, out: Path, author: str | None = None):
    d = df_sessions.copy()
    # compute week key from start_iso
    d["start_iso"] = pd.to_datetime(d["start_iso"], utc=True)
    d["iso_year_week"] = d["start_iso"].dt.strftime("%G-%V")
    grouped = d.groupby(["developer", "iso_year_week"]).switches_in_session.sum().reset_index(name="switches_in_week")
    if author:
        grouped = grouped[grouped.developer == author]
    plt.figure(figsize=(10, 6))
    sns.boxplot(x="developer", y="switches_in_week", data=grouped)
    plt.xticks(rotation=45)
    plt.title("Repository switches per week (summed from sessions)")
    plt.tight_layout()
    plt.savefig(out / "switches_per_week_boxplot.png")
    plt.close()


def plot_active_repos_by_week(df_week: pd.DataFrame, out: Path, author: Optional[str] = None):
    d = df_week.copy()
    if author:
        d = d[d.developer == author]
    # pivot so each developer becomes a column and weeks are the index
    df = d[["developer", "iso_week", "active_repos_ge2"]].dropna()
    if df.empty:
        print("No weekly active-repos data available; skipping lineplot.")
        return
    
    sns.set_style("whitegrid")
    
    pivot = df.pivot_table(index="iso_week", columns="developer", values="active_repos_ge2", aggfunc="mean")
    pivot = pivot.sort_index()
    plt.figure(figsize=(14, 6))
    ax = plt.gca()
    pivot.plot(ax=ax, linewidth=1)
    plt.xticks(rotation=90)
    plt.ylabel("Active repositories")
    plt.xlabel("")
    #plt.legend(bbox_to_anchor=(1.05, 1), loc="upper left")
    plt.legend(loc="upper left")
    plt.tight_layout()
    plt.savefig(out / "active_repos_by_week_lineplot.pdf")
    plt.close()


def plot_gini_by_week(df_week: pd.DataFrame, out: Path, author: Optional[str] = None):
    d = df_week.copy()
    if author:
        d = d[d.developer == author]
    # pivot to have weeks x developers
    df = d[["developer", "iso_week", "gini"]]
    if df.empty:
        print("No weekly gini data available; skipping gini lineplot.")
        return
    pivot = df.pivot_table(index="iso_week", columns="developer", values="gini", aggfunc="mean")
    pivot = pivot.sort_index()
    # ensure all weeks present
    all_weeks = sorted(df_week["iso_week"].unique(), key=lambda s: tuple(int(x) for x in s.split("-")))
    pivot = pivot.reindex(all_weeks)
    pivot = pivot.fillna(0)

    sns.set_theme(style="whitegrid")
    sns.set_palette("colorblind")

    plt.figure(figsize=(14, 6))
    ax = plt.gca()
    pivot.plot(ax=ax, linewidth=1.25, marker='o', markersize=4)
    ax.set_ylim(0, 1)
    plt.xticks(rotation=90)
    plt.ylabel("Gini coefficient")
    plt.xlabel("ISO year-week")
    plt.title("Repository concentration (Gini) per week — per-developer time series")
    plt.legend(bbox_to_anchor=(1.05, 1), loc="upper left")
    plt.tight_layout()
    plt.savefig(out / "gini_by_week_lineplot.pdf")
    plt.close()


def plot_switches_by_week_commits(df_commits: pd.DataFrame, out: Path, author: Optional[str] = None):
    grouped = compute_switches_per_week_from_commits(df_commits)
    if author:
        grouped = grouped[grouped.developer == author]
    if grouped.empty:
        print("No switches computed from commits; skipping switches-by-week plot.")
        return
    plt.figure(figsize=(14, 6))
    sns.boxplot(x="iso_year_week", y="switches_in_week", data=grouped)
    plt.xticks(rotation=90)
    plt.title("Repository switches per week (from raw commits)")
    plt.tight_layout()
    plt.savefig(out / "switches_by_week_from_commits_boxplot.pdf")
    plt.close()


def plot_switches_by_week_line(df_commits: pd.DataFrame, df_week: pd.DataFrame, out: Path, author: Optional[str] = None):
    """Per-developer line plot of switches per week (computed from raw commits)."""
    grouped = compute_switches_per_week_from_commits(df_commits)
    if grouped.empty:
        print("No switches computed from commits; skipping lineplot.")
        return
    # pivot so weeks are rows and developers are columns
    pivot = grouped.pivot_table(index="iso_year_week", columns="developer", values="switches_in_week", aggfunc="sum")
    pivot = pivot.sort_index()

    # ensure all weeks present (use weekly summary if available)
    if df_week is not None and "iso_week" in df_week.columns:
        all_weeks = sorted(df_week["iso_week"].unique(), key=lambda s: tuple(int(x) for x in s.split("-")))
        pivot = pivot.reindex(all_weeks)
    # fill missing with zeros
    pivot = pivot.fillna(0)

    # styling
    sns.set_theme(style="whitegrid")
    sns.set_palette("colorblind")

    plt.figure(figsize=(14, 6))
    ax = plt.gca()
    pivot.plot(ax=ax, linewidth=1.5, marker="o", markersize=4)
    ax.set_ylim(bottom=0)
    plt.xticks(rotation=90)
    plt.ylabel("Repository switches per week")
    plt.xlabel("ISO year-week")
    plt.title("Repository switches per week — per-developer time series (log scale)")
    # use symmetric log scale to handle zeros while showing log-like behavior
    ax.set_yscale("symlog", linthresh=1, base=10)
    plt.legend(bbox_to_anchor=(1.05, 1), loc="upper left")
    plt.tight_layout()
    plt.savefig(out / "switches_by_week_lineplot.pdf")
    plt.close()


def compute_switches_per_day_from_commits(df_commits: pd.DataFrame) -> pd.DataFrame:
    df = df_commits.copy()
    df["committed_at_iso"] = pd.to_datetime(df["committed_at_iso"], utc=True)
    df = df.sort_values(["developer", "committed_at_iso"])

    rows = []
    for dev, g in df.groupby("developer"):
        prev_day = None
        prev_repo = None
        counts = {}
        for _, row in g.iterrows():
            day = row["committed_at_iso"].strftime("%Y-%m-%d")
            repo = row.get("repo") or "(unknown)"
            if day != prev_day:
                prev_repo = None
                prev_day = day
            if prev_repo is not None and repo != prev_repo:
                counts[day] = counts.get(day, 0) + 1
            prev_repo = repo
        for day, cnt in counts.items():
            rows.append({"developer": dev, "day": day, "switches_in_day": cnt})

    if not rows:
        return pd.DataFrame(columns=["developer", "day", "switches_in_day"])
    return pd.DataFrame(rows)


def plot_switches_by_day_line(df_commits: pd.DataFrame, out: Path, author: Optional[str] = None):
    grouped = compute_switches_per_day_from_commits(df_commits)
    if grouped.empty:
        print("No daily switches computed; skipping day-line plot.")
        return
    pivot = grouped.pivot_table(index="day", columns="developer", values="switches_in_day", aggfunc="sum")
    pivot = pivot.sort_index()
    # ensure full date range from commits
    all_days = sorted(pd.to_datetime(df_commits["committed_at_iso"], utc=True).dt.strftime("%Y-%m-%d").unique())
    pivot = pivot.reindex(all_days)
    pivot = pivot.fillna(0)

    sns.set_theme(style="whitegrid")
    sns.set_palette("colorblind")

    plt.figure(figsize=(14, 6))
    ax = plt.gca()
    pivot.plot(ax=ax, linewidth=1.0, marker='.', markersize=3)
    ax.set_ylim(bottom=0)
    plt.xticks(rotation=90)
    #ax.set_yscale("symlog", linthresh=1, base=10)
    plt.ylabel("Repository switches per day")
    plt.xlabel("Date")
    plt.title("Repository switches per day — per-developer time series")
    plt.legend(bbox_to_anchor=(1.05, 1), loc="upper left")
    plt.tight_layout()
    plt.savefig(out / "switches_by_day_lineplot.pdf")
    plt.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--export-dir", type=Path, default=Path("data/exports-RQ4"))
    parser.add_argument("--out-dir", type=Path, default=Path("data/exports-RQ4/plots"))
    parser.add_argument("--author", type=str, default=None, help="Filter to single developer")
    parser.add_argument("--show", action="store_true", help="Show plots interactively")
    args = parser.parse_args()

    export_dir: Path = args.export_dir
    out_dir: Path = args.out_dir
    ensure_outdir(out_dir)

    df_week = load_weekly(export_dir)
    df_sessions = load_sessions(export_dir)
    df_commits = load_commits(export_dir)

    #plot_active_repos_week(df_week, out_dir, args.author)
    #plot_gini_week(df_week, out_dir, args.author)
    #plot_switches_per_session(df_sessions, out_dir, args.author)
    #plot_switches_per_week(df_sessions, out_dir, args.author)

    # additional plots: weeks on x-axis (distribution across developers)
    plot_active_repos_by_week(df_week, out_dir, args.author)
    plot_gini_by_week(df_week, out_dir, args.author)
    plot_switches_by_week_commits(df_commits, out_dir, args.author)
    # also produce per-developer lineplot for switches per week
    plot_switches_by_week_line(df_commits, df_week, out_dir, args.author)
    # per-day switches lineplot
    plot_switches_by_day_line(df_commits, out_dir, args.author)

    print(f"Saved plots to {out_dir}")
    if args.show:
        import os
        # open images using xdg-open if available
        for p in out_dir.iterdir():
            if p.suffix.lower() in (".png", ".jpg", ".jpeg"):
                os.system(f"xdg-open {p} &")


if __name__ == "__main__":
    main()
