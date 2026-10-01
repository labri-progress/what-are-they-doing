# Dataset Summary

## Overview
| Metric | Value |
| --- | --- |
| Observation window | 2025-09-01 to 2026-04-30 |
| Tracked repositories in dataset | 260 |
| Total embedded commit records | 146,940 |
| Peak aggregate day | 2026-03-22 (3,073 commits) |
| Commit records with >=1 hard agent signal | 70,996 / 146,940 (48.3%) |
| Distinct detected agent labels | 13 |
| Most frequent detected agent | claude_code (69,127 commits) |

## Developer Coverage
| Developer | Month span | Tracked repos | Commits | Active days | Peak day | Agent commits | Agent % | Top agent |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Dicklesworthstone | 2025-09..2026-04 | 100 | 71,420 | 188 | 2,702 | 53,634 | 75.1% | claude_code |
| clubanderson | 2025-09..2026-04 | 0 | 8,626 | 157 | 402 | 2,428 | 28.1% | claude_code |
| mavam | 2025-09..2026-04 | 21 | 3,231 | 202 | 88 | 1,255 | 38.8% | claude_code |
| obra | 2025-09..2026-04 | 36 | 4,803 | 171 | 202 | 2,264 | 47.1% | claude_code |
| philipp-spiess | 2025-09..2026-04 | 15 | 914 | 89 | 43 | 38 | 4.2% | codex |
| ruvnet | 2025-09..2026-04 | 9 | 6,184 | 169 | 350 | 5,394 | 87.2% | claude_code |
| steipete | 2025-09..2026-04 | 71 | 35,194 | 190 | 840 | 984 | 2.8% | codex |
| steveyegge | 2025-09..2026-04 | 0 | 9,364 | 142 | 390 | 4,951 | 52.9% | claude_code |
| teamchong | 2025-09..2026-04 | 10 | 7,204 | 141 | 295 | 48 | 0.7% | claude_code |

## Agent Signals
| Agent | Hits | Share of commit records | Developers with signal |
| --- | --- | --- | --- |
| claude_code | 69,127 | 47.0% | 9 |
| codex | 2,003 | 1.4% | 8 |
| copilot | 492 | 0.3% | 4 |
| amp | 357 | 0.2% | 7 |
| cursor | 107 | 0.1% | 5 |
| opencode | 66 | 0.0% | 5 |
| gemini | 41 | 0.0% | 4 |
| copilot-swe | 19 | 0.0% | 1 |
| qwen_code | 19 | 0.0% | 1 |
| roo_code | 4 | 0.0% | 1 |
| sweep | 2 | 0.0% | 1 |
| windsurf | 1 | 0.0% | 1 |
| cline | 1 | 0.0% | 1 |

## Notes
- `Total embedded commit records` counts the commit objects stored inside the monthly snapshot files; all agent-use metrics use this same total as the denominator.
- Agent-use labels are heuristic detections from commit messages, co-author trailers, commit authors, and cached changed-file signals when available.
- The current local dataset contains monthly snapshots for 9 of the 9 tracked developers listed in `developers.json`.
