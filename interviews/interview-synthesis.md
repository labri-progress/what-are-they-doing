# Interview Synthesis: Four Agentic Developers

*Compiled 2026-10-07 from the interview transcripts in this folder.*

## Sources

| Participant | GitHub handle | Format | Date | Length | Source file |
|---|---|---|---|---|---|
| Andy Anderson | clubanderson | Video call (Fabian, Romain) | 2026-06-05 | ~2 h | `clubanderson/interview_clubanderson_2026-06-05_15-07-10.html` |
| Jeffrey Emanuel | Dicklesworthstone | Video call with screen share (Fabian, Romain) | 2026-06-24 | ~3 h | `Dickelsworthstone/interview_Dicklesworthstone_2026-06-24_16-11-34.html` |
| Steve Yegge | steveyegge | Video call with screen share (Fabian, Romain) | 2026-07-07 | ~90 min | `steveyegge/interview_steveyegge_2026-07-07_18-20-24.html` |
| teamchong | teamchong | Email, two rounds (7 questions + follow-ups) | 2026-07-26 | — | `teamchong/email-interview_teamchong_2026-07-26_09-19-50.md` |

The three calls followed `interview-guide.md` (semi-structured). The analysis window for the GitHub data is **September 2025 – April 2026**.

### Caveats

- **Uneven depth.** teamchong's email answers are much shorter than the call transcripts. A "—" in a cell means the topic wasn't covered, not that the answer was "no".
- **Self-reports.** Figures such as agent counts, spend, commit volumes and success rates are the participants' own claims and haven't been verified.
- **Transcription.** The call transcripts were made automatically (noScribe) and contain some misheard words (e.g. "kubesteller" for KubeStellar, "Olama" for Ollama). Quotes should be checked against the audio before publication.
- **Timing.** The interviews took place 1–3 months after the analysis window. Some tools and models mentioned are newer than that window (e.g. Steve's "Gas Station" harness, Fable, Andy's switch to Goose).

---

## Table 1: Profile comparison

| Dimension | Andy Anderson (clubanderson) | Jeffrey Emanuel (Dicklesworthstone) | Steve Yegge | teamchong |
|---|---|---|---|---|
| **Background** | 34 years in enterprise IT as architect, manager and director. BS in CS, MS in finance, PhD. Mainly writes automation scripts (Perl, Python); says his coding is "okay", not expert-level | Pure math degree, then hedge funds (long/short equity). Taught himself to code: MATLAB neural nets in 2010, then Python/TypeScript from his 2017 crypto startup | 40 years as a developer (Amazon, Google, Grab). Ran dev tools at Amazon and Google. Has been manager, director and VP | Expert in his own field. Former technical manager. Answered "in a personal capacity", not for his employer |
| **What got him started** | Fear of losing his job (Dec 2025). Decided to "eliminate myself", partly as a self-experiment on whether he'd lose skills | Frustration that his startup's dev team was slow, and he couldn't work on the C++ part himself | A "100-year backlog" on Wyvern, his 30-year game. Saw ChatGPT write working Emacs Lisp. Has chased developer productivity his whole career | Didn't say directly. At first found LLMs of little use because he's already an expert, so he ran many sessions in parallel instead |
| **Timeline** | First ChatGPT copy-paste use around Mar 2025. Full Claude loop from Dec 2025: a sudden switch | Browser ChatGPT/Claude with 6 accounts, then Claude Code around Apr–May 2025. **Big jump in Oct 2025 with MCP Agent Mail** | Used Claude Code from the start. Stopped writing code himself around summer 2025. Took mid-April to mid-June 2026 off (tired, and waiting for Fable) | Gradual, in three phases (see workflow row). Project example started Sept 2025 |
| **Self-rated autonomy (1–10)** | Not given as a number. Wants full autonomy for maintenance (his "level 6" in ACMM) but humans in charge of direction and UX | Effectively near 10: "I don't even read every line of the plans" | **5**, because he is "constantly hands on" (rates his wife at 10) | Not given. Settled on "knowing when to let go and when to step back in" |
| **Main projects (Sep '25–Apr '26)** | KubeStellar Console (written entirely by AI) and Hive, a 24/7 multi-agent maintainer that is now a SaaS with an external customer (Project Bluefin/Knuckle) | Agent Flywheel tools (Agent Mail, beads_rust, bv, cass, dcg, rch, NTM, UBS…) and the FrankenSuite of Rust ports (FrankenSQLite, Asupersync at 4M+ lines of Rust, about 40 "Franken*" projects) | Beads, Gas Town, Gas City. Wyvern in a private repo (main focus since May) | pxpipe (local proxy testing visual context for LLMs) plus work projects |
| **Harness** | Claude Code personally. Copilot CLI inside Hive (free CNCF tokens). Started Goose + Ollama + DeepSeek R1 on the interview day. Tried and dropped Bob, Antigravity, Pi | **Stock** Claude Code and Codex, roughly 50/50, always the latest version. Gemini/Antigravity for code review only. Deliberately harness- and provider-agnostic | **Only** Claude Code, but angry it has no API/platform and "will get off" it as soon as something better appears. Own Emacs wrapper on top | Claude Code "almost exclusively" |
| **Models** | Stays on Opus 4.6 on purpose ("I don't want more agency"). Experimenting with local models for small, self-contained tasks | Only the best models at max reasoning effort ("you don't know in advance what's hard") | Fable/Opus at the top, then sends work down to the cheapest model that can do it. Called Jeffrey's approach "the rich person's answer" | Mostly Opus. Fable is better but too expensive. "You don't need a PhD for every job" |
| **Custom tooling** | Hive orchestrator, prompt templates with parameters filled in ("Mad Libs"), pub-sub-tmux to scrape harness output into a stream | Large in-house stack: Agent Mail with advisory file locks, beads + graph metrics, NTM swarms, remote compilation fleet, disk-space guard, destructive-command guard, a paid skills site | "Gas Station" (built the weekend before the interview): Emacs cockpit, agent mail, handoffs, dashboard, account switching, built on Beads and Obsidian "brains". Built for his project on purpose, not for reuse | **Nothing**: no MCP, no skills. "Most [internet advice] is full of shit" |
| **Agents in parallel** | 4–7 sessions he watches by eye, plus about 5–6 Hive agents running 24/7 | Usually 50+, sometimes over 100 full agents. 55 accounts, 7 home machines, 12 VPS instances | 16 direct agents, each running its own sub-agents ("indirects") | 2–5, plus a few backup experiments |
| **Running unattended** | Yes. A single prompt ran 72h+ (found 2,800 issues, committed 400 fixes). Hive runs 24/7 | Yes, continuously. "Universal prompt" plus skills keep swarms busy for days. Some sessions ran 11 days | Overnight, from piles of pre-filed beads (700 in 36h). Longest run about 8h | Personal projects: a "loop" with a set goal. Work: small tasks handed to individual agents |
| **Planning and prompting** | Planning mode with a 4–5 line prompt. Defines a target outcome rather than steps ("You will be judged on how many failures and fixes you create. Keep testing."). Treats issues as prompts | **Plan-first**: markdown plan revised 10–12 rounds with feedback from many models (GPT Pro decides), then turned into beads and polished about 5 times | Org-mode backlog, then **pages-long** prompts, then the agent gathers context, then a design, then beads reviewed 3–4 times, then the agent fleet implements | **No specs** ("too much overhead"). Keeps the plan in his head and reveals it bit by bit. Lets the agent build something, then they reshape it together |
| **Checking the work** | 91% test coverage, 36–37 CI workflows on different schedules, Playwright screenshots, CodeQL, error tracking in Google Analytics, NPS scores, median time to resolve about 40 min | Huge test suites, conformance tests against the original (golden outputs), linter-style bug scanner (UBS), Lean/TLA+ proofs for Asupersync, a "negative evidence ledger", "reality check" skills | Plays the game himself, agents play it too, and players report through an in-game bug familiar. Quality comes in multiple passes ("painting a wall") | Another model checks the result and writes an HTML walkthrough page. He tests by hand and **reads the diffs** |
| **Reads the code?** | **No.** "Code is a means to an end"; hasn't read a commit message in months | **No**, not even all the plans | Only Wyvern's core code, "for joy". Not for his other projects | **Yes**, reads the diffs and picks the winning approach |
| **Commits / GitHub traces** | Agents commit with his credentials. Didn't know his DCO "Signed-off-by" was added by an agent. Lots of PRs, few issues, so the *reasons* for changes are missing | One dedicated agent groups and commits changes. That's why Claude Code gets credit for most commits ("stolen valor"); Codex commits under his own name. Says the only reliable way to attribute authorship is to mine session logs | Pushes straight to master. Activity "plummeted" from the private repo and his 2-month break | **Turned off the Co-Authored-By trailer**, which is why our analysis found <1% agent signals |
| **Projects at once** | Several hives on one dashboard. Mostly testing and hardening | Up to about 40 projects in parallel. Credits hedge-fund habits for his "superhuman" context switching | 3–4 unrelated projects at most, 10–15 sub-streams inside one. **Switches less as agents get smarter.** Works on one project for a day or a week at a time | Now fewer projects (wants family time) |
| **Spend** | $400/month for 4 months, now $200, aiming for $0. Gets unlimited tokens through CNCF ("unfair advantage") | $12.5k/month in subscriptions (about $200k/month at API prices). Used workarounds to open more accounts. Anthropic cancelled his accounts once, then restored them | About $1,600/month (8 Claude Max accounts). "Touch and go" with his wife | Flat Claude Max plan; "would test a lot less if I was watching a meter". Hours not disclosed |
| **Hours / burnout** | 12+ hour days, "almost like a dopamine". Sets limits but sometimes works until 2am. Thinks burnout is real | **16 hours a day**, loves it, no burnout. Not taking outside PRs removes social friction | Calls burnout a **"huge problem"** and took 2 months off. Says he now works 8 hours a day | Burned out in the "review every line" phase. Not a concern now: "I know my limit" |
| **Loss of control** | Feels in control for issues (structured feedback forms), not for feature requests | Accepts it, like a CEO who hasn't seen every hire's résumé | It's a delegation problem, "classic manager thing" | Swinging to full autonomy meant he "outsourced [his] own thinking". Fixed by finding a middle ground |
| **Security** | Hive was hit by an XSS attack overnight. Fixing these is "cat and mouse" | Memory-safe Rust only, no `unsafe`, few dependencies outside his own projects | **"Terrified."** Snyk found 271 vulnerabilities after Fable had done a security pass. Has a dedicated security agent | — |
| **Outlook** | Pro-labor, wants humans in the loop, against recursive self-improvement, mixes providers. Expects 9-to-5 jobs to disappear | Everyone will work this way. People will go "Rust-pilled". Productivity will follow a power law. Companies should let go of people who don't adapt | Value will come from "experiences", not tools (he wants to be a "solo unicorn"). Predicts talking-avatar programming within a year. Current state is "worst train wreck" | Not worried: mid-tier models improve fast, and pxpipe halved his token cost |

---

## Table 2: Themes across all four

| Theme | Where they agree | Where they differ |
|---|---|---|
| **The developer becomes a manager** | All four use a management analogy: Steve ("promoted to manager"), teamchong ("like managing a team"), Jeffrey (Larry Page/Sundar), Andy (open-source maintainer) | Steve and teamchong say they can do it because they *were* managers. Jeffrey credits hedge-fund multitasking. Andy compares agents to open-source contributors of mixed ability |
| **Running agents in parallel** | All run several agents at once | Scale differs by about 50×: teamchong 2–5, Andy 4–7, Steve about 16 plus sub-agents, Jeffrey 50–100+. **Worktrees:** teamchong uses one worktree per agent and no communication; Jeffrey explicitly rejects worktrees in favour of agent messaging and file locks |
| **Agents talking to each other** | Jeffrey and Steve both built agent mail and use Beads | teamchong: "nothing to coordinate". Andy: agents interact only indirectly, through CI and the codebase |
| **Planning** | All three who discussed it put judgment upfront | Ranges from heavy (Jeffrey's plan-first method, Steve's pages-long prompts) to none (teamchong). Andy gives a target outcome instead of a plan |
| **Feedback loops and testing** | All rely on automated checks rather than reading code | Andy: CI and user metrics. Jeffrey: skills, conformance tests, formal proofs. Steve: playtesting and quality passes. teamchong: a second model as reviewer |
| **Quality depends on effort** | Jeffrey and Steve both: quality improves with more passes and more tokens (Jeffrey's "rule of five", Steve's "coats of paint"). teamchong: AI code is already better than many humans' | Andy doesn't care about code-level quality at all, only measurable outcomes. Jeffrey cares about it as much as volume. Steve depends on the project (Gas Town was "crap", Wyvern must be high quality) |
| **Skills / config** | — | Opposite ends: Jeffrey has hundreds and sells them; Steve has about 6 and thinks skills will be absorbed into future models; teamchong has none; Andy uses prompt templates instead |
| **Which model** | All use Claude Code/Opus heavily and mention Fable as a step up | Always the best model (Jeffrey) vs routing to cheaper models (Steve) vs deliberately older (Andy) vs "good enough" (teamchong) |
| **Cost and fairness** | All four bring up cost. Three hope open or local models will catch up (Andy, Steve, teamchong) | Jeffrey isn't worried (sponsors, consulting). Andy is the only one who treats access as a fairness issue and is building for people who can't afford tokens |
| **Weak at UI / vision** | Andy, Steve and Jeffrey all name UI and visual work as the biggest weakness (Steve also called vision the biggest breakthrough) | — |
| **Burnout** | Everyone recognises it | Jeffrey: none at 16h/day. Andy: real risk at 12h/day. Steve: took 2 months off. teamchong: burned out early, then changed his workflow |
| **How much GitHub shows** | All say GitHub data under-represents or distorts what they do | Hidden by different mechanisms: attribution turned off (teamchong), a single committer agent (Jeffrey), private repo plus a break (Steve), missing rationale and auto-added DCO sign-offs (Andy) |
| **Why they do it** | Building things that used to be impossible; ambition | Fear turned into mission (Andy), speed and building in public (Jeffrey), finishing a decades-long dream / business (Steve), steady pragmatism (teamchong) |

---

## Key takeaways

1. **Commit trailers are not a reliable signal of agent use.** Each participant defeats them in a different way: attribution turned off (teamchong), one agent committing everyone's work (Jeffrey), agent-added DCO sign-offs (Andy), a private repo (Steve). Jeffrey suggests the only reliable source is the agents' session logs.
2. **The developer's job shifts to planning, verifying and delegating.** But people sit at very different points between "plan heavily" (Jeffrey, Steve) and "reveal the plan gradually, react to a first draft" (teamchong). Andy instead defines a target outcome and lets tests enforce it.
3. **Scale is driven mostly by money and access to tokens.** Spend ranges from one flat subscription to $12.5k/month. Two participants said so themselves (Andy's "unfair advantage" from CNCF tokens, Jeffrey's "$2–3k/month minimum to see the benefit").
4. **The weak spot they agree on is UI/visual work.** It's the hardest area to give agents an automatic feedback loop for.
5. **Burnout and a manager-like attention limit come up in every interview.** Even the most autonomous setups depend on the person's attention, and only Jeffrey reports no strain.
6. **Unexpected finding:** Steve says he switches between projects *less* as agents get smarter, because better agents take on bigger work that needs more of his attention per project.

---

## Glossary of tools and terms mentioned

| Term | Meaning (as described by participants) |
|---|---|
| **ACMM** | "AI Codebase Maturity Model": Andy's paper defining levels of repo autonomy (level 6 = full autonomy) |
| **Hive** | Andy's multi-agent orchestrator that maintains repos 24/7 (security, CI, issue→PR, supervisor). Now offered as a SaaS |
| **Prompt templates ("Mad Libs")** | Andy's prompts with placeholders that code fills in before each run |
| **pub-sub-tmux (PST)** | Andy's tool that scrapes harness terminal output onto a message bus, to get predictable completion signals |
| **Beads** | Steve's task/knowledge graph for agents (epics, tasks, dependencies). Jeffrey ported it to Rust (`br`) and added a viewer (`bv`) that uses graph metrics |
| **Gas Town / Gas City / Gas Station** | Steve's orchestrators: Gas Town (open source), Gas City (enterprise version now run by others), Gas Station (personal Emacs-based harness built in July 2026) |
| **MCP Agent Mail** | Jeffrey's messaging system for agents, with advisory file reservations. His productivity jump in Oct 2025 |
| **NTM** | Jeffrey's tmux wrapper for starting and steering swarms of Claude Code/Codex agents |
| **dcg / rch / UBS / cass** | Jeffrey's destructive-command guard, remote compilation helper, "ultimate bug scanner", and coding-agent session search |
| **Negative evidence ledger** | Jeffrey's log of failed optimisation attempts, so agents stop repeating them |
| **Rule of five / coats of paint** | Do several review passes, because one pass reaches only basic quality. Taught by Jeffrey, adopted by Steve |
| **Intelligence tiering** | Steve's term for routing each task to the cheapest model that can handle it |
| **The "loop" (teamchong)** | Write candidate approaches into a file, run one agent per approach in its own git worktree, have a different model verify, then a human reads the diffs and picks the winner |
| **Fable** | The frontier model discussed as a big capability jump. Access was limited or withdrawn around the time of the interviews |

---

## Publication and consent notes

| Participant | Can be named? | Notes |
|---|---|---|
| Andy Anderson | Not stated explicitly | Agreed to email follow-up. Wants his stance presented as pro-labor / humans-in-the-loop |
| Jeffrey Emanuel | **Yes**, full name ("I'm an open book") | **Parts of his screen-share fall under consulting NDAs; don't use anything from those parts.** Offered to have us blank out passages from the transcript. Also mentioned account workarounds, so take care if quoting those |
| Steve Yegge | **Yes** ("I don't need to be anonymous"). Signed the consent document | Asked for a mention of Beads in the paper |
| teamchong | **Yes**, "happy for this to be on the record" | Speaking in a personal capacity only, not for his employer |
