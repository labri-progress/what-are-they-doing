import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import os.{Path, /}
import scala.collection.mutable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import scala.util.matching.Regex

// ── JSON codecs (jsoniter-scala) ───────────────────────────────────────────

object Codecs:
    given codecMapDayData: JsonValueCodec[Map[String, DayData]] = JsonCodecMaker.make

    given codecMonthlySnapshot: JsonValueCodec[MonthlySnapshot] =
      JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true))

    given codecCommitFiles: JsonValueCodec[List[CommitFile]] = JsonCodecMaker.make

    given codecCommitDetail: JsonValueCodec[CommitDetail] = JsonCodecMaker.make

    given codecHeuristicJson: JsonValueCodec[List[HeuristicJson]] = JsonCodecMaker.make

    given codecDeveloperEntry: JsonValueCodec[List[DeveloperEntry]] = JsonCodecMaker.make

import Codecs.given

// ── Data models for monthly snapshot JSON ──────────────────────────────────

case class MonthlySnapshot(
    developer: String,
    month: String,
    repos: Option[List[String]],
    days: Map[String, DayData]
)

case class DayData(
    total_count: Int,
    sampled: Option[Int],
    pages: Option[List[Int]],
    commits: List[CommitEntry]
)

case class CommitEntry(
    sha: String,
    node_id: Option[String],
    commit: CommitInfo,
    url: String,
    html_url: Option[String],
    author: Option[GitHubUser],
    committer: Option[GitHubUser]
)

case class CommitInfo(
    author: GitAuthor,
    committer: GitAuthor,
    message: String,
    tree: Option[TreeRef],
    url: String,
    comment_count: Option[Int],
    verification: Option[Verification]
)

case class GitAuthor(
    name: String,
    email: String,
    date: String
)

case class TreeRef(
    sha: String,
    url: String
)

case class Verification(
    verified: Boolean,
    reason: String,
    signature: Option[String],
    payload: Option[String],
    verified_at: Option[String]
)

case class GitHubUser(
    login: String,
    id: Long,
    node_id: Option[String],
    avatar_url: Option[String],
    gravatar_id: Option[String],
    url: String,
    html_url: Option[String],
    `type`: String
)

// ── Data models for commit detail cache ───────────────────────────────────

case class CommitDetail(
    message: Option[String],
    files: Option[List[CommitFile]]
)

case class CommitFile(
    sha: Option[String],
    filename: String,
    status: String,
    additions: Int,
    deletions: Int,
    changes: Int
)

// ── Data models for heuristic JSON ───────────────────────────────────────

case class HeuristicJson(
    author_names: List[String],
    author_mails: List[String],
    files: List[String],
    branch_name_prefix: List[String],
    commit_message_prefix: List[String],
    period_start: String,
    period_end: Option[String]
)

// ── Heuristic matching (ported from Python agent-mining) ─────────────────

object HeuristicMatcher {

  private val coauthorPattern: Regex =
    """(?im)^\s*co-?authored-?by:\s*(.*?)\s*<([^>]+)>\s*$""".r

  def normalize(s: String): String =
    if s == null then "" else s.trim.toLowerCase

  def iterCoauthors(message: String): List[(String, String)] =
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
              case _: Exception => normalize(patNorm.substring(3)).contains(textNorm)
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
        .replace("[", "[")
        .replace("]", "]")
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
      os.list(agentsDir).filter(_.ext == "json").foreach { file =>
        val jsonBytes      = os.read.bytes(file)
        val heuristicsJson = readFromArray[List[HeuristicJson]](jsonBytes)
        val agentName      = file.last.stripSuffix(".json")
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
      val ca = commitAuthor
      val cm = commitMessage

      // 1) Author identity
      for name <- h.authorNames do
          if matchPattern(name, ca) then return true
      for mail <- h.authorMails do
          if matchPattern(mail, ca) then return true

      // 2) Co-authors in message
      for (coName, coMail) <- iterCoauthors(cm) do
          for name <- h.authorNames do
              if matchPattern(name, coName) then return true
          for mail <- h.authorMails do
              if matchPattern(mail, coMail) then return true

      // 3) Commit message prefixes
      for prefix <- h.commitMessagePrefix do
          if matchPattern(prefix, cm) then return true

      false
}
// ── Date bucketing ─────────────────────────────────────────────────────────

enum Granularity:
    case Day, Week, Month

object DateBucket:
    private val weekFields = WeekFields.of(Locale.US)

    def bucketStart(d: LocalDate, g: Granularity): LocalDate = g match
        case Granularity.Day   => d
        case Granularity.Week  => d.`with`(weekFields.dayOfWeek(), 1L)
        case Granularity.Month => d.withDayOfMonth(1)

    def bucketLabel(d: LocalDate, g: Granularity): String = g match
        case Granularity.Day   => d.toString
        case Granularity.Week  => s"Week of ${d.toString}"
        case Granularity.Month => d.format(DateTimeFormatter.ofPattern("yyyy-MM"))

    def chooseGranularity(dayCount: Int, requested: Granularity): Granularity =
      if requested != Granularity.Day then requested
      else if dayCount <= 45 then Granularity.Day
      else if dayCount <= 370 then Granularity.Week
      else Granularity.Month

// ── CSV output ─────────────────────────────────────────────────────────────

object CsvWriter:
    def writePeriodCsv(
        rows: List[PeriodRow],
        outPath: Path
    ): Unit =
        val header = "developer,period_label,period_iso,total_commits,agent,count\n"
        val lines  = rows.map { r =>
          s"${r.developer},${escape(r.periodLabel)},${r.periodIso},${r.totalCommits},${escape(r.agent)},${r.count}"
        }
        os.write.over(outPath, (header :: lines).mkString("\n") + "\n")

    def writeSummaryCsv(
        summaries: List[DevSummary],
        outPath: Path
    ): Unit =
        val header =
          "developer,total_snapshot,total_records,agent_records,agent_pct,top_agent,top_agent_count,periods\n"
        val lines = summaries.map { s =>
          s"${s.developer},${s.totalSnapshot},${s.totalRecords},${s.agentRecords},${f"${s.agentPct}%.1f"},${escape(s.topAgent)},${s.topAgentCount},${s.periods}"
        }
        os.write.over(outPath, (header :: lines).mkString("\n") + "\n")

    private def escape(s: String): String =
      if s.contains(",") || s.contains("\"") || s.contains("\n") then
          "\"" + s.replace("\"", "\"\"") + "\""
      else s

case class PeriodRow(
    developer: String,
    periodLabel: String,
    periodIso: String,
    totalCommits: Int,
    agent: String,
    count: Int
)

case class DevSummary(
    developer: String,
    totalSnapshot: Int,
    totalRecords: Int,
    agentRecords: Int,
    agentPct: Double,
    topAgent: String,
    topAgentCount: Int,
    periods: Int
)

// ── Main analysis ──────────────────────────────────────────────────────────

@main def run(): Unit =
    val dataDir     = "data"
    val granularity = "auto"
    println(s"Running with dataDir=$dataDir granularity=$granularity")
    val repoRoot    = os.pwd / os.up
    val dataPath    = repoRoot / os.RelPath(dataDir)
    val agentsPath  = repoRoot / os.RelPath("agent-mining/agents")
    val commitsPath = repoRoot / os.RelPath("data/commits")
    val devFile     = repoRoot / os.RelPath("developers.json")
    val outputPath  = repoRoot / os.RelPath("figures")
    val startDate   = ""
    val endDate     = ""

    os.makeDir.all(outputPath)

    // Load developers
    val developersJson = os.read.bytes(devFile)
    val developers     = readFromArray[List[DeveloperEntry]](developersJson)
    val trackedHandles = developers.map(_.handle).toSet

    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")

    // Load heuristics
    val heuristicsByAgent = HeuristicMatcher.loadHeuristics(agentsPath)
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    // Determine date range
    val allDays         = mutable.ListBuffer[LocalDate]()
    val snapshotPattern = raw"(.+)-(\d{4}-\d{2})".r

    for file <- os.list(dataPath) if file.ext == "json" do
        snapshotPattern.findFirstMatchIn(file.last.stripSuffix(".json")) match
            case Some(m) if trackedHandles.contains(m.group(1).nn) =>
              val snapshot = readFromArray[MonthlySnapshot](os.read.bytes(file))
              for dayStr <- snapshot.days.keys do
                  allDays += LocalDate.parse(dayStr)
            case _ => ()

    if allDays.isEmpty then
        println("No snapshot data found.")
        sys.exit(1)

    val dataStart      = allDays.min
    val dataEnd        = allDays.max
    val start          = if startDate.isEmpty then dataStart else LocalDate.parse(startDate)
    val end            = if endDate.isEmpty then dataEnd else LocalDate.parse(endDate)
    val effectiveStart = if start.isBefore(dataStart) then dataStart else start
    val effectiveEnd   = if end.isAfter(dataEnd) then dataEnd else end

    if effectiveStart.isAfter(effectiveEnd) then
        println("--start must be on or before --end")
        sys.exit(1)

    val dayCount = effectiveEnd.toEpochDay - effectiveStart.toEpochDay + 1
    val gran     = DateBucket.chooseGranularity(
      dayCount.toInt,
      granularity match
          case "day"   => Granularity.Day
          case "week"  => Granularity.Week
          case "month" => Granularity.Month
          case _       => Granularity.Day
    )
    println(s"Granularity: $gran, Range: $effectiveStart to $effectiveEnd")

    // Process each developer
    val allPeriodRows = mutable.ListBuffer[PeriodRow]()
    val allSummaries  = mutable.ListBuffer[DevSummary]()

    for handle <- trackedHandles.toList.sorted do
        println(s"Processing @$handle...")

        val periodMap = mutable.Map[LocalDate, PeriodAccumulator]()

        for
            file <- os.list(dataPath)
            if file.ext == "json" && file.last.startsWith(s"$handle-")
        do
            val snapshot = readFromArray[MonthlySnapshot](os.read.bytes(file))
            for (dayStr, dayData) <- snapshot.days do
                val dayValue = LocalDate.parse(dayStr)
                if !dayValue.isBefore(effectiveStart) && !dayValue.isAfter(effectiveEnd) then
                    val bucket = DateBucket.bucketStart(dayValue, gran)
                    val acc    = periodMap.getOrElseUpdate(
                      bucket,
                      PeriodAccumulator(
                        label = DateBucket.bucketLabel(bucket, gran),
                        totalCommits = 0,
                        agentCounts = mutable.Map[String, Int]().withDefaultValue(0)
                      )
                    )
                    acc.totalCommits += dayData.total_count

                    for commit <- dayData.commits do
                        val detailFile                   = commitsPath / s"${commit.sha}.json"
                        val (detailMessage, detailFiles) =
                          if os.exists(detailFile) then
                              val bytes = os.read.bytes(detailFile)
                              // Try new format {message, files} first, fall back to old bare array format
                              val detail = try
                                  readFromArray[CommitDetail](bytes)
                              catch
                                  case _: Exception =>
                                    val files = readFromArray[List[CommitFile]](bytes)
                                    CommitDetail(message = Some(commit.commit.message), files = Some(files))
                              (
                                detail.message.getOrElse(commit.commit.message),
                                detail.files.getOrElse(Nil).map(_.filename)
                              )
                          else
                              (commit.commit.message, Nil)

                        val author       = commit.commit.author
                        val commitAuthor = s"${author.name} <${author.email}>"
                        val agents       = HeuristicMatcher.detectAgents(
                          detailMessage,
                          commitAuthor,
                          detailFiles,
                          heuristicsByAgent
                        )
                        if agents.nonEmpty then
                            for agent <- agents do
                                acc.agentCounts(agent) += 1
                        else
                            acc.agentCounts("no agent") += 1

        if periodMap.isEmpty then
            println(s"  No data for @$handle in range")
        else
            val sortedPeriods = periodMap.toList.sortBy(_._1)
            var totalSnapshot = 0
            var totalRecords  = 0
            var agentRecords  = 0
            val allAgents     = mutable.Map[String, Int]().withDefaultValue(0)

            for (bucket, acc) <- sortedPeriods do
                totalSnapshot += acc.totalCommits
                val records = acc.agentCounts.values.sum
                totalRecords += records
                agentRecords += (records - acc.agentCounts.getOrElse("no agent", 0))

                for (agent, count) <- acc.agentCounts do
                    if agent != "no agent" then allAgents(agent) += count
                    allPeriodRows += PeriodRow(
                      developer = handle,
                      periodLabel = acc.label,
                      periodIso = bucket.toString,
                      totalCommits = acc.totalCommits,
                      agent = agent,
                      count = count
                    )

            val topAgent = if allAgents.nonEmpty then allAgents.maxBy(_._2) else ("-", 0)
            val agentPct = if totalRecords > 0 then (agentRecords.toDouble / totalRecords) * 100.0 else 0.0

            allSummaries += DevSummary(
              developer = handle,
              totalSnapshot = totalSnapshot,
              totalRecords = totalRecords,
              agentRecords = agentRecords,
              agentPct = agentPct,
              topAgent = topAgent._1,
              topAgentCount = topAgent._2,
              periods = sortedPeriods.size
            )

            println(
              s"  @$handle: ${sortedPeriods.size} periods, $totalSnapshot snapshot commits, $totalRecords records, ${f"$agentPct%.1f"}% agent-attributed"
            )

    // Write CSV outputs
    val periodCsv = outputPath / "agent-coevolution-periods.csv"
    CsvWriter.writePeriodCsv(allPeriodRows.toList, periodCsv)
    println(s"\nPeriod CSV: $periodCsv (${allPeriodRows.size} rows)")

    val summaryCsv = outputPath / "agent-coevolution-summary.csv"
    CsvWriter.writeSummaryCsv(allSummaries.toList, summaryCsv)
    println(s"Summary CSV: $summaryCsv (${allSummaries.size} rows)")

    println("\nDone. Use the CSV files with your Python plotting script.")

// ── Supporting types ───────────────────────────────────────────────────────

case class DeveloperEntry(
    handle: String,
    repos: Option[List[String]] = None
)

case class PeriodAccumulator(
    label: String,
    var totalCommits: Int,
    agentCounts: mutable.Map[String, Int]
)
