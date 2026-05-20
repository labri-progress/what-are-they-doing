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
