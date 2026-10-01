# Analysis of @steveyegge

Steve Yegge, 40-year industry veteran (Amazon, Google, Grab, Sourcegraph) and
well-known technical writer. Creator of Gas Town (gastownhall org), an
open-source orchestration system that coordinates 20-30 parallel coding agents,
built on top of Beads, his Git-backed work-tracking/agent-memory ledger
(released October 2025; Gas Town open-sourced January 1, 2026).
Interviewed 2026-07-07 (transcript in interviews/steveyegge/).

## Commits per month (sampled commits; total_count equals sampled, no rate limiting)

```
  2025-09:     0
  2025-10:   335
  2025-11:   415
  2025-12: 3,473
  2026-01: 1,562
  2026-02: 1,974
  2026-03: 1,546
  2026-04:    59
  ---------------------------------
  Sep-Apr:  9,364 commits, 142 active days
```

Peak in December 2025, then decline; activity collapses in April 2026. In the
interview he attributes the decline to moving his main project (a 30-year
video game, kept in a private repository) off GitHub.

## Repositories

Tracked repositories are almost exclusively the Gas Town ecosystem:
gastownhall/gastown, gastownhall/beads, gastownhall/gascity,
gastownhall/marketplace, gastownhall/wasteland, gastownhall/homebrew-beads,
steveyegge/gastown-otel.

## Agent detection

- 53.4% of commits (Sep 2025 - Feb 2026) carry at least one harness signal;
  52.9% over Sep 2025 - Apr 2026 (top agent: claude_code).
- Agent-maintained files (beads state, in-repo issue tracking) account for
  15.4% of his file changes and 51.7% of his total churn in RQ4, the highest
  share of the cohort.
