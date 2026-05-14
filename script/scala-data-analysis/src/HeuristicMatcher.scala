package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import whataretheydoing.AgentHeuristic

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

object HeuristicMatcher {

  private val coauthorPattern: Regex =
    """(?im)^\s*co-?authored-?by:\s*(.*?)\s*<([^>]+)>\s*$""".r

  def normalize(s: String): String = s.trim.toLowerCase

  def matchPattern(pattern: String, text: String): Boolean =
      val patNorm  = normalize(pattern)
      val textNorm = normalize(text)
      if patNorm.isEmpty then return false

      // Regex mode via explicit prefix
      if patNorm.startsWith("re:") then
          try
              val regex = patNorm.substring(3).r
              regex.findFirstIn(textNorm).isDefined
          catch
              case _: Exception => textNorm.contains(patNorm)
      // Glob mode
      else if patNorm.exists(ch => "*?[]".contains(ch)) then
          globMatch(patNorm, textNorm)
      // Default: substring
      else
          textNorm.contains(patNorm)

  private def globMatch(pattern: String, text: String): Boolean =
      // Simple glob to regex conversion
      val regex = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
      try
          ("^" + regex + "$").r.findFirstIn(text).isDefined
      catch
          case _: Exception => text.contains(pattern)

  def loadHeuristics(agentsDir: Path): Map[String, List[AgentHeuristic]] =
      val stream = Files.list(agentsDir)
      try
        stream.iterator().asScala
          .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json"))
          .map { file =>
            val jsonBytes      = Files.readAllBytes(file)
            val heuristicsJson = readFromArray[List[AgentHeuristic]](jsonBytes)
            val agentName      = file.getFileName.toString.stripSuffix(".json")
            heuristicsJson.foreach { heuristic =>
              assert(heuristic.period_start == "", s"Heuristic $agentName has a non-empty period_start")
              assert(heuristic.period_end == None, s"Heuristic $agentName has a non-empty period_end")
            }
            (agentName, heuristicsJson)
          }.toMap
      finally
        stream.close()

  def detectAgents(
      commitMessage: String,
      commitAuthor: String,
      filenames: List[String],
      heuristicsByAgent: Map[String, List[AgentHeuristic]]
  ): Set[String] =
      val matched = mutable.ListBuffer[String]()
      for (agentName, heuristics) <- heuristicsByAgent do
          var found = false
          for h <- heuristics if !found do
              // Check author identity and message prefixes
              if matchCommitHeuristic(commitMessage, commitAuthor, h) then
                  matched += agentName
                  found = true
              else if filenames.nonEmpty && !found then
                  // Check file patterns
                  for pat <- h.files do
                      for fn <- filenames do
                          if matchPattern(pat, fn) then
                              matched += agentName
                              found = true
      matched.toSet

  private def matchCommitHeuristic(
      commitMessage: String,
      commitAuthor: String,
      h: AgentHeuristic
  ): Boolean =

      // 1) Author identity
      if h.author_names.exists(n => matchPattern(n, commitAuthor)) then return true
      if h.author_mails.exists(m => matchPattern(m, commitAuthor)) then return true

      val coauthors = coauthorPattern.findAllMatchIn(commitMessage).map { m =>
        (name = normalize(m.group(1).nn), mail = normalize(m.group(2).nn))
      }
      // 2) Co-authors in message
      if coauthors.exists { case (coName, coMail) =>
            h.author_names.exists(n => matchPattern(n, coName)) ||
            h.author_mails.exists(m => matchPattern(m, coMail))
          }
      then return true

      // 3) Commit message prefixes
      if h.commit_message_prefix.exists(p => matchPattern(p, commitMessage)) then return true

      false
}
