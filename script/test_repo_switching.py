#!/usr/bin/env python3
"""Clean assertion-based tests for `analyze-repo-switching.py` helpers.

Run: python3 script/test_repo_switching_clean.py
"""
from datetime import datetime, timedelta, timezone
from collections import Counter
import importlib.util
import sys


def load_module(path: str):
    spec = importlib.util.spec_from_file_location("ars_clean", path)
    mod = importlib.util.module_from_spec(spec)
    import sys as _sys
    _sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


def C(mod, sha: str, repo: str, dt: datetime):
    return mod.CommitRecord(sha=sha, repo=repo, committed_at=dt, day=dt.date().isoformat(), message="", url="")


def test_gini(mod):
    gini = mod.gini_coefficient_from_counts
    assert abs(gini(Counter({"a": 1, "b": 1, "c": 1})) - 0.0) < 1e-9
    assert abs(gini(Counter({"A": 9, "B": 1})) - 0.4) < 1e-9
    assert gini(Counter({"x": 10, "y": 0, "z": 0})) > 0.6


def test_sessionization_and_switches(mod):
    sessionize = mod.sessionize_commits
    session_switch_counts = mod.session_switch_counts
    t0 = datetime(2026, 1, 1, 9, 0, tzinfo=timezone.utc)
    commits = [
        C(mod, "c1", "A", t0),
        C(mod, "c2", "A", t0 + timedelta(minutes=10)),
        C(mod, "c3", "B", t0 + timedelta(minutes=20)),
        C(mod, "c4", "C", t0 + timedelta(minutes=65)),
    ]
    sessions = sessionize(commits, threshold_minutes=30)
    assert len(sessions) == 2
    assert session_switch_counts(sessions) == [1, 0]


def test_weekly_active_repos(mod):
    group_weeks = mod.group_commits_by_iso_week
    t = datetime(2026, 2, 2, 10, 0, tzinfo=timezone.utc)
    commits = [
        C(mod, "a", "repo1", t),
        C(mod, "b", "repo1", t + timedelta(hours=1)),
        C(mod, "c", "repo2", t + timedelta(days=1)),
    ]
    groups = group_weeks(commits)
    assert len(groups) == 1
    repo_counts = Counter(c.repo for c in next(iter(groups.values())))
    assert sum(1 for v in repo_counts.values() if v >= 2) == 1


def main():
    mod = load_module("./script/analyze-repo-switching.py")
    test_gini(mod)
    test_sessionization_and_switches(mod)
    test_weekly_active_repos(mod)
    print("Clean tests passed.")


if __name__ == "__main__":
    main()
