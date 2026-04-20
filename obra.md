# Analysis of @obra





From October 2025 to February 2026, Obra progressively shifted its focus toward
AI agents.

```
Agent Detection — obra-2025-10
=======================================================
  Commits with ≥1 agent signal : 28 / 380  (7%)
  Commits with >1 agent signal : 0

Agents detected  (n=380 commits)
────────────────────────────────
  claude_code               █████████████████████████████████████ 7%         26
  codex                     ███ 1%                                            2

Example commits per agent
─────────────────────────

  claude_code  (26 commits)
    b694992  Use explicit paths instead of CLAUDE_PLUGIN_ROOT in bash code
    90da28e  Remove hardcoded path exclusions and add summarization prompt detectio
    16f4d46  Add sync command for session-end hook integration

  codex  (2 commits)
    da9f4f1  Release v3.3.0: Add experimental Codex support
    5831c4d  Fix AGENTS.md to be minimal one-liner + explanatory text


Agent Detection — obra-2025-11
=======================================================
  Commits with ≥1 agent signal : 85 / 297  (29%)
  Commits with >1 agent signal : 1

Agents detected  (n=297 commits)
────────────────────────────────
  claude_code               ████████████████████████████████████ 28%         82
  opencode                  ██ 1%                                             4

Example commits per agent
─────────────────────────

  claude_code  (82 commits)
    ec4f0cf  fix: enable multi-architecture Docker builds for refresh-container com
    ae5a3c5  fix: update all container image references to use published devcontain
    72a87bc  fix: configure CI workflow to push multi-arch container images

  opencode  (4 commits)
    0351bda  feat: add comprehensive OpenCode AI support
    f3d6c33  test: add automated test suite for opencode plugin
    9cd6c52  feat: use message insertion pattern for persistence and add project sk

...

Agent Detection — obra-2026-02
=======================================================
  Commits with ≥1 agent signal : 853 / 1103  (77%)
  Commits with >1 agent signal : 1

Agents detected  (n=1103 commits)
─────────────────────────────────
  claude_code               ████████████████████████████████████ 77%        851
  codex                      0%                                               3

Example commits per agent
─────────────────────────

  claude_code  (851 commits)
    f3c77d1  fix: follow parent chain through filtered entries in tree builder
    3d85cbd  fix: add remark-gfm for markdown table rendering
    d56fe3e  feat: enhanced minimap with branch offshoots and click-to-switch

  codex  (3 commits)
    5e1926d  Add Codex session scanner with tests
    35f2c77  Update AGENTS.md research: add home-level instructions (~/.codex/AGENT
    caee4eb  feat: ingest Codex sessions, fix CLI shebang and SQLite init

```


