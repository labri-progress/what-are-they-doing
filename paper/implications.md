# Implications — working draft for review

Status: rewritten 2026-10-03. The rewrite has two goals. First, it updates the
evidence to the nine-developer dataset (September 2025 to April 2026). Second,
it reranks the implications by strength of support. Every item now carries a
support tag:

- [paper]: the claim is verified in `paper.tex`.
- [paper+interview]: the claim is verified in `paper.tex` and in an interview source.
- [interview]: the claim is verified only in an interview source. It is not yet in the paper.
- [one source]: the claim rests on one developer account. It needs corroboration before it can carry weight in the Discussion.

All line numbers refer to the working copy of 2026-10-02 and later. Re-check
them before you cite them in prose. The draft is still being edited.

Verification protocol: every factual claim was checked against `paper.tex` and
the interview sources on 2026-10-03. Anything not verified is marked as such.

## Evidence base

### Verified in the paper

- The cohort has nine developers, from September 2025 to April 2026: 146,940
  commits across 455 repositories (paper.tex:49, 76).
- Agent attribution is detectable in 48.3% of commits. Claude Code accounts for
  the majority of attributed commits: 69,127 commits (paper.tex:51, 85).
  Of the attributed commits, 94.92% carry a Claude Code signal (paper.tex:986).
- Three of the nine developers show near-zero hard signals. Two of them,
  steipete and teamchong, produce extreme commit volumes. The third,
  philipp-spiess, produces 914 commits (paper.tex:86, 344). In the
  attributed/unattributed table, steipete is at 2.8%, teamchong at 0.7%, and
  philipp-spiess at 4.1% (paper.tex:1563, 1565, 1566).
- Dicklesworthstone is at 75.0% attribution (paper.tex:1564). ruvnet has the
  highest share in the cohort, 88.8% in the same table (paper.tex:1566) and
  87.2% in the profile (paper.tex:321).
- Three developers sustain activity to the end of the window: clubanderson,
  steipete, and Dicklesworthstone (paper.tex:943-946). The others show a burst
  and then a decline. teamchong peaks at 3,147 commits in December 2025 and
  never approaches that volume again (paper.tex:320).
- The abstract says that "agents keep working while the developer does not"
  (paper.tex:82). The results text is weaker: it says "we assume" that the
  slowdown phase is a rest period, and the histogram is in UTC, so the local
  time zone is unknown (paper.tex:863, 867, 938).
- The cohort maintains 7.2 repositories per week. The pre-agent baseline is
  3.7 (paper.tex:90, 1253, 1285-1286). The median developer switches
  repositories four times per day. Dicklesworthstone reaches a median of
  168.5 switches per day and a maximum of 1,292 (paper.tex:90, 1277).
  Activity stays concentrated: the median weekly Gini coefficient is 0.449
  (paper.tex:1267).
- The typical commit is small and focused at every volume. The median commit
  changes 27 to 88 lines and touches one or two files. Between 76.4% and
  89.0% of commits touch at most five files (paper.tex:1337-1338).
- Attributed commits are larger on average: median churn 98 lines against 53
  (paper.tex:1398, 1562). For ruvnet and clubanderson the relation reverses:
  ruvnet has 40 against 243, clubanderson 52 against 72 (paper.tex:1566-1570).
  So the size difference is a workflow property, not an agent property.
- Rework is heavy. Between 50.8% and 88.1% of file changes touch a file that
  the developer changed before. Same-day revisits account for 23.9% to 42.5%
  of changes (paper.tex:1503-1506).
- Agents write their own state into repositories. For steveyegge, state files
  account for 15.4% of file changes and 51.7% of churn. For ruvnet, 12.5% and
  16.4%. For Dicklesworthstone, 8.6% and 10.1%. For the other six developers,
  the share stays below 2% (paper.tex:1516-1518).
- Churn composition: 30.7% code, 15.4% tests, 12.1% documentation, 7.3% data,
  6.4% configuration, 28.2% in the residual category (paper.tex:1476-1478).
  The tail contains machine artifacts: a vendored test matrix and an
  agent-maintained memory store account for 3.0 and 1.7 million changed lines
  across three changes each (paper.tex:1480-1485).
- Quality (RQ5): code smells and cyclomatic complexity improve per kLoC. The
  duplication confidence interval is positive but includes 0, and 60% of
  projects show a worsening trend (paper.tex:1660). The aging scenario shows
  no clear trend on any metric (paper.tex:1694, 1700).
- Growth: the median project grows by a factor of 7.3 in lines (paper.tex:1600).
  The share of code in files above 1,000 lines doubles, from 22% to 46%
  (paper.tex:1616). The largest file reaches 129,000 lines (paper.tex:1621).
- RQ5 limitations: Dicklesworthstone contributes 41 of 55 projects
  (paper.tex:1753). All five quality metrics are file-local. None of them
  measures the organization of the codebase as a whole (paper.tex:1761-1763).

### Verified in the interviews (three attribution-failure mechanisms)

| Developer | Detected | Actual (self-reported) | Mechanism | Source |
|---|---|---|---|---|
| teamchong | 0.7% | Claude Code "almost exclusively" | Claude Code has attribution on by default. The user turned it off in the config. | email interview, line 3 |
| steipete | 2.8% | codex and openclaw. "I don't write code manually." | The harnesses mark no commits by design. Codex tried it only briefly. | email confirmation, lines 8, 10 |
| Dicklesworthstone | 75.0% | About 50-50 between Claude Code and Codex | Misattribution: a repo-updater skill inside Claude Code signs the commits of other tools. Claude Code "is sort of stolen valor." | transcript, lines 986, 1028-1030 |

The table shows two errors that push in opposite directions. Mechanisms one
and two push the Claude Code share down: work exists, but the trailer does
not. Mechanism three pushes the share up: the trailer exists, but the work
belongs to another tool. The 94.92% Claude Code share (paper.tex:986) is
therefore biased in both directions. The paper currently says that harness
frequencies "reflect both usage and detectability" (paper.tex:990). The
interviews support a stronger statement.

Warning: mechanism three is verified in the transcript, but it is no longer
stated in the paper. The paper says only that nearly all of Dicklesworthstone's
detected signals identify Claude Code (paper.tex:963-964). Before the
Discussion cites the misattribution, the RQ2 text must state it, with the
transcript as source.

### Verified teamchong quotes

- Attention as bottleneck: "My attention became the bottleneck, and it's easy
  to burn out that way" (Q3, line 17).
- Hands-off failure: "I'd outsourced my own thinking and understanding, I no
  longer understood how things worked" (Q3, line 19).
- Deliberate narrowing: "I don't work across that many projects anymore.
  Partly because I want to spend more time with family" (Q4, line 25).
- Subsidized economics: "AI companies are chasing market share ahead of their
  IPOs... That won't last forever" (Q6, line 38).
- Flat pricing: "I would test a lot less if I was watching a meter"
  (Q1 follow-up, line 67).
- Self-report, not verified: "Maintainability: I don't worry about it"
  (Q7 follow-up, line 103).

### Not verified / unresolved

- The clubanderson and steveyegge transcripts exist but were not read in
  this audit.
- mavam, obra, ruvnet, and philipp-spiess have no developer account. ruvnet
  has the highest attribution share in the cohort. So the high-signal end of
  RQ2 rests entirely on trace data.
- steipete gave an email confirmation, not an interview. His reply ends with
  a question: "how are you trying to detect this?" He invites a follow-up.
- teamchong says his productivity is "up 10x or more" (email interview,
  line 5). This is self-report. No trace data can confirm it.
- The Dicklesworthstone misattribution mechanism is in the transcript, but
  not in the paper. See the warning above.

## Implications, reranked by strength of support

The old draft ordered the items by audience. This version orders them by how
well the evidence supports them. Write the top items into the Discussion
first. Hold the bottom items until corroboration exists.

### A. Measurement and provenance (strongest support)

1. **Commit metadata no longer represents authorship in any direction.**
   [paper+interview] Three verified mechanisms produce three different
   errors: opt-out, no attribution by design, and misattribution. Where
   provenance matters — compliance, security review, credit — it must be
   explicit policy, not an assumed default. A commit trailer is a text
   string. Any tool can omit it or forge it. This claim holds for every
   developer who uses agents, at any volume. It does not depend on the
   specific cohort.
2. **Trailer-based detection is a lower bound, and the bias has two
   directions.** [paper+interview] Undercount: a trailer that was never
   written cannot be recovered by message-pattern matching. Over-attribution:
   a harness can sign the work of other tools. Signal-independent
   triangulation is required. The third low-signal developer, philipp-spiess,
   makes this point stronger: the blind spot appears at 914 commits, not
   only at extreme volume.
3. **Activity graphs cannot be read as presence.** [paper] The rest-hours
   finding is ambiguous by construction: the data is in UTC, the local time
   zone is unknown, and the text says "we assume" (paper.tex:863). Also, a
   repo-updater skill can produce commits without a human in the loop
   [interview]. Do not manage people by commit telemetry. The abstract
   sentence "agents keep working while the developer does not" (paper.tex:82)
   is stronger than the results text supports. Align them before submission.
4. **Effort proxies are decoupled from effort.** [paper] teamchong produces
   extreme volume with intact commit hygiene: the median commit still touches
   one or two files. Contribution graphs and leaderboards measure pipeline
   throughput, not human work. Note the cohort scope: this is verified for
   nine hyperactive developers. It is a hypothesis for developers in general.

### B. Code quality and maintenance

5. **"No degradation" is a per-line statement, not a project-level
   acquittal.** [paper] Per kLoC, smells and complexity improve. But total
   remediation effort grows, because code grows faster than per-line quality
   falls. Duplication worsens in 60% of projects. Code concentrates in
   oversized files: the share above 1,000 lines doubles from 22% to 46%.
   Track absolute debt, not only per-line rates.
6. **Local static metrics are blind to the open hypothesis: architectural
   pathologies of agent-built systems.** [paper] All five metrics are
   file-local. Duplication, the only cross-file metric, is also the only one
   leaning worse. Candidate measures: file-size distribution over time,
   change locality, coupling. The file-size distribution is cheap to compute
   and changes within months. This item is partly written already: the
   Discussion paragraph on where the added code ends up covers it
   (paper.tex:1717-1741). Finish it and point to it.
7. **Repositories accumulate machine-written state.** [paper] Up to half of
   churn can be agent bookkeeping, and the churn tail contains million-line
   vendored artifacts. This has two consequences. For measurement, churn-based
   mining needs new hygiene rules; filters written before 2025 do not know
   about agent state files. For collaboration, review queues fill with
   machine state. Both need filtering conventions.

### C. Workload and attention (single-source support: read the other transcripts first)

8. **The bottleneck moves from typing to attention, and attention has hard
   limits.** [one source] teamchong describes both failure modes from
   experience: review-everything burns out; let-it-run loses understanding.
   The cohort revisit rates, up to 88.1% of changes on an already-changed
   file, are consistent with the iteration loop that this implies. Do not
   write this item into the Discussion before the clubanderson and steveyegge
   transcripts are read and checked for or against it.
9. **Burst-then-decline is the modal trajectory, and the collapse can be a
   rational choice.** [paper for the trajectory, one source for the
   interpretation] teamchong narrowed scope deliberately, for family time.
   So a decline is not always a failure. The interesting research question
   shifts: what predicts sustained activity?
10. **Solo-scale production is proven; solo-scale stewardship is not.**
    [paper for the production side, one source against the stewardship side]
    The cohort built 55 projects in months. But the aging scenario has no
    maintenance phase, and the one stewardship claim ("I don't worry about
    it") is self-report. Keep the asymmetry explicit.

### D. Ecosystem and economics (hold for now)

11. **Attribution settings are a legibility decision with externalities.**
    [interview] One developer's privacy choice falsifies population-level
    adoption statistics. Individual opt-outs add up to a collective
    measurement error. This item is safe as one sentence.
12. **Provenance must become a protocol property, not a trailer.** [paper,
    for the failure; the remedy is a proposal, not a finding] The
    repo-updater case shows that a voluntary string cannot carry provenance.
    Where provenance is a compliance requirement, no trailer-based pipeline
    satisfies it. Harness and tool builders need a provenance mechanism that
    they cannot omit or forge. This item is a natural implication for the
    tool-builder audience, and it is currently missing from the draft.
13. **Workflows are calibrated to subsidized token economics.** [one source]
    Parallel fan-out and experimentation rates may not survive repricing.
    Keep to one sentence and label as self-report.

### E. Methods for researchers

14. **Pair telemetry with developer accounts by design, and label them
    correctly.** [paper+interview] A confirmation is not an interview. The
    coverage is asymmetric: the paper's steipete blind spot is
    correspondence-verified, its teamchong blind spot is interview-verified,
    and its highest-attribution developer, ruvnet, has no account at all.
    State the asymmetry in Threats to Validity.
15. **Pre-agent baselines are stale in the tail.** [paper] Means and medians
    disagree about what changed: the cohort median of four repository
    switches per day is elevated, and the tail is transformed (median 168.5,
    maximum 1,292). Report distributions, not only central values.
16. **Trajectories diverge enough that aggregate designs average away the
    phenomenon.** [paper] Use trajectory-typing with the developer as the
    unit. Cross-sectional means hide both the sustained growers and the
    burst-collapse cases.

## Open items / next steps

1. Add the Dicklesworthstone misattribution mechanism to the RQ2 text, with
   the transcript as source. Until then, do not cite it in the Discussion.
2. Align the abstract with the results text on the rest-hours claim
   (paper.tex:82 against paper.tex:863).
3. Read the clubanderson and steveyegge transcripts. Then firm up items 8,
   9, and 10.
4. Follow up with steipete. His reply invites it. He is also the one cohort
   member who builds agent tooling.
5. ruvnet is the biggest verification gap: the highest attribution share in
   the cohort, and no developer account.
6. Ready for Discussion prose now: items 1, 2, 5, 6, and 7.
