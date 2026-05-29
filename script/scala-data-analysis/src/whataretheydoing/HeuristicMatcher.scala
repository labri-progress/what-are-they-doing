package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import whataretheydoing.AgentHeuristic
import whataretheydoing.DataAnalysis.time
import whataretheydoing.definitions.CustomHeuristics

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

object HeuristicMatcher {

  enum SignalType:
      case CommitAuthor, Committer, CoAuthoredBy, SignedOffBy, ExecutedBy, Role, AssistedBy, TrailerValueMatch, CommitMessage, Files

  private val trailerPersonPattern: Regex = """^\s*(.+?)\s*<\s*([^>]+)\s*>\s*$""".r
  private val assistedByPattern: Regex = """^\s*(.+?)\s+\(([^)]+)\)\s*$""".r

  lazy val baseHeuristics: Map[String, AgentHeuristic] =
    time("load heuristics")(HeuristicMatcher.loadHeuristics(GlobalPaths.heuristics))

  lazy val heuristicsByAgent: Map[String, AgentHeuristic] =
    import de.rmgk.Associative.mapAssoc
    mapAssoc.combine(baseHeuristics, CustomHeuristics.customHeuristics)

  def parseNameEmail(value: String): (name: String, mail: String) =
    value.trim match
      case trailerPersonPattern(name, mail) => (normalize(name.nn), normalize(mail.nn))
      case other                           => (normalize(other), "")

  def normalize(s: String): String = s.trim.toLowerCase

  def matchPattern(pattern: String, text: String): Boolean =
      val patNorm  = normalize(pattern)
      val textNorm = normalize(text)
      if patNorm.isEmpty then false
      else if patNorm.startsWith("re:") then patNorm.substring(3).r.findFirstIn(textNorm).isDefined
      else globMatch(patNorm, textNorm)

  private def globMatch(pattern: String, text: String): Boolean =
    pattern
      .split("\\*", -1)
      .iterator
      .filter(_.nonEmpty)
      .foldLeft(0 -> true) { case ((fromIndex, matched), part) =>
        if !matched then (fromIndex, false)
        else
            val nextIndex = text.indexOf(part, fromIndex)
            if nextIndex < 0 then (fromIndex, false)
            else (nextIndex + part.length, true)
      }
      ._2

  def loadHeuristics(agentsDir: Path): Map[String, AgentHeuristic] =
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
            assert(heuristicsJson.sizeIs == 1)
            (agentName, heuristicsJson.head)
          }.toMap
      finally
        stream.close()

  def extractTrailerValues(trailers: Seq[(key: String, value: String)], key: String): Seq[(String, String)] =
    trailers.collect {
      case (k, v) if k == key => parseNameEmail(v)
    }

  def detectTrailerSignals(
      trailers: Seq[(key: String, value: String)],
      h: AgentHeuristic
  ): Set[SignalType] =
      val coauthors  = extractTrailerValues(trailers, "co-authored-by")
      val signedOffs = extractTrailerValues(trailers, "signed-off-by")
      val executedBys = trailers.collect { case ("executed-by", v) => normalize(v) }
      val roles       = trailers.collect { case ("role", v) => normalize(v) }

      val coauthorSignal =
        if coauthors.exists { case (coName, coMail) =>
              h.author_names.exists(n => matchPattern(n, coName)) ||
              h.author_mails.exists(m => matchPattern(m, coMail))
            }
        then Set(SignalType.CoAuthoredBy)
        else Set.empty

      val signedOffBySignal =
        if signedOffs.exists { case (soName, soMail) =>
              h.author_names.exists(n => matchPattern(n, soName)) ||
              h.author_mails.exists(m => matchPattern(m, soMail))
            }
        then Set(SignalType.SignedOffBy)
        else Set.empty

      val executedBySignal =
        if executedBys.nonEmpty && h.executed_by_prefixes.exists(prefix =>
              executedBys.exists(eb => matchPattern(prefix, eb))
            )
        then Set(SignalType.ExecutedBy)
        else Set.empty

      val roleSignal =
        if roles.nonEmpty && h.role_prefixes.exists(prefix =>
              roles.exists(r => matchPattern(prefix, r))
            )
        then Set(SignalType.Role)
        else Set.empty

      val assistedBySignal =
        val parsed = trailers.collect { case ("assisted-by", v) =>
          v.trim match
            case assistedByPattern(model, tool) => (normalize(model.nn), normalize(tool.nn))
            case _                             => (normalize(v), "")
        }
        if h.assisted_by_patterns.exists((pfxModel, pfxTool) =>
              parsed.exists((model, tool) => matchPattern(pfxModel, model) && matchPattern(pfxTool, tool))
            )
        then Set(SignalType.AssistedBy)
        else Set.empty

      val trailerPrefixSignal =
        if h.trailer_prefixes.nonEmpty then
          val matched = h.trailer_prefixes.exists { (trailerKey, prefixes) =>
            val values = trailers.collect { case (k, v) if k == trailerKey => normalize(v) }
            values.exists(v => prefixes.exists(p => matchPattern(p, v)))
          }
          if matched then Set(SignalType.TrailerValueMatch) else Set.empty
        else Set.empty

      coauthorSignal ++ signedOffBySignal ++ executedBySignal ++ roleSignal ++ assistedBySignal ++ trailerPrefixSignal

  def detectAgents(
      commit: CommitEntry,
      detail: CommitDetail,
      trailers: Seq[(key: String, value: String)],
  ): Map[String, Set[SignalType]] =
    heuristicsByAgent.iterator.flatMap { case (agentName, heuristics) =>
      val signals = detectSignals(commit, detail, trailers, heuristics)
      if signals.nonEmpty then Some(agentName -> signals) else None
    }.toMap

  private def detectSignals(
      commit: CommitEntry,
      detail: CommitDetail,
      trailers: Seq[(key: String, value: String)],
      h: AgentHeuristic
  ): Set[SignalType] =
      val commitMessage   = detail.message.getOrElse(commit.commit.message)
      val commitAuthor    = s"${commit.commit.author.name} <${commit.commit.author.email}>"
      val commitCommitter = s"${commit.commit.committer.name} <${commit.commit.committer.email}>"
      val filenames       = detail.files.map(_.filename)

      val authorSignal =
        if h.author_names.exists(n => matchPattern(n, commitAuthor)) || h.author_mails.exists(m =>
              matchPattern(m, commitAuthor)
            )
        then Set(SignalType.CommitAuthor)
        else Set.empty

      val committerSignal =
        if h.author_names.exists(n => matchPattern(n, commitCommitter)) || h.author_mails.exists(m =>
              matchPattern(m, commitCommitter)
            )
        then Set(SignalType.Committer)
        else Set.empty

      val trailerSignals = detectTrailerSignals(trailers, h)

      val messageSignal =
        if h.commit_message_prefix.exists(p => matchPattern(p, commitMessage)) then Set(SignalType.CommitMessage)
        else Set.empty

      val fileSignal =
        if filenames.nonEmpty && h.files.exists(pat => filenames.exists(fn => matchPattern(pat, fn))) then
            Set(SignalType.Files)
        else Set.empty

      authorSignal ++ committerSignal ++ trailerSignals ++ messageSignal ++ fileSignal
}
