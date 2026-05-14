package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.time.{LocalDate, YearMonth}
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

object DevData {
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
          Files.writeString(outPath, (header :: lines).mkString("\n") + "\n", StandardCharsets.UTF_8)
          ()

      def writeSummaryCsv(
          summaries: List[DevSummary],
          outPath: Path
      ): Unit =
          val header =
            "developer,total_snapshot,total_records,agent_records,agent_pct,top_agent,top_agent_count,periods\n"
          val lines = summaries.map { s =>
            s"${s.developer},${s.totalSnapshot},${s.totalRecords},${s.agentRecords},${f"${s.agentPct}%.1f"},${escape(s.topAgent)},${s.topAgentCount},${s.periods}"
          }
          Files.writeString(outPath, (header :: lines).mkString("\n") + "\n", StandardCharsets.UTF_8)
          ()

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

  inline def time[A](label: String)(inline body: A): A = {
    val start  = System.nanoTime()
    val result = body
    val end    = System.nanoTime()
    println(s"$label: ${(end - start) / 1000000.0} ms")
    result
  }

  val granularity       = "auto"
  val repoRoot: Path    = Path.of("").toAbsolutePath.normalize.getParent.getParent
  val dataPath: Path    = repoRoot.resolve("data")
  val heuristics: Path  = repoRoot.resolve("agent-mining/agents")
  val commitsPath: Path = repoRoot.resolve("data/commits")
  val devFile: Path     = repoRoot.resolve("developers.json")
  val outputPath: Path  = repoRoot.resolve("figures")
  val startDate         = ""
  val endDate           = ""

  // Load developers
  lazy val developers: List[DeveloperEntry] = time("load devs"):
      val developersJson = Files.readAllBytes(devFile)
      readFromArray[List[DeveloperEntry]](developersJson)
  lazy val trackedHandles: Set[String] = developers.map(_.handle).toSet

  lazy val heuristicsByAgent: Map[String, List[AgentHeuristic]] =
    time("load heuristics")(HeuristicMatcher.loadHeuristics(heuristics))

  lazy val aggregateData: Vector[(dev: String, month: YearMonth, path: Path, data: MonthlySnapshot)] =
    time("loading aggregate data") {
      val snapshotPattern = raw"(.+)-(\d{4}-\d{2})".r
      val jsonsFiles      = Using(Files.list(dataPath)) {
        _.iterator().asScala.flatMap { path =>
          if Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json")
          then
              snapshotPattern.findFirstMatchIn(path.getFileName.toString.stripSuffix(".json")) match {
                case Some(m) =>
                  Some((developer = m.group(1).nn, month = YearMonth.parse(m.group(2)).nn, path = path))
                case _ => None
              }
          else None
        }.toVector
      }.get

      jsonsFiles.flatMap { (dev, month, path) =>
        if trackedHandles.contains(dev) then
            Some((dev, month, path, readFromArray[MonthlySnapshot](Files.readAllBytes(path))))
        else None
      }.toVector
    }

  lazy val allDays: Seq[LocalDate] = aggregateData.flatMap(_.data.days.keysIterator.map(LocalDate.parse(_).nn))

  @main def run(): Unit =

      println(s"Running with dataDir=${dataPath.toString} granularity=$granularity")

      Files.createDirectories(outputPath)

      println(s"Tracked developers: ${trackedHandles.mkString(", ")}")

      // Load heuristics
      println(s"Loaded ${heuristicsByAgent.size} agent definitions")

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

          val devDataStream = Files.list(dataPath)
          try
            for
                file <- devDataStream.iterator().asScala
                if Files.isRegularFile(
                  file
                ) && file.getFileName.toString.endsWith(".json") && file.getFileName.toString.startsWith(s"$handle-")
            do
                val snapshot = readFromArray[MonthlySnapshot](Files.readAllBytes(file))
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
                            val detailFile                   = commitsPath.resolve(s"${commit.sha}.json")
                            val (detailMessage, detailFiles) =
                              if Files.exists(detailFile) then
                                  val bytes = Files.readAllBytes(detailFile)
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
          finally
            devDataStream.close()

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
      val periodCsv = outputPath.resolve("agent-coevolution-periods.csv")
      CsvWriter.writePeriodCsv(allPeriodRows.toList, periodCsv)
      println(s"\nPeriod CSV: $periodCsv (${allPeriodRows.size} rows)")

      val summaryCsv = outputPath.resolve("agent-coevolution-summary.csv")
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
}
