#!/usr/bin/env python3
"""Compute the local-time work schedule of each developer (paper, RQ1).

Commit dates in data/{author}-{YYYY-MM}.json are UTC, so the author's time zone
is lost. This script clones every repository of the dataset (commits only,
--filter=tree:0, no rate limit), reads each commit's Git author date, which
keeps the author's UTC offset, and prints as JSON, per developer, pooled, and
for the median developer, the share of commits in 10:00-17:59, at night
(22:00-06:59), on Saturday and on the weekend. These are the measures of
Karyotakis et al. used as pre-agent baseline in the paper. It also reports the
share of commits stamped +0000, which flags servers and cloud agents.

Usage:
    python script/analyze-work-schedule.py data CLONE_DIR
"""
from __future__ import annotations

import collections
import glob
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

DATA, CLONES = sys.argv[1], sys.argv[2]
START, END = "2025-09", "2026-04"

commits: dict[str, tuple[str, str]] = {}  # sha -> (dev, repo)
for path in sorted(glob.glob(os.path.join(DATA, "*-20*.json"))):
    m = re.match(r"(.+)-(\d{4}-\d{2})(?:-\d+)?\.json$", os.path.basename(path))
    if not m or not (START <= m.group(2) <= END):
        continue
    for day in json.load(open(path)).get("days", {}).values():
        for c in day.get("commits", []):
            rm = re.match(r"https://github.com/([^/]+/[^/]+)/commit/", c.get("html_url", ""))
            if rm:
                commits[c["sha"]] = (m.group(1), rm.group(1))

repos = sorted({r for _, r in commits.values()})


def clone(repo: str) -> str:
    dest = os.path.join(CLONES, repo.replace("/", "__") + ".git")
    if not os.path.isdir(dest):
        subprocess.run(["git", "clone", "-q", "--bare", "--filter=tree:0",
                        f"https://github.com/{repo}.git", dest],
                       env=dict(os.environ, GIT_TERMINAL_PROMPT="0"),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1800)
    return dest


os.makedirs(CLONES, exist_ok=True)
with ThreadPoolExecutor(8) as ex:
    dests = list(ex.map(clone, repos))

local: dict[str, datetime] = {}
cloned = 0
for dest in dests:
    if not os.path.isdir(dest):
        continue
    cloned += 1
    out = subprocess.run(["git", "-C", dest, "log", "--all", "--format=%H %aI"],
                         capture_output=True, text=True).stdout
    for line in out.splitlines():
        sha, iso = line.split(" ", 1)
        if sha in commits:
            local[sha] = datetime.fromisoformat(iso)  # wall-clock time in author's offset

stats = collections.defaultdict(collections.Counter)
for sha, (dev, _) in commits.items():
    s = stats[dev]
    t = local.get(sha)
    if t is None:
        s["missing"] += 1
        continue
    s["n"] += 1
    off = t.utcoffset()
    s["utc_offset_zero"] += off is not None and off.total_seconds() == 0
    s["office"] += 10 <= t.hour < 18
    s["night"] += t.hour >= 22 or t.hour < 7
    s["saturday"] += t.weekday() == 5
    s["weekend"] += t.weekday() >= 5

KEYS = ("office", "night", "saturday", "weekend", "utc_offset_zero")
res = {"repos": len(repos), "cloned": cloned, "commits": len(commits), "matched": len(local), "devs": {}}
tot = collections.Counter()
for dev, s in sorted(stats.items()):
    tot.update(s)
    res["devs"][dev] = {"n": s["n"], "missing": s["missing"]} | {k: round(s[k] / s["n"], 4) for k in KEYS if s["n"]}
res["pooled"] = {k: round(tot[k] / tot["n"], 4) for k in KEYS}
res["median_dev"] = {}
for k in KEYS:
    vals = sorted(v[k] for v in res["devs"].values() if k in v)
    res["median_dev"][k] = vals[len(vals) // 2]
print(json.dumps(res, indent=1))
