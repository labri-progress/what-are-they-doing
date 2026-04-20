# Analysis of @philipp-spiess

This user began committing frequently in Jan. 2026. His commits do not include
Claude's signature. He uses a different format. Each commit containing code
generated with an AI agent is signed with a transcript that links to an
agentlogs.ai session. See, for example, the following logs:
https://github.com/philipp-spiess/modern/commits?author=philipp-spiess&since=2026-02-19&until=2026-02-20

This scenario, i.e., a link to a coding session is not implemented in the
heuristics detecting AI agents. See:

```
Commits: 375  |  cached: 375  |  full message: 0  |  agents: 44
  tip: run analyze-commit-quality.py first to fetch full messages

Agent Detection — philipp-spiess-2026-01
=======================================================
  Commits with ≥1 agent signal : 1 / 375  (0%)
  Commits with >1 agent signal : 1

Agents detected  (n=375 commits)
────────────────────────────────
  claude_code               █████████████████████████████████████ 0%          1
  codex                     █████████████████████████████████████ 0%          1
  opencode                  █████████████████████████████████████ 0%          1

Example commits per agent
─────────────────────────

  claude_code  (1 commits)
    57d41fb  cli,opencode: Reorganize commands and fix commit tracking

  codex  (1 commits)
    57d41fb  cli,opencode: Reorganize commands and fix commit tracking

  opencode  (1 commits)
    57d41fb  cli,opencode: Reorganize commands and fix commit tracking

```

We visited several of the links referenced in the commits, but all of them lead
to error pages. However, this clearly indicates that the user acknowledges using
an AI agent.


```
Conventional Commit Analysis — philipp-spiess-2026-01
=======================================================
  Total commits     : 375
  Conventional      : 289  (77%)
  Non-conventional  : 86
  Breaking changes  : 0

By type  (n=289)
────────────────
  web                  ████████████████████████████████████ 53%       152
  docs                 ████ 9%                                        26
  cli                  ███ 7%                                         21
  chore                ██ 6%                                          18
  fix                  █ 6%                                           16
  ci                   █ 6%                                           16
  feat                 █ 5%                                           14
  shared               ███ 4%                                         12
  refactor             ██ 2%                                          6
  chunking             █ 1%                                           2
  opencode             █ 1%                                           2
  test                  0%                                            1
  build                 0%                                            1
  perf                  0%                                            1
  deck                  0%                                            1

Unknown types (not in Conventional Commits spec)
────────────────────────────────────────────────
  chunking             1
  shared               1
  opencode             1
  cli                  1
  web                  1
  deck                 1

By scope  (n=2, top 15)
───────────────────────
  cli                  ███████████████████████████████████ 100%       2

Non-conventional samples  (first 10 of 86)
──────────────────────────────────────────
  Add closing dot to bio
  Update bio: Somewhere between AI and UI
  Revert "docs: add a programming joke to README"
  Initial commit with hooks setup
  Add agentlogs dev script to run CLI with Bun
  Simplify package.json stuff
  Finalize rename
  Update AGENTS.md commit styles
  Fixes
  cli,shared: Fix git context resolution from .git/config
```

```
Commit Quality Analysis — philipp-spiess-2026-01
=======================================================
  Commits analysed : 375
  Avg files/commit : 0.0
  Avg lines/commit : 0.0

  Flag                   Bar                                                n      %
  ────────────────────── ────────────────────────────────────────────── ─────  ─────
  ── good ──
  fix_with_test           0%                                                0     0%
  focused                ███████████████████████████████████ 100%         375   100%
  pure_concern            0%                                                0     0%
  ── dirty ──
  fix_no_test            ██ 4%                                             16     4%
  feat_no_test            0%                                                0     0%
  scattered               0%                                                0     0%
  huge_diff               0%                                                0     0%
  mixed_concerns          0%                                                0     0%

File type breakdown  (n=375)
────────────────────────────

Dirtiest commits
────────────────
  ff6a4d6
    fix: return null instead of throwing in getSession to prevent SSR failur
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  3831132
    fix: use correct hookSpecificOutput format for PreToolUse
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  ff55bf8
    fix: add auth and user_id to commit-track endpoint
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  6ceca2b
    fix: use correct Claude Code hook JSON output format with decision and u
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  6900534
    fix: disable console output in pretool-hook to preserve stdout JSON
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  15fd3f1
    fix: derive commit prompts from transcript history, not tmp files
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  114823d
    fix: handle null repoId in transcript view
    files=0  lines=0  dirs=0  [focused, fix_no_test]
  146ef22
    fix: remove router instance caching to prevent HMR blank pages
    files=0  lines=0  dirs=0  [focused, fix_no_test]

Cleanest commits
────────────────
  ff6a4d6
    fix: return null instead of throwing in getSession to prevent SSR failur
    files=0  lines=0  [focused, fix_no_test]
  99ff7c0
    chore: update TanStack router/start to 1.145.11
    files=0  lines=0  [focused]
  3831132
    fix: use correct hookSpecificOutput format for PreToolUse
    files=0  lines=0  [focused, fix_no_test]
  ff55bf8
    fix: add auth and user_id to commit-track endpoint
    files=0  lines=0  [focused, fix_no_test]
  6ceca2b
    fix: use correct Claude Code hook JSON output format with decision and u
    files=0  lines=0  [focused, fix_no_test]
```
