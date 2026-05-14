package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.tototoshi.csv.CSVWriter

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, LocalDate, YearMonth}
import scala.collection.parallel.immutable.ParVector
import scala.jdk.CollectionConverters.*
import scala.util.Using

object DevAgentCommitTypes {

  enum CommitType:
      case Build, Chore, Ci, Docs, Feat, Fix, Perf, Refactor, Revert, Style, Test, Unknown

  case class ClassifiedCommit(
      agents: Set[String],
      commitType: CommitType,
      message: String,
      files: List[String]
  )

  case class PeriodCsvRow(
      developer: String,
      period_iso: String,
      total_commits: Int,
      agent: String,
      count: Int
  )

  case class CommitTypePeriodRow(
      periodIso: String,
      totalCommits: Int,
      countsByType: Map[CommitType, Int]
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
  val outputPath: Path  = repoRoot.resolve("figures").resolve("rq2")
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

  lazy val commitSignals: Map[String, ClassifiedCommit] = time("compute commit signals") {
    allCommits.to(ParVector).map { c => (c.sha, getCommitDetails(c)) }.to(Map)
  }

  lazy val allDays: Seq[LocalDate] = aggregateData.flatMap(_.data.days.keysIterator.map(LocalDate.parse(_).nn))

  private val conventionalCommitPattern =
    "^([a-z]+)(?:\\([^\\r\\n()]+\\))?(!)?:\\s+(.+)$".r

  def classifyCommitMessage(message: String): CommitType =
    message.linesIterator.nextOption() match
        case Some(conventionalCommitPattern(rawType, _, _)) =>
          rawType.nn.toLowerCase match
              case "build"    => CommitType.Build
              case "chore"    => CommitType.Chore
              case "ci"       => CommitType.Ci
              case "docs"     => CommitType.Docs
              case "feat"     => CommitType.Feat
              case "fix"      => CommitType.Fix
              case "perf"     => CommitType.Perf
              case "refactor" => CommitType.Refactor
              case "revert"   => CommitType.Revert
              case "style"    => CommitType.Style
              case "test"     => CommitType.Test
              case _          => CommitType.Unknown
        case _ => CommitType.Unknown

  private def getCommitDetails(commit: CommitEntry): ClassifiedCommit = {
    val detailFile = commitsPath.resolve(s"${commit.sha}.json")
    val detail     =
      if Files.exists(detailFile) then
          val bytes = Files.readAllBytes(detailFile)
          try readFromArray[CommitDetail](bytes)
          catch
              case _: Exception =>
                val files = readFromArray[List[CommitFile]](bytes)
                CommitDetail(message = Some(commit.commit.message), files = Some(files))
      else CommitDetail(message = Some(commit.commit.message), files = Some(Nil))

    val message      = detail.message.getOrElse(commit.commit.message)
    val changedFiles = detail.files.getOrElse(Nil).map(_.filename)
    val author       = commit.commit.author
    val commitAuthor = s"${author.name} <${author.email}>"
    val agents       = HeuristicMatcher.detectAgents(
      message,
      commitAuthor,
      changedFiles,
      heuristicsByAgent
    )

    ClassifiedCommit(
      agents = agents,
      commitType = classifyCommitMessage(message),
      message = message,
      files = changedFiles
    )
  }

  val commitTypeOrder = Vector(
    CommitType.Feat,
    CommitType.Fix,
    CommitType.Refactor,
    CommitType.Docs,
    CommitType.Test,
    CommitType.Perf,
    CommitType.Build,
    CommitType.Ci,
    CommitType.Style,
    CommitType.Chore,
    CommitType.Revert,
    CommitType.Unknown
  )

  val commitTypeColors: Map[CommitType, String] = Map(
    CommitType.Feat -> "#e76f51",
    CommitType.Fix -> "#f4a261",
    CommitType.Refactor -> "#2a9d8f",
    CommitType.Docs -> "#577590",
    CommitType.Test -> "#9b5de5",
    CommitType.Perf -> "#43aa8b",
    CommitType.Build -> "#4d908e",
    CommitType.Ci -> "#277da1",
    CommitType.Style -> "#90be6d",
    CommitType.Chore -> "#adb5bd",
    CommitType.Revert -> "#6c757d",
    CommitType.Unknown -> "#e9ecef"
  )

  def weekStart(day: LocalDate): LocalDate =
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
                val agents = commitSignals.get(commit.sha).map(_.agents).getOrElse(Set.empty)
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

  def commitTypeRowsForDeveloper(handle: String): Vector[CommitTypePeriodRow] = {
    val totalsByWeek =
      aggregateData.iterator
        .filter(_.dev == handle)
        .flatMap { case (_, _, _, snapshot) =>
          snapshot.days.iterator.map { case (day, dayData) =>
            (weekStart(LocalDate.parse(day)), dayData.total_count)
          }
        }
        .toVector
        .groupMapReduce(_._1)(_._2)(_ + _)

    val countsByWeekType =
      aggregateData.iterator
        .filter(_.dev == handle)
        .flatMap { case (_, _, _, snapshot) =>
          snapshot.days.iterator.flatMap { case (day, dayData) =>
            val week = weekStart(LocalDate.parse(day))
            dayData.commits.iterator.map { commit =>
              val commitType = commitSignals.get(commit.sha).map(_.commitType).getOrElse(CommitType.Unknown)
              ((week, commitType), 1)
            }
          }
        }
        .toVector
        .groupMapReduce(_._1)(_._2)(_ + _)

    totalsByWeek.keys.toVector.sorted.map { week =>
      CommitTypePeriodRow(
        periodIso = week.toString,
        totalCommits = totalsByWeek(week),
        countsByType = commitTypeOrder.map(t => t -> countsByWeekType.getOrElse((week, t), 0)).toMap
      )
    }
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
