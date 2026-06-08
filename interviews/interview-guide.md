# Interview Guide

> [!TIP]
> - If answers are abstract, ask for at least one specific example
> - Treat results as hypotheses, not facts
> - Instead of "why?", ask:
>   - "so how did that work / look like"
>   - "what were your reasons for this?"
>   - "could you elaborate?"
>   - Could you please tell me more about...
>   - You mentioned... Could you tell me more about that? What stands out in your mind about that?
> - Other probes:
>   - Tell me more about that.
>   - Why do you think that?
>   - I’m not quite sure I understood ... could you tell me about that some more?
>   - So what I hear you saying is...
>   - Could you give me some examples?
>   - Can you give me an example of...
>   - Why is that important to you?
>   - What makes you feel that way?
>   - What are some of your reasons for (dis)liking it?
>   - You just told me about...  I’d also like to know about...
> - If they talk a lot, ask for a specific example to discuss
> - Avoid stacked questions, prefer follow-ups if needed

### Introduction & Warmup

Thank you for agreeing to this interview. Just as a reminder, the interview will be recorded, but the recording won't be published. If all goes well, we will publish the combined insights from all our interviews, and might even reference or quote specific parts of this interview. You will of course stay anonymous, unless you would like to be mentioned specifically.

So the interview's purpose is to help us understand a recent dramatic increase of activity for some agentic developers. We have already analyzed your public GitHub traces and would like to either validate or challenge our findings, and also cover context that repository data alone cannot reveal. We're especially interested in your motivation for agentic development and your workflow.  
So this is about *your* thoughts on the matter, and there are no right or wrong answers.

In total, we'll aim for 30-60 minutes, if that's fine with you.  
Do you have any questions or comments before we get started?

- [ ] 1. **So what is your background?**
  - Probes:
    - TODO
  - Follow-Up:
    - Did you have any prior coding experience?
    - What (or who) got you into agentic development? (specific event or person that convinced/inspired you?)
- [ ] 2. What would you say, how extensive is your AI usage?
  - Probes:
    - On a scale out of 10, where 1 is absolutely no machine learning and 10 is no human in the loop, where do you see yourself?
- [ ] 3. Could you tell me what you worked on between September 2025 and April 2026?
  - Probes:
    - Which projects were most important to you during that time?
    - How do you feel about this period, especially early on, looking back from today?

### Theme 1: Agentic workflow basics - why, since when, which agent harnesses

- [ ] 4. **At what point did you fully embrace this kind of agentic development you're practicing now?**
  - *Addresses: RQ1, why did they start with agentic?*
  - Probes:
    - Was it a gradual process or a sudden change?
    - How does that timing relate to September 2025? Would you say it started before, around then, or afterward?
    - What lead you to embracing this workflow?
  - Follow-Up:
    - Since when have you used AI/LLMs to write code?
    - Since when have you used AI/LLMs in general?
    - How capable is AI right now?
    - When do you think will the pace of development in the AI space start to decelerate?
- [ ] 5. **What things have had the biggest impact (positive or negative) on your pace of development in recent months?**
  - *Addresses: RQ1, motivation, where is this incredible acceleration coming from?*
  - Probes:
    - Change in agent/model capabilities?
    - Change in agent tooling (MCP, skills, etc.)?
    - Modifications to prompts?
    - **Motivation and ideas?**
    - Agent coordination/orchestration?
    - **Project / community management?**
  - Follow Up:
    - What was the source of changes in capabilities?
- [ ] 6. **Our analysis shows that you were primarily using INSERT_PRIMARY_AGENT for development. How accurately does that reflect your workflow?**
  - *Addresses: RQ2, confirms the primary agent harness, and other harnesses they've used*
  - Probes:
    - Why do you use this/these agent(s)?
    - What features are you looking for in an agentic harness?
    - Are there any features you've tried but have abandoned now? Why?
  - Follow-Up:
    - What was the first agent harness you used?
    - Did you ever use any other agent harnesses?
      - Probe: Do/did you use different agent harnesses for different tasks, like exploration and ideating, writing code, documentation, or tests?
    - Have you found there to be any significant differences between agent harnesses?
    - How frequently do you switch the models used by your agent harness?

### Theme 2: What are they doing? Workflow, tooling, projects, time splitting, autonomy

- [ ] 7. **How would you describe your agentic workflow? Could you walk me through a typical workflow from idea to commit?**
  - *Addresses: RQ1/2/4 how does their workflow look like? what are they doing? **  grand-tour question!***
  - Probes:
    - **How do you decide what to develop?**
    - How you implement it?
    - How do your prompts look like?
      - How much context do you add to each prompt?
      - What do you consider to be important context?
    - How do you verify it?
      - Do you read all diffs?
    - How do you commit it?
      - **Do you instruct agents to use certain commit message semantics?**
        - Probes:
          - like Conventional Commits
      - **What leads to a commit?**
        - Probes:
          - Do the agents decide, do you decide?
          - Do you use periodic checkpoints?
          - Do commits always reflect meaningful work units?
    - Do you use any automated code reviews?
    - Do you use issues and pull requests?
  - Follow-Up:
    - Are there any things that you do manually?
      - Probes:
        - If so, which ones, and why?
        - **How much code do you write manually?**
    - How did your workflow evolve?
- [ ] 8. **Is there anything particular about your agent harness or its configuration?**
  - *Addresses: RQ2, How do they customize their agent harness?*
  - Probes:
    - Did you rely on stock behavior, or use custom instructions, configuration files, etc?
    - Did you use any other tooling around your agents?
- [ ] 9. **How do you split your time between multiple projects?**
  - *Addresses: RQ4, context switching*
  - Probes:
    - Do you work on multiple projects in parallel or in a more serial manner?
    - Would you describe your workflow as mostly serial, loosely parallel, or highly parallel?
  - Follow-Up:
    - How does agentic development impact your ability to switch between projects?
- [ ] 10. **How do you deploy your agents?**
  - *Addresses: RQ4, orchestration*
  - Probes:
    - Do you use multiple agents in parallel?
      - How many on average?
    - How autonomous are your agents?
      - How frequently do you interact with them? When or why?
      - Do you let them run continuously and/or overnight?
      - What's the longest stretch you've had an agent work continuously?

### Theme 3: Impact of agentic development

- [ ] 11. **How does agentic development affect the quality of your code?**
  - *Addresses: RQ3, quality of code*
  - Probes:
    - Would you consider the quality of your agentic code to be higher or lower than code you manually write?
  - Follow-Up:
    - How much does code quality matter to you and your workflow?
    - Which of your projects, if any, are facing actual customers?
      - Probes:
        - Do you treat these projects any differently from your other projects?
        - Are there significant structural differences between agentic and (past) non-agentic projects in this regard?
- [ ] 12. **Do you have any concerns about your workflow or its long-term impact/sustainability?**
  - *Addresses: future trends*
  - Probes:
    - Quality? Burnout? Control?
    - Do you sometimes feel like your agents are producing too little or too much code?
    - **How do you feel about the safety and security of your code?**
      - Can you think of a situation where these concerns affected a real decision you made about your workflow?
  - Follow-Up:
    - How would you describe the current level of maturity of agentic development in general, and of your own workflow?

### Theme 4: Optional Questions

*Only ask these if there's enough time/motivation, or they're really relevant for the participant!*

- [ ] 13. To what extend do you think your public activity, for example on GitHub, reveals that you are using agents for development, if at all?
  - *Addresses: RQ1+RQ2, How well does our analysis cover their workflow?*
  - Probes:
    - Some agent harnesses and workflows leave clear public traces, while others leave almost none. Where do you think your workflow lands on that range?
      - If so, why?
  - Follow-Up:
    - Did you ever actively suppress or avoid such traces?
      - If so, why?
    - **What aspect of your workflow is most likely to be missed by our analysis?**
      - Probes:
        - Are there any specific agent harnesses or other uses of AI/LLMs that we wouldn't be able to see from our analysis?
- [ ] 14. How much *time* do you currently spend on agentic development (and how much *money*, if you are confortable discussing that)?
  - *Addresses: RQ1, volume, temporal patterns, evolution*
  - SENSITIVE TOPIC!
  - Probes:
    - Do you see any kind of trend here?
    - Are you planning to increase your spending further?
    - Do you feel like you're getting a lot value for money?

### Theme 5: Summary, Outlook & Outro

- [ ] 15. **Could you summarize the strengths and challenges you see in this new approach to development?**
  - *Addresses: motivation, stance on agentic development*
  - Follow-Up:
    - Is there anything you wish agents/models could do, but currently can't?
- [ ] 16. (What do you predict will the future of agentic coding look like?)
  - *Addresses: future trends*
  - Probes:
    - Can you give a rough timeline you think might be realistic?
- [ ] 17. **Is there anything important about your workflow that we haven't yet discussed?**
  - Probes:
    - Anything that should be mentioned when we publish our analysis?
    - Anything you consider unique about your workflow?
- [ ] 18. **Is it okay to follow up via [email] if we find that we have further questions or want to clarify something?**

We'll let you know once we have any results to share.
