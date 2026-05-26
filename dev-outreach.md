# Reaching out to Devs

Goal is to get some answers directly from these developers, to verify our analysis results and fill in gaps in our knowledge or answer open questions.

We have essentially three options for that:

## Just send them some questions (essentially a questionnaire)

- Pro:
  - Low-effort on both sides
  - Reproducible/consistent (same message for everyone)
  - No coordination needed
  - Standard qualitative research method
- Con:
  - Impersonal
  - Easy to ignore
  - Hard to follow up

For this, we already have an initial draft, with questions that *should* address issues of all four research questions:

---

Hey INSERT_FIRST_NAME_IF_KNOWN!

We're academic researchers from several universities across Europe and Canada, and are currently analyzing your development patterns to publish in a research paper.  
Your GitHub activity exactly fits the patterns we are interested in, and we would like to know a bit more.  
While we have already performed some analysis, we would really appreciate it if you could answer a few short questions, and validate or correct our results.  
This helps **us** to publish more accurate insights, and allows **you** to ensure we're not making incorrect claims about your workflow.

The following questions are mainly about the timespan from **September 2025** to **April 2026**. Please try to keep that in mind when answering the questions :)

1. Our analysis showed that you were primarily using INSERT_PRIMARY_AGENT for development. Is that correct?
2. What things have had the biggest impact (positive or negative) on your pace of development?
3. Could you describe your agentic workflow in a few sentences? Specifically how you decide what to develop, how you implement it, how you verify it, and how you commit it.
4. How do you split your time between multiple projects? Do you work on multiple projects in parallel or in a more serial manner?
5. Do you use multiple agents in parallel? How many on average (you can give a range)?
6. How autonomous are your agents? How often do you need/want to interact with them? Do you let them run continuously and/or overnight?
7. Do you have any concerns about your workflow or its long-term impact/sustainability?

Finally, if you're curious about our research and/or would like to support it, we would be super happy to have a short chat/interview with you, where you can describe your process and experiences in a bit more detail.  
If you're interested in that, just let us know!

> [!NOTE]
> The following "About Us" part is optional, not sure if it would help to appear more serious?

**About Us:**  
We're researchers from four different universities, with a long history of research about AI-supported development. Specifically, here's who we are:

- **KTH Royal Institute of Technology, Sweden**
  - Prof. Martin Monperrus (Professor of Software Technology, pioneer of AI-assisted coding)
  - Larissa Schmid
  - & team
- **University of Bordeaux, France**
  - Dr. Thomas Degueule (CNRS Researcher at the LaBRI Software Engineering group)
  - Dr. Romain Robbes (Senior Scientist for Empirical Software Engineering and Machine Learning at CNRS/LaBRI)
  - & team
- **University of Waterloo, Canada**
  - Prof. Shane McIntosh (Associate Professor for Software Data Mining)
  - Georges Aaron Randrianaina (Postdoc, Software Data Mining)
  - & team
- **Technical University of Darmstadt, Germany**
  - Prof. Mira Mezini (Professor of Computer Science, with extensive background in programming systems and AI research)
  - Dr. Ragnar Mogk (Postdoc, Programming Language and Distributed Systems)
  - Dr. Amir Molzam Sharifloo (Postdoc, AI for Software Engineering and Code Models)
  - Fabian Meerkötter (Doctoral Researcher, Human-Centered Software Engineering)
  - Website: <https://www.st.informatik.tu-darmstadt.de/>

TODO add missing people and universities, possibly shorten?

---

## Semi-Structured Interview

- Pro:
  - Personal
  - In-depth answers
  - Follow-up questions
  - Standard qualitative research method
- Con:
  - Requires significant effort and time investment on both sides
  - Requires good preparation to not waste time
  - Takes longer to conduct (deadline)
  - Probably can't be conducted for all selected developers

There are a few candidates which appear likely to agree to an interview:

- Andy Anderson / clubanderson (<https://github.com/clubanderson>) has already agreed to be interviewed
- Jesse Vincent / obra (<https://github.com/obra>) seems genuinely interested to discuss the topic and share his thoughts
- Philipp Spiess / philipp-spies (<https://github.com/philipp-spiess>) doesn't seem super busy and already has a podcast where he talks about this topic with a friend
- (Steven Chong / teamchong (<https://github.com/teamchong>) seems eager to network and discuss their work)

The actual interview could probably recycle the questions from the "questionnaire" above in some way, but with the added benefit of facilitating more in-depth answers and allowing follow-up questions. Also, the interviews could be personalized a bit better, e.g., to touch on specific topics of interest for that person (like certain patterns we observed or behavior we can't yet explain).

TODO create interview guide (which questions to ask, roughly which order, prompts about specific details we're looking for in each question)

### RQs, Prior Domain Knowledge, Relevant Insights About Devs

- **Four research questions**:
  1. How do commit volumes and activity rhythms evolve over the observation window?
  2. Which coding agent harnesses do hyperactive developers use, and for which tasks?
  3. What is the quality of commits produced by hyperactive AI-augmented developers?
  4. How do hyperactive AI-augmented developers switch across repositories?
- **Relevant domain knowledge**:
  - Claude Code was first harness, more options are starting to show up
  - Change in mentality/terminology from "vibe coding" to "agentic development". Same or different?
    - Might be a **sensitive topic**, put towards the end in guide!
  - Some devs had tons of prior coding experience, others just started recently
  - Devs are paying considerable sums for agent subscriptions
  - workflows seem to vary a lot, w.r.t. commit behavior (attribution, commit messages), PR & Issues usage, even triage
  - There are pretty reliable signals to detect the agent harness if present, but if those signals are omitted (on purpose or due to the workflow), detection becomes guesswork
    - We can often detect that a harness was used, but not which one
  - We've seen signals for the following agents: Claude Code, OpenAI Codex, Cursor, Copilot, Gemini, OpenCode, Amp, Sweep
    - Notably missing: Pi / OpenClaw
  - Agent usage leads to new kinds of temporal patterns
- **Our hypotheses**:
  - Devs mix agent harnesses and/or switch their primary harness over time
  - Claude Code is the dominant harness
  - Agent usage enables cheaper / more effortless project switching between multiple repos
  - Agentic development is less about code generation and more about coordination and orchastration
  - Agentic development increases both code volume and code risk
    - Safe scalability is a concern
  - Agentic development could potentially increase structural quality of code/repos although it is significantly higher-volume output
  - There's an agentic coding explosion, with rapid growth over the past 8 months
- **Our existing results**:
  - Growth appears to still be ongoing for some devs, but others have slowed down again
  - Some devs appear to create artificially-high amounts of commits and/or GitHub activity, through mechanics like "checkpoint" commits or excessive agentic interactions through issues and pull requests
    - This might be a **sensitive topic**, move to the end! Especially Andy seems to be doing the latter

### Interview Guide Draft

TODO how to get them to focus on the analyzed time span? do we even need that, or do we just ask about their behavior "in the past"?
- **Before each interview:**
  - Note the primary harness that was detected
  - Create a list of main projects of that dev
  - Check if there are any unique patterns for this dev
    - e.g. bursts of or drops in activity, clear changes in signals, differences between repositories
  - Check if there are still any open questions or unexplained/unexpected results
- Duration: ???
- Before beginning, obtain informed consent
  - Consent to being interviewed and insights from their responses being published
  - Consent to being recorded for analysis purposes only (recording will not be published)
  - Consent to direct quotes being included in published works where appropriate
  - Optional: consent to be de-anonymized (e.g. if they want publicity/recognition?)
- Start the interview with a short introduction of the topic and the purpose of the interview
  - Phenomenon of certain developers becoming "hyperactive"
  - Past, present, and future of agentic development
  - Motivation and workflows
  - Mention that we've already analyzed their public traces, and would like to confirm or reject our findings
  - Mention that some aspects of the workflow and behavior cannot be mined from a repository
  - Mention that even though answers to some questions may be obvious from their GitHub activity, we're still asking them for completeness
- Continue with a warm up to set the state and become acquainted
- Notes for during the interview:
  - If answers are abstract, ask for at least one specific example
  - Treat results as hypotheses, not facts
  - Instead of "why?", ask:
    - "so how did that work / look like"
    - "what were your reasons for this?"
    - "could you elaborate?"
  - If they talk a lot, ask for a specific example to discuss
- At the end, close the interview

#### Potential Questions

TODO split questions into main themes? possibly per RQ, if possible

- Our analysis showed that you were primarily using INSERT_PRIMARY_AGENT for development. Could you elaborate on how and why you use that?
  - Probes:
    - What was the first agent harness you used?
    - Did or do you use any other agent harnesses (now or in the past)?
    - Have you found there to be any significant differences between agent harnesses?
    - How frequently do you switch the models used by your agent harness?
    - What features are you looking for in an agentic harness?
    - Are there any features you've tried but have abandoned now? Why?
- At what point did you fully embrace this kind of agentic development you're practicing now, and why?
  - Probes:
    - Since when have you used AI/LLMs to write code?
    - Since when have you used AI/LLMs in general?
    - How do you feel about the current state of AI?
    - When do you think will the pace of development in the AI space start to decelerate?
- What things have had the biggest impact (positive or negative) on your pace of development?
  - Probes:
    - Change in agent/model capabilities?
      - What was the source of changes?
    - Change in agent tooling (MCP, skills, etc.)?
    - Modifications to prompts?
    - Motivation and ideas?
    - Agent coordination/orchestration?
    - Project / community management?
- How would you describe your agentic workflow? Could you walk me through a typical workflow from idea to commit?
  - Probes:
    - How do you decide what to develop?
    - How you implement it?
    - How do your prompts look like?
      - How much context do you add to each prompt?
      - What do you consider to be important context?
    - How you verify it?
      - Do you read all diffs?
    - How you commit it?
    - Do you use any automated code reviews?
    - Do you use issues and pull requests?
    - Are there any things that you always do manually?
      - If so, which ones, and why?
  - How much code do you write manually? How much other tasks do you do manually?
    - Probe:
      - Ideation
      - How much of the code you produce do you review yourself?
- How do you handle commits and commit messages?
  - Probes:
    - Do you instruct agents to use certain semantics, like Conventional Commits?
    - What leads to a commit? Do the agents decide, do you decide, do you use periodic checkpoints?
    - Do commits always reflect meaningful work units?
- What do you use each agent harnesses for?
  - Probes:
    - Do you use different agent harnesses for different tasks, like exploration and ideating, writing code, documentation, or tests?
- Some agent harnesses and workflows leave clear public traces, while others leave almost none. How well do your public traces reflect your actual use of AI/LLMs?
  - Probes:
    - Are there any specific agent harnesses or other uses of AI/LLMs that we wouldn't be able to see from our analysis?
      - If so, why?
    - Did you ever actively suppress or avoid such traces?
      - If so, why?
    - What would be aspect of your workflow that is most likely to be missed by our analysis?
- How did you customize your agent harnesses?
  - Probes:
    - Did you rely on stock behavior, or use custom instructions, configuration files, etc?
    - Did you use any other tooling around your agents?
- How do you split your time between multiple projects?
  - Probes:
    - Do you work on multiple projects in parallel or in a more serial manner?
    - Would you describe your workflow as mostly serial, loosely parallel, or highly parallel?
    - How does agentic development impact your ability to switch between projects?
- Do you use multiple agents in parallel?
  - Probes:
    - How many on average?
- How autonomous are your agents?
  - Probes:
    - How frequently do you interact with them? When or why?
    - Do you let them run continuously and/or overnight?
    - What's the longest stretch you've had an agent work continuously?
- How do you think agentic development affects the code quality of your work?
  - Probes:
    - Would you consider the quality of your agentic code to be higher or lower than code you manually write?
    - How much does code quality matter to you and your workflow?
- Would you say it's beneficial to produce a lot of commits?
  - Probes:
    - Are you in some way concerned about what your GitHub activity shows?
    - When did you become aware of your unusually high (GitHub) activity?
- What is your opinion of the term "vibe coding"?
  - Probes:
    - Is there a difference between vibe coding and agentic development?
    - Is agentic development a super-charged version of vibe coding, or a fundamentally different approach?
    - Have you ever considered yourself a vibe coder?
- (Do you consider yourself a rare type of developer, or the beginning of a new era?)
- How much *time* do you currently spend on agentic development?
  - Probes:
    - Do you see any kind of trend here?
- How much *money* do you currently spend on agentic development?
  - Probes:
    - Are you planning to increase your spending further?
    - Do you feel like you're getting a lot value for money?
- Which of your projects are "in production" and customer-facing?
  - Probes:
    - Do you treat these projects any differently from your non-production projects?
    - Are there significant structural differences between agentic and (past) non-agentic projects in this regard?
- (Why???)
- What concerns, if any, do you have about your workflow or its long-term impact/sustainability?
  - Probes:
    - Quality? Burnout? Control?
    - Do you sometimes feel like your agents are producing too much code?
    - How do you feel about the safety and security of your code?
    - Would you consider agentic development in general and your workflow in particular to be "mature"?
- How do you feel about the ownership and copyright of your code?
  - Probes:
    - Who would you say has written your code?
    - Who has developed your projects? You, or you and [Claude]?
- Could you summarize the strengths and challenges you see in this new approach to development?
  - Probes:
    - Is there anything you wish agents/models could do, but currently can't?
- What do predict will the future of agentic coding look like?
  - Probes:
    - Can you give a rough timeline you think might be realistic?
- Is there anything important about your workflow that we haven't yet discussed?
  - Probes:
    - Anything that should be mentioned when we publish our analysis?

### Final Interview Guide

> [!TIP]
> - If answers are abstract, ask for at least one specific example
> - Treat results as hypotheses, not facts
> - Instead of "why?", ask:
>   - "so how did that work / look like"
>   - "what were your reasons for this?"
>   - "could you elaborate?"
> - If they talk a lot, ask for a specific example to discuss

#### Introduction & Warmup

In this interview, we want to understand the recent rise of highly active developers in agentic coding by discussing your workflow. We have already analyzed your public GitHub traces and would like to validate or challenge those findings, while also covering context that repository data alone cannot reveal. We're especially interested in your motivation for and approach to agentic development!

- [ ] 1. **So what is your (coding) background?**
  - Follow-Up:
    - Did you have any prior coding experience?
    - What (or who) got you into agentic development?
- [ ] 2. What would you say, how extensive is your AI usage?
  - Probes:
    - On a scale out of 10, where 1 is absolutely no machine learning and 10 is no human in the loop, where do you see yourself?
- [ ] 3. Could you tell me what you worked on between September 2025 and April 2026?
  - Probes:
    - Which projects were most important to you during that time?
    - How do you feel about this period, especially early on, looking back from today?

#### Theme 1: Agentic workflow basics - why, since when, which agent harnesses

- [ ] 3. **At what point did you fully embrace this kind of agentic development you're practicing now, and why?**
  - *Addresses: RQ1, why did they start with agentic?*
  - Follow-Up:
    - Since when have you used AI/LLMs to write code?
    - Since when have you used AI/LLMs in general?
    - How capable is AI right now?
    - When do you think will the pace of development in the AI space start to decelerate?
- [ ] 3. **What things have had the biggest impact (positive or negative) on your pace of development in recent months?**
  - *Addresses: RQ1, motivation, where is this incredible acceleration coming from?*
  - Probes:
    - Change in agent/model capabilities?
    - Change in agent tooling (MCP, skills, etc.)?
    - Modifications to prompts?
    - Motivation and ideas?
    - Agent coordination/orchestration?
    - Project / community management?
  - Follow Up:
    - What was the source of changes in capabilities?
- [ ] 3. **Our analysis shows that you were primarily using INSERT_PRIMARY_AGENT for development. Could you elaborate on why you use that?** (is that a loaded question?)
  - *Addresses: RQ2, confirms the primary agent harness, and other harnesses they've used*
  - Probes:
    - What features are you looking for in an agentic harness?
    - Are there any features you've tried but have abandoned now? Why?
  - Follow-Up:
    - What was the first agent harness you used?
    - Did you ever use any other agent harnesses?
      - Probe: Do/did you use different agent harnesses for different tasks, like exploration and ideating, writing code, documentation, or tests?
    - Have you found there to be any significant differences between agent harnesses?
    - How frequently do you switch the models used by your agent harness?

#### Theme 2: What are they doing? Workflow, tooling, projects, time splitting, autonomy

- [ ] 4. **How would you describe your agentic workflow? Could you walk me through a typical workflow from idea to commit?**
  - *Addresses: RQ1/2/4 how does their workflow look like? what are they doing?*
  - Probes:
    - How do you decide what to develop?
    - How you implement it?
    - How do your prompts look like?
      - How much context do you add to each prompt?
      - What do you consider to be important context?
    - How do you verify it?
      - Do you read all diffs?
    - How do you commit it?
      - Do you instruct agents to use certain semantics, like Conventional Commits?
      - What leads to a commit? Do the agents decide, do you decide, do you use periodic checkpoints? Do commits always reflect meaningful work units?
    - Do you use any automated code reviews?
    - Do you use issues and pull requests?
  - Follow-Up:
    - Are there any things that you do manually?
      - Probes:
        - If so, which ones, and why?
        - How much code do you write manually?
    - How did your workflow evolve?
- [ ] 5. (How well do your public traces reflect your actual use of AI/LLMs?)
  - *Addresses: RQ1+RQ2, How well does our analysis cover their workflow?*
  - Probes:
    - Some agent harnesses and workflows leave clear public traces, while others leave almost none. Where do you think your workflow lands on that range?
    - Are there any specific agent harnesses or other uses of AI/LLMs that we wouldn't be able to see from our analysis?
      - If so, why?
  - Follow-Up:
    - Did you ever actively suppress or avoid such traces?
      - If so, why?
    - What aspect of your workflow is most likely to be missed by our analysis?
- [ ] 6. **Is there anything particular about your agent harness or its configuration?**
  - *Addresses: RQ2, How do they customize their agent harness?*
  - Probes:
    - Did you rely on stock behavior, or use custom instructions, configuration files, etc?
    - Did you use any other tooling around your agents?
- [ ] 7. **How do you split your time between multiple projects?**
  - *Addresses: RQ4, context switching*
  - Probes:
    - Do you work on multiple projects in parallel or in a more serial manner?
    - Would you describe your workflow as mostly serial, loosely parallel, or highly parallel?
  - Follow-Up:
    - How does agentic development impact your ability to switch between projects?
- [ ] 8. **How do you deploy your agents?**
  - *Addresses: RQ4, orchestration*
  - Probes:
    - Do you use multiple agents in parallel?
      - How many on average?
    - How autonomous are your agents?
      - How frequently do you interact with them? When or why?
      - Do you let them run continuously and/or overnight?
      - What's the longest stretch you've had an agent work continuously?

#### Theme 3: Impact of agentic development

- [ ] 9. **How does agentic development affect the quality of your code?**
  - *Addresses: RQ3, quality of code*
  - Probes:
    - Would you consider the quality of your agentic code to be higher or lower than code you manually write?
  - Follow-Up:
    - How much does code quality matter to you and your workflow?
    - Which of your projects, if any, are facing actual customers?
      - Probes:
        - Do you treat these projects any differently from your other projects?
        - Are there significant structural differences between agentic and (past) non-agentic projects in this regard?
- [ ] 10. (How much *time* do you currently spend on agentic development (and how much *money*, if you are confortable discussing that)?)
  - *Addresses: RQ1, volume, temporal patterns, evolution*
  - SENSITIVE TOPIC!
  - Probes:
    - Do you see any kind of trend here?
    - Are you planning to increase your spending further?
    - Do you feel like you're getting a lot value for money?
- [ ] 11. **Do you have any concerns about your workflow or its long-term impact/sustainability?**
  - *Addresses: future trends*
  - Probes:
    - Quality? Burnout? Control?
    - Do you sometimes feel like your agents are producing too much code?
    - How do you feel about the safety and security of your code?
  - Follow-Up:
    - Would you consider agentic development in general and your workflow in particular to be "mature"?

#### Theme 4: Summary, Outlook & Outro

- [ ] 12. **Could you summarize the strengths and challenges you see in this new approach to development?**
  - *Addresses: motivation, stance on agentic development*
  - Follow-Up:
    - Is there anything you wish agents/models could do, but currently can't?
- [ ] 13. (What do you predict will the future of agentic coding look like?)
  - *Addresses: future trends*
  - Probes:
    - Can you give a rough timeline you think might be realistic?
- [ ] 14. **Is there anything important about your workflow that we haven't yet discussed?**
  - Probes:
    - Anything that should be mentioned when we publish our analysis?
- [ ] 15. Is it okay to follow up via [email] if we find that we have further questions or want to clarify something?

## Analyze public statements (podcasts, talks, blog posts)

- Pro:
  - Info is already there, we can just take it
  - Potentially lots of details
  - Potentially info on temporal patterns and changes
- Con:
  - Not available for all devs
  - Not exactly reliable or verifiable
  - Not a standard qualitative research method
  - Requires significant effort and time investment on our side (to find relevant media and work through it)

This should probably only be used as a **fallback** if we're unable to reach a person directly.

TODO collect links to podcasts, talks, blog posts *per developer*, possibly transcribe podcasts and talks to make them searchable (for filtering down to relevant media)

- obra:
  - https://rebuild.fm/424/
