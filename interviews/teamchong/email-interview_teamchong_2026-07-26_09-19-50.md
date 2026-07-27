1. **Your case is quite unique, since our analysis shows barely any agent harness signals in your development activity. Claude Code, Cursor, and OpenCode make a few appearances, but only account for less than 1% of your commits. How accurately does that reflect your workflow? How extensive do you really use AI for development, behind the scenes?**

    I use Claude Code almost exclusively, it's the most cost-effective way to get AI leverage given my workload. The reason it barely shows up in your harness-signal analysis is that I turned off the "Co-Authored-By: Claude" commit attribution in my Claude Code config, so my commits don't carry that signature even though I'm using it constantly behind the scenes.

2. **What things have had the biggest impact (positive or negative) on your pace of development during that time?**

    My productivity has been up 10x or more.

    Two things drove that:
    - Exploring multiple ideas within a well-defined scope, e.g. telling it to improve performance by removing features, then letting it keep iterating.
    - Using the LLM to pattern-match across domains. Given a problem, I'll ask how the gaming industry handles it, how nature handles it, how it's been resolved historically. Asking an LLM for a different perspective on a subject has been one of the most useful things I've found. Everyone has their own narrow area of expertise, but an LLM lets you step outside that limitation.

3. **Could you describe your agentic workflow in a few sentences? Specifically how you decide what to develop, how you implement it, how you verify it, and how you commit it.**

      Early on I didn't find LLMs that useful for my own work, since I'm already an expert in what I do. So I started spawning multiple LLM sessions in parallel instead.

      I oversaw every action and reviewed every line of code. That worked, but it took a ton of effort, like day-trading crypto or driving all day straight. My attention became the bottleneck, and it's easy to burn out that way.

      Next I tried the opposite: let it run wild and just review occasionally. The problem is I was no longer in the loop. I'd outsourced my own thinking and understanding, I no longer understood how things worked, and that made it harder to push things further myself.

      Now I've landed on something more mature: knowing when to let go and when to step back in, like managing a team. You have to experience both extremes to build that judgment.

4. **How do you split your time between multiple projects? Do you work on multiple projects in parallel or in a more serial manner?**

    I don't work across that many projects anymore. Partly because I want to spend more time with family, partly because the most important thing I've learned is how to manage agents, and that's a skill you can't learn by reading about it, only by doing it.

5. **Do you use multiple agents in parallel? How many on average (you can give a range)?**

    2 to 5 at a time, plus a few backup experiments I can pick up if I have extra time.

6. **How autonomous are your agents? How often do you interact with them? Do you let them run continuously and/or overnight?**

    For personal projects, I set up a loop once I have an idea and a defined goal, and let it run.
    For work, I usually split tasks into small pieces and assign them to individual agents.

7. **Do you have any concerns about your workflow or its long-term impact/sustainability?**

    Right now it works well because AI companies are chasing market share ahead of their IPOs, so they're handing out a lot of tokens to get people hooked on their ecosystem. That won't last forever. My hope is that open-source models eventually catch up.

---

Thanks for answering these questions and sharing your insights with us!

We do have a few follow-up questions where we'd love to dive deeper into certain aspects:

**Re: Question 1 (agent harness & models):** When using Claude Code, which models do you usually use with that? Only Opus-class, or a mix of different models? Also, if you are comfortable sharing this, how much time and money do you currently spend on agentic development? Do you see any kind of trend here?

**Re: Question 3 (agentic workflow):** Could you walk me through a typical workflow from idea to commit? Possibly using an example from a recent project? We are trying to understand how you go from idea to prompt to commits, step by step. This would also include how you ensure that your code is doing that its supposed to do, and how you decide what to commit and when, and what kind of prompts and skill you use.  
In addition to that, how much do you still do manually, both writing code and other tasks?

**Re: Question 6 (autonomy of agents):** You mentioned you set up a "loop" to keep the agent working on a personal project. How does that work exactly?

**Re: Question 7 (long-term concerns):** You already mentioned burnout in response to a previous question. Is that a concern for you right now, or do you feel like you have landed on a workflow that avoids that issue? Also, do you have any concerns about the impact or maintainability of your projects in the future?  
It would also be great if you could discuss what your plans for the future are, with regard to token prices increasing. You mentioned that you're hoping for open models to take up the mantle, but do you also have other strategies to cope with increasing cost in general?


---

Answers below. Happy for this to be on the record. Personal capacity only, none of this speaks for my employer.

Q1: models, time, money

Mostly Opus. I try every new model when it lands, but I keep coming back, Fable is better but it is too expensive.

Time: I prefer not to disclose.

Money: Claude Code Max for personal work. Flat subscription, so I never see a per request cost, and that changes how I experiment. I would test a lot less if I was watching a meter.

Trend: I don't really have one. I don't read about how other people work, so I have nothing to compare against. I just do my thing and learn by doing.

Q3: idea to commit

Recent example, pxpipe, a local proxy that experiments with visual context for LLMs. https://github.com/teamchong/pxpipe

Idea: it started as one thought experiment: if you ignored cost entirely, what would the ideal interface for an LLM be? Humans went from command line to GUI. LLMs never made that jump, and the context window is the model's UI. Weekend project, Sept 2025.

No planning. I tried spec driven development and it's not for me. Managing the specs introduces too much overhead. I plan everything in my mind and I do progressive disclosure to the agent instead.

Since there's no plan in record, I just ask the model to build something. Most of the time it's not what I wanted, but now there's an end to end flow to argue with, so we go through the details and reshape it until it's what I wanted.

There's not much prompt skill involved. I send messages full of typos. No MCP installed, no skills installed. I ignore advice from the internet, I think most of it is full of shit.

Context is the part that matters. I ask the model to research a topic and write it down as markdown so the next session starts from it. Calling that a knowledge base sounds cool, but it's folders and files.

Verification: I ask a model to check the result, have it generate an html page so I can walk through it, and I test by hand.

Manual work: not much left. It's not far off being a technical manager with a team.

Q6: the loop

You will see people online talking about the loop, graphs of this and that. Some of them are inventing buzz words so they sound smart. It's nothing like that.

Concretely: write the approaches you want to try into a file, then fan out one agent per approach, each in its own git worktree.
Still 2 to 5 at a time. They never talk to each other and share nothing, so there is nothing to coordinate.
Same trick works for benchmarking and for improvement passes. Things that used to take a year take hours.

That's the loop for me. Set the goal, have a model execute it with observability, have a different model verify, then I read the diffs and pick the winner.

Q7: burnout, maintainability, cost

Burnout: not a concern right now. I know my limit and I zoom out when I need a break.

Maintainability: I don't worry about it. I have seen far worse out of projects built and maintained entirely by humans.
I used to be a technical manager, and managing agents isn't much different from managing developers.
AI makes mistakes, humans make mistakes, and the same questions work on both. Walk me through this change. Why did you make that tradeoff.

LLM code quality is already better than a lot of humans. It still needs steering, because the context window limits what it can see, and it can't make the right call on information it doesn't have.

Token price: pxpipe cut mine by more than half. Open models catching up is part of it, but the bigger part is that every tier keeps getting better at a crazy speed, and in most cases you don't need the best one.
You want a cashier who's good at math. You don't need to hire a PhD for every job. Long term I'm not worried at all.
