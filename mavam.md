# Analysis of @mavam


A increasingly using AI agents as shown below. From more than 50% in Dec. 2025
to almost 70% of the commits in Jan. 2026. And still growing!

```
Agent Detection — mavam-2025-12
=======================================================
  Commits with ≥1 agent signal : 430 / 802  (54%)
  Commits with >1 agent signal : 0

Agents detected  (n=802 commits)
────────────────────────────────
  claude_code               ████████████████████████████████████ 54%        430

Example commits per agent
─────────────────────────

  claude_code  (430 commits)
    c6e864c  Use claude CLI for marketplace updates
    0ffd5f1  Use 2-space indentation for shfmt
    4116e83  Add project-level settings with tenzir marketplace

...

Commits: 567  |  cached: 0  |  full message: 0  |  agents: 44
  tip: run analyze-commit-quality.py first to fetch full messages

Agent Detection — mavam-2026-01
=======================================================
  Commits with ≥1 agent signal : 382 / 567  (67%)
  Commits with >1 agent signal : 0

Agents detected  (n=567 commits)
────────────────────────────────
  claude_code               ████████████████████████████████████ 67%        382

Example commits per agent
─────────────────────────

  claude_code  (382 commits)
    60779b6  Redesign changelog landing page with project card grid
    73f305b  Fix button font-weight to match design system
    103bfa2  Restore /docs:pr command with cross-referencing support
```
