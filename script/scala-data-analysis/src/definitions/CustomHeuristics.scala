package whataretheydoing.definitions

import whataretheydoing.AgentHeuristic

object CustomHeuristics {

  val customHeuristics: Map[String, AgentHeuristic] = Map(
    "claude_code" -> AgentHeuristic(
      author_names = Nil,
      author_mails = List("reviewer@claude-dev.local"),
      files = Nil,
      branch_name_prefix = Nil,
      commit_message_prefix = Nil,
      period_start = "",
      period_end = None
    ),
    "gastown" -> AgentHeuristic(
      data_source = Some("https://github.com/gastownhall/gastown/blob/main/README.md"),
      author_names = Nil,
      author_mails = Nil,
      files = Nil,
      branch_name_prefix = Nil,
      commit_message_prefix = Nil,
      period_start = "",
      period_end = None,
      executed_by_prefixes = List("gastown/", "beads/", "mayor"),
      role_prefixes = List("crew", "polecats", "refinery", "mayor", "witness", "boot")
    ),
    "copilot" -> AgentHeuristic(
      author_names = Nil,
      author_mails = Nil,
      files = Nil,
      branch_name_prefix = Nil,
      commit_message_prefix = Nil,
      period_start = "",
      period_end = None,
      agent_log_url_patterns = List("github.com")
    )
  )


}
