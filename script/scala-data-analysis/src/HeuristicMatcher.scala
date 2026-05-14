package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import whataretheydoing.HeuristicJson

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex
import Codecs.given

object HeuristicMatcher {

  private val coauthorPattern: Regex =
    """(?im)^\s*co-?authored-?by:\s*(.*?)\s*<([^>]+)>\s*$""".r

  def normalize(s: String): String = s.trim.toLowerCase

  def iterCoauthors(message: String): List[(name: String, email: String)] =
    if message == null || message.isEmpty then Nil
    else
        coauthorPattern.findAllMatchIn(message).map { m =>
          (normalize(m.group(1).nn), normalize(m.group(2).nn))
        }.toList

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

  case class Heuristic(
      agentName: String,
      authorNames: List[String],
      authorMails: List[String],
      files: List[String],
      commitMessagePrefix: List[String]
  )

  def loadHeuristics(agentsDir: Path): Map[String, List[Heuristic]] =
      val result = mutable.Map[String, List[Heuristic]]()
      val stream = Files.list(agentsDir)
      try
          stream.iterator().asScala
            .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json"))
            .foreach { file =>
              val jsonBytes      = Files.readAllBytes(file)
              val heuristicsJson = readFromArray[List[HeuristicJson]](jsonBytes)
              val agentName      = file.getFileName.toString.stripSuffix(".json")
              val heuristics     = heuristicsJson.map { hj =>
                Heuristic(
                  agentName = agentName,
                  authorNames = hj.author_names,
                  authorMails = hj.author_mails,
                  files = hj.files,
                  commitMessagePrefix = hj.commit_message_prefix
                )
              }
              result(agentName) = heuristics
            }
      finally
          stream.close()
      result.toMap

  def detectAgents(
      commitMessage: String,
      commitAuthor: String,
      filenames: List[String],
      heuristicsByAgent: Map[String, List[Heuristic]]
  ): List[String] =
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
      matched.toList

  private def matchCommitHeuristic(
      commitMessage: String,
      commitAuthor: String,
      h: Heuristic
  ): Boolean =
      // 1) Author identity
      if h.authorNames.exists(n => matchPattern(n, commitAuthor)) then return true
      if h.authorMails.exists(m => matchPattern(m, commitAuthor)) then return true

      // 2) Co-authors in message
      val coauthors = iterCoauthors(commitMessage)
      if coauthors.exists { case (coName, coMail) =>
            h.authorNames.exists(n => matchPattern(n, coName)) ||
            h.authorMails.exists(m => matchPattern(m, coMail))
          }
      then return true

      // 3) Commit message prefixes
      if h.commitMessagePrefix.exists(p => matchPattern(p, commitMessage)) then return true

      false
}
