package whataretheydoing

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
    )
  )
}
