# RQ4 mining plan

Input for the commit- and code-quality pipeline: which repositories to clone,
for which developer, over which dates.

## Observation window

```
2025-09-01 .. 2026-04-30
```

Inclusive on both ends, UTC. This is the full extent of the collected data in
`data/{developer}-{YYYY-MM}.json` and matches the window used by RQ1-RQ3, so
RQ4 results line up with the temporal trajectories reported there.

The first two months are thin: September and October 2025 hold 2,591 commits,
1.8% of the 146,940 collected. Only `mavam` and `ruvnet` are meaningfully
active before November 2025. Keeping them costs almost nothing to mine and
captures the onset of hyperactivity, but per-month comparisons across the whole
cohort should start in November 2025.

## Files

| File | Contents |
| --- | --- |
| `repos.txt` | 179 GitHub URLs, one per line. Direct input to the mining tool. |
| `repos.csv` | One row per developer-repository pair with the attribution breakdown described below. |

Regenerate both with:

```bash
python script/select-rq4-repos.py
```

## Selection rule

A repository is included for a developer when that developer made **at least 50
commits in it inside the window**. This yields 179 repositories covering 142,901
of 146,040 canonicalised commits (97.9%) and about 19.7 GB of clones.

Three corrections are applied before the threshold:

**Canonicalisation.** GitHub redirects renamed and transferred repositories, so
the same repository can appear under two names in the collected data. 25 names
changed since collection and 12 canonical repositories were reachable under two
of them: `steipete/{Peekaboo,mcporter,gogcli,imsg,Tachikoma,AXorcist,goplaces,spogo,wacli}`
moved to the `openclaw` organisation, `ruvnet/ruvector` became `ruvnet/RuVector`,
`ruvnet/wifi-densepose` became `ruvnet/RuView`, and `obra/github-triage` moved to
`prime-radiant-inc`. Cloning both names clones the same repository twice; the 12
affected repositories hold 4,601 developer commits that would be analysed twice.
`repos.txt` carries canonical names only; the `aliases` column in `repos.csv`
records the old ones.

**Liveness.** Seven repositories have been deleted or made private since
collection, taking 900 commits (0.6%) with them: `openclaw/openclaw.ai` (397),
`Jyotilohar18/cass_memory_system` (268), `teamchong/zell` (169),
`dahisea/All-Repo-Trending` (55), `openclaw/skills` (3), `CreekBar/claude-flow`
(7), `edenreich/awesome-infrastructure` (1).

**Volume.** The threshold drops 260 developer-repository pairs, almost all
drive-by contributions into projects the developer does not drive: single
commits into `ghostty-org/ghostty`, `microsoft/amplifier`,
`open-policy-agent/opa` and a long tail of awesome-lists. Their code says
nothing about how these developers work, and including them would attribute
other projects' code quality to our cohort.

The list in `all.txt` at the repository root is *not* this list: it is the
union of every repository name in `data/*.json` with no developer mapping, no
canonicalisation and no filtering.

## Attribution: which commits to analyse

`repos.csv` splits every repository's window commits into five buckets, from
GitHub's weekly contributor statistics:

| Column | Meaning |
| --- | --- |
| `by_developer` | Commits authored under the target developer's own login. |
| `by_claude` | Commits authored by `claude`, the Claude Code GitHub App, which commits under its own identity rather than the developer's. |
| `by_other_agents` | `codex`, `cursoragent`, `factory-droid[bot]`, `devin-ai-integration[bot]`, and similar. |
| `by_infra_bots` | CI, release and dependency automation: `github-actions[bot]`, `dependabot[bot]`, `renovate[bot]`, `tenzir-bot`, and so on. |
| `by_other_humans` | Everyone else. |

Across the 179 selected repositories the split is:

| Bucket | Commits | Share |
| --- | ---: | ---: |
| Target developers | 153,632 | 55.8% |
| `claude` | 85,123 | 30.9% |
| Other coding agents | 116 | 0.04% |
| Infrastructure bots | 8,701 | 3.2% |
| Other humans | 27,917 | 10.1% |

**`claude` is the largest single author identity in the corpus**: 85,123 commits
across 137 of the 179 repositories, more than any individual human including
`Dicklesworthstone` (78,762). This drives the filtering decision:

- Filtering on `author == developer` **discards the agent's own commits**, which
  are the object of study. Do not do this.
- Analysing every commit in the clone pulls in unrelated contributors. That is
  mostly harmless for `teamchong` (99.7% developer-plus-agent) and
  `Dicklesworthstone` (99.2%), but not for `steipete`, where 17,356 commits
  (30.0%) come from other humans, almost all of them in `openclaw/openclaw`, a
  337-contributor project.
- The workable filter is an **identity set per developer**: the developer's
  login plus `claude` plus the other agent logins, excluding infrastructure bots
  and unrelated humans. `developer_plus_agent_share` in `repos.csv` is the
  fraction of each repository that survives it.

Developer-plus-agent share by developer, over the selected repositories:

| Developer | Repos | Commits in window | Developer | `claude` | Other humans | Dev+agent share |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| teamchong | 13 | 6,676 | 6,648 | 8 | 19 | 99.7% |
| Dicklesworthstone | 78 | 145,399 | 78,762 | 65,468 | 767 | 99.2% |
| ruvnet | 7 | 13,916 | 6,911 | 6,036 | 57 | 93.1% |
| obra | 22 | 8,238 | 4,698 | 2,781 | 758 | 90.8% |
| philipp-spiess | 2 | 801 | 696 | 9 | 96 | 88.0% |
| steveyegge | 2 | 18,904 | 8,504 | 6,066 | 4,154 | 77.2% |
| clubanderson | 12 | 17,395 | 9,507 | 2,758 | 2,825 | 70.5% |
| mavam | 10 | 6,301 | 2,878 | 1,223 | 1,885 | 65.1% |
| steipete | 33 | 57,859 | 35,028 | 774 | 17,356 | 62.0% |

Two entries deserve care when reading results:

- `steipete` shows only 774 `claude` commits. His agent traces are in commit
  trailers rather than the author field, consistent with the near-zero hard
  agent signal reported for him in RQ2. Author-identity attribution understates
  his agent usage; the `agent-mining` heuristics remain the right instrument
  there.
- `mavam` and `clubanderson` work in genuine multi-contributor projects
  (`tenzir/tenzir` at 27.0% developer share, `kubestellar/console` at 61.4%,
  `llm-d/llm-d-workload-variant-autoscaler` at 13.8%). Code-level metrics
  computed over the whole tree in those repositories describe a team, not a
  developer.

## Languages

Primary language of the 179 selected repositories, for sizing the code-level
analyzers:

| Language | Repos | Developer commits |
| --- | ---: | ---: |
| Rust | 52 | 56,524 |
| TypeScript | 40 | 44,856 |
| Go | 24 | 16,377 |
| Swift | 15 | 4,971 |
| Shell | 14 | 4,981 |
| Python | 9 | 3,390 |
| JavaScript | 7 | 1,923 |
| Zig | 6 | 5,986 |
| C | 3 | 816 |
| Other (C++, HTML, MDX, Ruby, Dart, none) | 9 | 3,077 |

Rust, TypeScript and Go cover 116 of 179 repositories and 82% of the commits.
Zig is small in repository count but concentrated: it is `teamchong`'s entire
stack, so dropping it removes a developer from the code-level analysis
entirely. Swift is almost all `steipete`.

## Caveats

**Sampling.** High-volume days were sampled at collection time: 146,940 commits
were collected against 151,421 reported by the API, so per-repository counts in
`developer_commits` are lower bounds for `Dicklesworthstone` (71,420 of 75,545)
and `ruvnet` (6,184 of 6,540). All other developers are complete.

**History rewriting.** In three repositories our recorded commits exceed the
current default-branch history, so some collected commits are no longer
reachable in a fresh clone: `Dicklesworthstone/frankensqlite` (3,242 recorded
against 2,003 present), `Dicklesworthstone/frankenterm` (7,852 against 6,996),
`Dicklesworthstone/vibe_cockpit` (150 against 149). Roughly 2,100 commits, 1.5%
of the selection.

**Contributor statistics.** Bucket counts come from GitHub's
`/stats/contributors` endpoint. Its totals can exceed a `git log` count on the
default branch, and it buckets commits into weeks starting on Sunday, so the
window edges round to the enclosing Sundays. Read the buckets as proportions,
not as exact commit counts. Where GitHub declined to compute statistics
(`kubestellar/hive`, `steipete/CodexBar`) the script falls back to paging the
commit history directly.

**Archived repositories.** Four of the 179 are archived: `teamchong/edgebox`,
`tenzir/docs`, `tenzir/claude-plugins` and `kubestellar/ui`. They still clone;
they simply stopped receiving commits.

**No within-repository pre-agent baseline.** Only 33 of the candidate
repositories existed before 2025-09-01, holding 7% of commits. Most of this
cohort's repositories were created during the window, so H4.3 (complexity
accumulating over time) has to be tested within the window rather than against a
pre-agent state of the same code.
