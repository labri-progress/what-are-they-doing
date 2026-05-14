package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.tototoshi.csv.CSVWriter

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, LocalDate, YearMonth}
import java.time.format.DateTimeFormatter
import scala.collection.parallel.immutable.{ParMap, ParVector}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object DevData {

  case class PeriodCsvRow(
      developer: String,
      period_iso: String,
      total_commits: Int,
      agent: String,
      count: Int
  )

// ── CSV output ─────────────────────────────────────────────────────────────

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
  lazy val developers: List[DevSummary] = time("load devs"):
      val developersJson = Files.readAllBytes(devFile)
      readFromArray[List[DevSummary]](developersJson)

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

  def allCommits = aggregateData.iterator.flatMap(_.data.days.valuesIterator.flatMap(_.commits))

  lazy val commitSignals: ParMap[String, Set[String]] = time("compute commit signals") {
    allCommits.to(ParVector).map { c => (c.sha, getCommitDetails(c)) }.toMap
  }

  lazy val allDays: Seq[LocalDate] = aggregateData.flatMap(_.data.days.keysIterator.map(LocalDate.parse(_).nn))

  private def getCommitDetails(commit: CommitEntry): Set[String] = {
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
    HeuristicMatcher.detectAgents(
      detailMessage,
      commitAuthor,
      detailFiles,
      heuristicsByAgent
    )
  }

  private def weekStart(day: LocalDate): LocalDate =
    day.`with`(DayOfWeek.MONDAY)

  private def periodRows: Vector[PeriodCsvRow] =
    time("build weekly period rows") {
      val totalsByDeveloperWeek =
        aggregateData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.map { case (day, dayData) =>
              ((developer, weekStart(LocalDate.parse(day))), dayData.total_count)
            }
          }
          .toVector
          .groupMapReduce(_._1)(_._2)(_ + _)

      val countsByDeveloperWeekAgent =
        aggregateData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.flatMap { case (day, dayData) =>
              val week = weekStart(LocalDate.parse(day))
              dayData.commits.iterator.flatMap { commit =>
                val agents = commitSignals.getOrElse(commit.sha, Set.empty)
                val labels = if agents.nonEmpty then agents else Set("no agent")
                labels.iterator.map(agent => ((developer, week, agent), 1))
              }
            }
          }
          .toVector
          .groupMapReduce(_._1)(_._2)(_ + _)

      countsByDeveloperWeekAgent.toVector
        .sortBy { case ((developer, week, agent), _) => (developer, week, agent) }
        .map { case ((developer, week, agent), count) =>
          PeriodCsvRow(
            developer = developer,
            period_iso = week.toString,
            total_commits = totalsByDeveloperWeek((developer, week)),
            agent = agent,
            count = count
          )
        }
    }

  private def writePeriodsCsv(rows: Seq[PeriodCsvRow], path: Path): Unit = {
    val writer = CSVWriter.open(path.toFile)
    try
        writer.writeRow(List("developer", "period_iso", "total_commits", "agent", "count"))
        rows.foreach { row =>
          writer.writeRow(
            List(
              row.developer,
              row.period_iso,
              row.total_commits.toString,
              row.agent,
              row.count.toString
            )
          )
        }
    finally writer.close()
  }

  @main def makeWeeklyPlotCsv(): Unit = {

    println(s"Running with dataDir=${dataPath.toString} granularity=week")

    Files.createDirectories(outputPath)

    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    val rows       = periodRows
    val outputFile = outputPath.resolve("agent-coevolution-periods.csv")
    writePeriodsCsv(rows, outputFile)

    println(s"Wrote ${rows.size} weekly rows to $outputFile")
  }

}
