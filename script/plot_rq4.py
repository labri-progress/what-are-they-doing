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


def _make_subplots_grid(n: int, figsize=(12, 8)):
    import math
    ncols = int(math.ceil(math.sqrt(n)))
    nrows = int(math.ceil(n / ncols))
    fig, axes = plt.subplots(nrows=nrows, ncols=ncols, figsize=(figsize[0], figsize[1] * nrows / 2))
    axes = axes.flatten() if hasattr(axes, 'flatten') else [axes]
    return fig, axes[:n]


def plot_active_repos_subplots(df_week: pd.DataFrame, out: Path, color_map: dict | None = None):
    """Bar subplots: active_repos_ge2 per `iso_week` for each developer."""
    df = df_week.copy()
    if df.empty:
        print("No weekly data for active repos; skipping subplots.")
        return
    developers = sorted(df['developer'].unique())
    # full week range
    all_weeks = sorted(df_week['iso_week'].unique(), key=lambda s: tuple(int(x) for x in s.split("-")))
    # ensure we have a color_map for all developers
    if color_map is None:
        palette = sns.color_palette("colorblind", n_colors=len(developers))
        color_map = {dev: palette[i] for i, dev in enumerate(developers)}

    fig, axes = _make_subplots_grid(len(developers), figsize=(12, 9))
    for ax, dev in zip(axes, developers):
        series = df[df.developer == dev].groupby('iso_week', as_index=True)['active_repos_ge2'].mean()
        d = series.reindex(all_weeks).fillna(0)
        ax.bar(range(len(all_weeks)), d.values, color=color_map[dev])
        ax.set_title(dev)
        # show ticks every 4th week and format as YYYY-MM using week_start_iso where available
        ticks = list(range(0, len(all_weeks), 4))
        week_label_map = {row['iso_week']: pd.to_datetime(row['week_start_iso']).strftime('%Y-%m') for _, row in df_week[['iso_week', 'week_start_iso']].drop_duplicates().iterrows()}
        labels = [week_label_map.get(all_weeks[i], all_weeks[i]) for i in ticks]
        ax.set_xticks(ticks)
        ax.set_xticklabels(labels, rotation=45)
        ax.set_ylabel('Active repos')
        ax.grid(axis='y')
    plt.tight_layout()
    plt.savefig(out / 'active_repos_per_week_subplots.pdf')
    plt.close()


def plot_gini_subplots(df_week: pd.DataFrame, out: Path, color_map: dict | None = None):
    """Bar subplots: weekly Gini per developer."""
    df = df_week.copy()
    if df.empty:
        print("No weekly data for gini; skipping subplots.")
        return
    developers = sorted(df['developer'].unique())
    all_weeks = sorted(df_week['iso_week'].unique(), key=lambda s: tuple(int(x) for x in s.split("-")))
    if color_map is None:
        palette = sns.color_palette("colorblind", n_colors=len(developers))
        color_map = {dev: palette[i] for i, dev in enumerate(developers)}

    fig, axes = _make_subplots_grid(len(developers), figsize=(12, 9))
    for ax, dev in zip(axes, developers):
        series = df[df.developer == dev].groupby('iso_week', as_index=True)['gini'].mean()
        d = series.reindex(all_weeks).fillna(0)
        ax.bar(range(len(all_weeks)), d.values, color=color_map[dev])
        ax.set_ylim(0, 1)
        ax.set_title(dev)
        ticks = list(range(0, len(all_weeks), 4))
        week_label_map = {row['iso_week']: pd.to_datetime(row['week_start_iso']).strftime('%Y-%m') for _, row in df_week[['iso_week', 'week_start_iso']].drop_duplicates().iterrows()}
        labels = [week_label_map.get(all_weeks[i], all_weeks[i]) for i in ticks]
        ax.set_xticks(ticks)
        ax.set_xticklabels(labels, rotation=45)
        ax.set_ylabel('Gini')
        ax.grid(axis='y')
    plt.tight_layout()
    plt.savefig(out / 'gini_per_week_subplots.pdf')
    plt.close()


def plot_switches_day_subplots(df_commits: pd.DataFrame, out: Path, color_map: dict | None = None):
    """Bar subplots: switches per day for each developer (computed from commits).
    Ensure full week coverage by reindexing days across the global date range and
    show x-labels monthly to avoid overcrowding."""
    grouped = compute_switches_per_day_from_commits(df_commits)
    if grouped.empty:
        print("No daily switches computed; skipping day subplots.")
        return
    # full date range from commits
    df_commits['committed_at_iso'] = pd.to_datetime(df_commits['committed_at_iso'], utc=True)
    all_days = pd.date_range(start=df_commits['committed_at_iso'].dt.date.min(), end=df_commits['committed_at_iso'].dt.date.max(), freq='D')
    all_days_str = [d.strftime('%Y-%m-%d') for d in all_days]

    developers = sorted(grouped['developer'].unique())
    if color_map is None:
        palette = sns.color_palette("colorblind", n_colors=len(developers))
        color_map = {dev: palette[i] for i, dev in enumerate(developers)}

    fig, axes = _make_subplots_grid(len(developers), figsize=(14, 10))
    for ax, dev in zip(axes, developers):
        series = grouped[grouped.developer == dev].groupby('day', as_index=True)['switches_in_day'].sum()
        d = series.reindex(all_days_str).fillna(0)
        x = list(range(len(all_days_str)))
        ax.bar(x, d.values, color=color_map[dev], width=1.0)
        ax.set_title(dev)
        # label only the first day of each month (e.g., "Sep 2025"); fallback to sparse labels
        ticks = [i for i, day in enumerate(all_days_str) if day.endswith('-01')]
        if not ticks:
            step = max(1, len(all_days_str) // 60)
            ticks = list(range(0, len(all_days_str), step))
            labels = [pd.to_datetime(all_days_str[i]).strftime('%Y-%m') for i in ticks]
        else:
            labels = [pd.to_datetime(all_days_str[i]).strftime('%Y-%m') for i in ticks]
        ax.set_xticks(ticks)
        ax.set_xticklabels(labels, rotation=45, fontsize=8)
        ax.set_xlim(-0.5, len(all_days_str) - 0.5)
        ax.set_ylabel('Switches/day')
        ax.grid(axis='y')
    plt.tight_layout()
    plt.savefig(out / 'switches_per_day_subplots.pdf')
    plt.close()


def plot_switches_session_subplots(df_sessions: pd.DataFrame, out: Path, color_map: dict | None = None):
    """Bar subplots: switches per session for each developer (sessions on x-axis)."""
    df = df_sessions.copy()
    if df.empty:
        print("No session data; skipping session subplots.")
        return
    df['start_iso'] = pd.to_datetime(df['start_iso'], utc=True)
    developers = sorted(df['developer'].unique())
    if color_map is None:
        palette = sns.color_palette("colorblind", n_colors=len(developers))
        color_map = {dev: palette[i] for i, dev in enumerate(developers)}

    fig, axes = _make_subplots_grid(len(developers), figsize=(14, 10))
    for ax, dev in zip(axes, developers):
        d = df[df.developer == dev].sort_values('start_iso')
        if d.empty:
            ax.set_visible(False)
            continue
        x = list(range(len(d)))
        ax.bar(x, d['switches_in_session'].values, color=color_map[dev], width=0.9)
        ax.set_title(dev)
        # sparse tick labels showing session start datetimes every N sessions (YYYY-MM)
        step = max(1, len(d) // 10)
        ticks = list(range(0, len(d), step))
        labels = [d['start_iso'].dt.strftime('%Y-%m').iloc[i] for i in ticks]
        ax.set_xticks(ticks)
        ax.set_xticklabels(labels, rotation=45)
        ax.set_xlim(-0.5, len(d) - 0.5)
        ax.set_ylabel('Switches/session')
        ax.grid(axis='y')
    plt.tight_layout()
    plt.savefig(out / 'switches_per_session_subplots.pdf')
    plt.close()


def _plot_row_for_developers(fig_type: str, developers: list[str], df_week: pd.DataFrame, df_commits: pd.DataFrame, df_sessions: pd.DataFrame, out: Path, color_map: dict):
    """Helper to plot a single-row figure for a small list of developers.
    fig_type: one of 'active_repos', 'gini', 'switches_day', 'switches_session'"""
    n = len(developers)
    if n == 0:
        return
    fig, axes = plt.subplots(1, n, figsize=(4 * n, 4), squeeze=False)
    axes = axes[0]

    # full week/day ranges
    all_weeks = sorted(df_week['iso_week'].unique(), key=lambda s: tuple(int(x) for x in s.split('-'))) if (df_week is not None and 'iso_week' in df_week.columns) else []
    df_commits_local = df_commits.copy()
    df_commits_local['committed_at_iso'] = pd.to_datetime(df_commits_local['committed_at_iso'], utc=True)
    all_days = pd.date_range(start=df_commits_local['committed_at_iso'].dt.date.min(), end=df_commits_local['committed_at_iso'].dt.date.max(), freq='D')
    all_days_str = [d.strftime('%Y-%m-%d') for d in all_days]

    for ax, dev in zip(axes, developers):
        if fig_type == 'active_repos':
            series = df_week[df_week.developer == dev].groupby('iso_week', as_index=True)['active_repos_ge2'].mean()
            d = series.reindex(all_weeks).fillna(0)
            ax.bar(range(len(all_weeks)), d.values, color=color_map.get(dev))
            ax.set_title(dev)
            ticks = list(range(0, len(all_weeks), 4))
            week_label_map = {row['iso_week']: pd.to_datetime(row['week_start_iso']).strftime('%Y-%m') for _, row in df_week[['iso_week', 'week_start_iso']].drop_duplicates().iterrows()}
            ax.set_xticks(ticks)
            ax.set_xticklabels([week_label_map.get(all_weeks[i], all_weeks[i]) for i in ticks], rotation=45)
            ax.set_ylabel('Active repos')
            ax.grid(axis='y')

        elif fig_type == 'gini':
            series = df_week[df_week.developer == dev].groupby('iso_week', as_index=True)['gini'].mean()
            d = series.reindex(all_weeks).fillna(0)
            ax.bar(range(len(all_weeks)), d.values, color=color_map.get(dev))
            ax.set_ylim(0, 1)
            ax.set_title(dev)
            ticks = list(range(0, len(all_weeks), 4))
            week_label_map = {row['iso_week']: pd.to_datetime(row['week_start_iso']).strftime('%Y-%m') for _, row in df_week[['iso_week', 'week_start_iso']].drop_duplicates().iterrows()}
            ax.set_xticks(ticks)
            ax.set_xticklabels([week_label_map.get(all_weeks[i], all_weeks[i]) for i in ticks], rotation=45)
            ax.set_ylabel('Gini')
            ax.grid(axis='y')

        elif fig_type == 'switches_day':
            grouped = compute_switches_per_day_from_commits(df_commits)
            series = grouped[grouped.developer == dev].groupby('day', as_index=True)['switches_in_day'].sum()
            d = series.reindex(all_days_str).fillna(0)
            x = list(range(len(all_days_str)))
            ax.bar(x, d.values, color=color_map.get(dev), width=1.0)
            ax.set_title(dev)
            # label only first day of month
            ticks = [i for i, day in enumerate(all_days_str) if day.endswith('-01')]
            if not ticks:
                step = max(1, len(all_days_str) // 60)
                ticks = list(range(0, len(all_days_str), step))
                labels = [pd.to_datetime(all_days_str[i]).strftime('%Y-%m') for i in ticks]
            else:
                labels = [pd.to_datetime(all_days_str[i]).strftime('%Y-%m') for i in ticks]
            ax.set_xticks(ticks)
            ax.set_xticklabels(labels, rotation=45, fontsize=8)
            ax.set_xlim(-0.5, len(all_days_str) - 0.5)
            ax.set_ylabel('Switches/day')
            ax.grid(axis='y')

        elif fig_type == 'switches_session':
            df = df_sessions.copy()
            df['start_iso'] = pd.to_datetime(df['start_iso'], utc=True)
            d = df[df.developer == dev].sort_values('start_iso')
            x = list(range(len(d)))
            ax.bar(x, d['switches_in_session'].values, color=color_map.get(dev), width=0.9)
            ax.set_title(dev)
            step = max(1, len(d) // 10)
            ticks = list(range(0, len(d), step))
            labels = [d['start_iso'].dt.strftime('%Y-%m').iloc[i] for i in ticks] if len(d) else []
            ax.set_xticks(ticks)
            ax.set_xticklabels(labels, rotation=45, fontsize=8)
            ax.set_xlim(-0.5, len(d) - 0.5)
            ax.set_ylabel('Switches/session')
            ax.grid(axis='y')

    plt.tight_layout()
    filename = f"{fig_type}_four_devs_row.pdf"
    plt.savefig(out / filename)
    plt.close()


def plot_four_devs_row_plots(df_week: pd.DataFrame, df_commits: pd.DataFrame, df_sessions: pd.DataFrame, out: Path, devs: list[str], color_map: dict):
    # create the four requested figures (one row with four subplots each)
    _plot_row_for_developers('active_repos', devs, df_week, df_commits, df_sessions, out, color_map)
    _plot_row_for_developers('gini', devs, df_week, df_commits, df_sessions, out, color_map)
    _plot_row_for_developers('switches_day', devs, df_week, df_commits, df_sessions, out, color_map)
    _plot_row_for_developers('switches_session', devs, df_week, df_commits, df_sessions, out, color_map)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--export-dir", type=Path, default=Path("data/exports-RQ4"))
    parser.add_argument("--out-dir", type=Path, default=Path("data/exports-RQ4/plots"))
    parser.add_argument("--show", action="store_true", help="Show plots interactively")
    args = parser.parse_args()

    export_dir: Path = args.export_dir
    out_dir: Path = args.out_dir
    ensure_outdir(out_dir)

    df_week = load_weekly(export_dir)
    df_sessions = load_sessions(export_dir)
    df_commits = load_commits(export_dir)

    devs_week = set(df_week['developer'].unique()) if 'developer' in df_week.columns else set()
    devs_sess = set(df_sessions['developer'].unique()) if 'developer' in df_sessions.columns else set()
    devs_comm = set(df_commits['developer'].unique()) if 'developer' in df_commits.columns else set()
    devs_all = sorted(devs_week.union(devs_sess).union(devs_comm))
    palette = sns.color_palette("colorblind", n_colors=len(devs_all))
    color_map = {dev: palette[i] for i, dev in enumerate(devs_all)}

    plot_active_repos_subplots(df_week, out_dir, color_map=color_map)
    plot_gini_subplots(df_week, out_dir, color_map=color_map)
    plot_switches_day_subplots(df_commits, out_dir, color_map=color_map)
    plot_switches_session_subplots(df_sessions, out_dir, color_map=color_map)

    # Also create compact one-row figures for the requested four developers
    chosen_devs = ["Dicklesworthstone", "steipete", "teamchong", "steveyegge"]
    plot_four_devs_row_plots(df_week, df_commits, df_sessions, out_dir, chosen_devs, color_map)

    print(f"Saved plots to {out_dir}")
    if args.show:
        import os
        # open images using xdg-open if available
        for p in out_dir.iterdir():
            if p.suffix.lower() in (".png", ".jpg", ".jpeg"):
                os.system(f"xdg-open {p} &")


if __name__ == "__main__":
    main()
