package whataretheydoing

import munit.FunSuite

class HeuristicMatcherTest extends FunSuite {

  val allSignedOffEntries: Seq[(String, String)] = Seq(
    "Andrew Anderson <andy@clubanderson.com>",
    "Andy Anderson <andy@clubanderson.com>",
    "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>",
    "Reviewer Agent <reviewer@claude-dev.local>",
    "Copilot <copilot@github.com>",
    "andy anderson <andy@andys-MacBook-Pro.local>",
    "Andy Anderson <clubanderson@gmail.com>",
    "ks-ci-bot <ks-ci-bot@users.noreply.github.com>",
    "clubanderson <clubanderson@users.noreply.github.com>",
    "Copilot <223556219+Copilot@users.noreply.github.com>",
    "Andy Anderson <andy@kubestellar.io>",
    "Andy Anderson <andrew.anderson@ibm.com>",
    "Assistant <noreply@anthropic.com>",
    "Kevin Roche <kproche@us.ibm.com>",
    "github-actions[bot] <github-actions[bot]@users.noreply.github.com>",
    "xonas1101 <aarushsingh1305@gmail.com>",
    "aashu2006 <akshatp439@gmail.com>",
    "Claude Opus 4.6 <noreply@anthropic.com>",
    "copilot <copilot@github.com>",
    "Andy Anderson <andy.anderson2@ibm.com>",
    "mrhapile <allinonegaming3456@gmail.com>",
    "AAdIprog <aadishah132@gmail.com>",
    "GitHub Copilot <copilot@github.com>",
    "Claude <noreply@anthropic.com>",
    "Andrew Anderson <andrew.anderson@ibm.com>",
    "Andrew Anderson <andy@kubestellar.io>",
    "Sakshar Dhawan <sakshardhawanfzk@gmail.com>",
    "yblzhua <1963225049730818048+yblzhua@users.noreply.github.com>",
    "Andan <andan@ibm.com>",
    "Andy <andy@users.noreply.github.com>",
    "llm-d-infra-sync <llm-d-infra-sync@users.noreply.github.com>",
    "Andy Anderson <andan02@gmail.com>",
    "clubanderson <andy@clubanderson.com>",
    "Rishi Mondal <mavrickrishi@gmail.com>"
  ).map((raw) => ("signed-off-by", raw))

  test("signed-off-by trailer values correctly detect actual bots/agents and reject humans") {
    val allAgents = DataAnalysis.heuristicsByAgent.values.toVector

    // ── Should be detected as an agent ──
    val shouldBeAgent = Set(
      "copilot"                 -> "copilot@github.com",
      "copilot"                 -> "223556219+copilot@users.noreply.github.com",
      "assistant"               -> "noreply@anthropic.com",
      "claude opus 4.6"         -> "noreply@anthropic.com",
      "claude"                  -> "noreply@anthropic.com",
      "github copilot"          -> "copilot@github.com",
      "reviewer agent"          -> "reviewer@claude-dev.local"
    )

    shouldBeAgent.foreach { case (name, mail) =>
      val matched = allAgents.flatMap(h => HeuristicMatcher.detectTrailerSignals(Seq.empty, Seq((name, mail)), h)).toSet
      assert(clue(matched).nonEmpty, s"'$name <$mail>' should be detected as an agent (matched: $matched)")
    }

    // ── Should NOT be detected as any agent ──
    val shouldBeHuman = Set(
      "andrew anderson"         -> "andy@clubanderson.com",
      "andy anderson"           -> "andy@clubanderson.com",
      "github-actions[bot]"     -> "41898282+github-actions[bot]@users.noreply.github.com",
      "andy anderson"           -> "andy@andys-macbook-pro.local",
      "andy anderson"           -> "clubanderson@gmail.com",
      "ks-ci-bot"               -> "ks-ci-bot@users.noreply.github.com",
      "clubanderson"            -> "clubanderson@users.noreply.github.com",
      "andy anderson"           -> "andy@kubestellar.io",
      "andy anderson"           -> "andrew.anderson@ibm.com",
      "kevin roche"             -> "kproche@us.ibm.com",
      "github-actions[bot]"     -> "github-actions[bot]@users.noreply.github.com",
      "xonas1101"               -> "aarushsingh1305@gmail.com",
      "aashu2006"               -> "akshatp439@gmail.com",
      "andy anderson"           -> "andy.anderson2@ibm.com",
      "mrhapile"                -> "allinonegaming3456@gmail.com",
      "aadiprog"                -> "aadishah132@gmail.com",
      "andrew anderson"         -> "andrew.anderson@ibm.com",
      "andrew anderson"         -> "andy@kubestellar.io",
      "sakshar dhawan"          -> "sakshardhawanfzk@gmail.com",
      "yblzhua"                 -> "1963225049730818048+yblzhua@users.noreply.github.com",
      "andan"                   -> "andan@ibm.com",
      "andy"                    -> "andy@users.noreply.github.com",
      "andy anderson"           -> "andan02@gmail.com",
      "clubanderson"            -> "andy@clubanderson.com",
      "rishi mondal"            -> "mavrickrishi@gmail.com"
    )

    shouldBeHuman.foreach { case (name, mail) =>
      val matched = allAgents.flatMap(h => HeuristicMatcher.detectTrailerSignals(Seq.empty, Seq((name, mail)), h)).toSet
      assert(clue(matched).isEmpty, s"'$name <$mail>' should NOT be detected as any agent (matched: $matched)")
    }
  }

  test("loaded heuristics contain all expected agents") {
    val expected = Set("claude_code", "copilot", "opencode", "codex", "cursor", "windsurf")
    expected.foreach(agent => assert(clue(DataAnalysis.heuristicsByAgent.contains(agent)), s"$agent heuristic should be loaded"))
  }
}
