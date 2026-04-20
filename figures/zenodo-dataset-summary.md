# Dataset Summary

## Overview
| Metric | Value |
| --- | --- |
| Observation window | 2025-09-01 to 2026-02-28 |
| Tracked repositories in dataset | 260 |
| Total embedded commit records | 76,749 |
| Peak aggregate day | 2026-02-16 (1,833 commits) |
| Commit records with >=1 hard agent signal | 36,565 / 76,749 (47.6%) |
| Distinct detected agent labels | 8 |
| Most frequent detected agent | claude_code (36,493 commits) |

## Developer Coverage
| Developer | Month span | Tracked repos | Commits | Active days | Peak day | Agent commits | Agent % | Top agent |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Dicklesworthstone | 2025-09..2026-02 | 100 | 38,424 | 127 | 1,115 | 29,610 | 77.1% | claude_code |
| mavam | 2025-09..2026-02 | 21 | 2,543 | 154 | 88 | 981 | 38.6% | claude_code |
| obra | 2025-09..2026-02 | 36 | 2,656 | 116 | 202 | 1,388 | 52.3% | claude_code |
| philipp-spiess | 2025-09..2026-02 | 15 | 801 | 69 | 43 | 2 | 0.2% | opencode |
| ruvnet | 2025-09..2026-02 | 9 | 5,304 | 117 | 350 | 4,483 | 84.5% | claude_code |
| steipete | 2025-09..2026-02 | 71 | 21,222 | 130 | 630 | 65 | 0.3% | codex |
| teamchong | 2025-09..2026-02 | 10 | 5,799 | 108 | 295 | 36 | 0.6% | claude_code |

## Agent Signals
| Agent | Hits | Share of commit records | Developers with signal |
| --- | --- | --- | --- |
| claude_code | 36,493 | 47.5% | 7 |
| codex | 142 | 0.2% | 4 |
| cursor | 45 | 0.1% | 4 |
| opencode | 22 | 0.0% | 3 |
| amp | 12 | 0.0% | 1 |
| sweep | 2 | 0.0% | 1 |
| copilot | 2 | 0.0% | 1 |
| gemini | 1 | 0.0% | 1 |

## Notes
- `Total embedded commit records` counts the commit objects stored inside the monthly snapshot files; all agent-use metrics use this same total as the denominator.
- Agent-use labels are heuristic detections from commit messages, co-author trailers, commit authors, and cached changed-file signals when available.
- The current local dataset contains monthly snapshots for 7 of the 7 tracked developers listed in `developers.json`.
