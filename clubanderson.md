# Analysis of @clubanderson

Veteran enterprise software architect (34 years in enterprise computing,
automation-focused, scripting languages) and solo maintainer of the KubeStellar
console (kubestellar/console), a multi-cluster Kubernetes management tool.
Publicly writes about engineering the codebase so that coding agents can help
maintain it (e.g. "I Purposely Built a Codebase That Teaches Itself",
kubestellar.medium.com) and runs an ACCM (AI Codebase Maturity Model) history
workflow in the console repo, updated daily by GitHub Actions.
Interviewed 2026-06-05 (transcript in interviews/clubanderson/).

## Commits per month (sampled commits; total_count equals sampled, no rate limiting)

```
  2025-09:     3
  2025-10:    86
  2025-11:    81
  2025-12:   184
  2026-01:  1,414
  2026-02:  1,220
  2026-03:  2,462
  2026-04:  3,176
  ---------------------------------
  Sep-Apr:  8,626 commits, 157 active days
```

Sharp ramp-up starting January 2026 (agent workflow adopted around December
2025); output keeps accelerating through April 2026.

## Repositories

Tracked repo list in the monthly snapshots has 100 entries (the enumeration is
capped at maxRepositories=100, so this is a lower bound; 78 distinct repos
actually committed to in the RQ4 export). Dominant repos: kubestellar/console and
other kubestellar/* repositories; clubanderson/clubTivi, clubanderson/aquaautomate,
clubanderson/ha-water-quality-monitor (home-automation projects); plus many
awesome-* lists (observed contribution bursts).

## Agent detection

- Sep 2025 - Feb 2026: 56.1% of commits carry at least one harness signal
  (top agent: claude_code).
- Sep 2025 - Apr 2026: 28.1% (top agent: claude_code) - the attribution rate
  falls as volume grows; early work is nearly all agent-attributed.
- Note: 74.1% of churn falls in the residual "others" category in RQ4
  (lowest code share of the cohort, 10.9%).
