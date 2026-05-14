package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*

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
      sampled_commits: Int,
      agent: String,
      count: Int
  )

  case class AgentPeriodRow(
      periodIso: String,
      totalCommits: Int,
      sampledCommits: Int,
      countsByAgent: Map[String, Int]
  )

  case class CommitTypePeriodRow(
      periodIso: String,
      totalCommits: Int,
      sampledCommits: Int,
      countsByType: Map[CommitType, Int]
  )

  val agentColorOrder = Vector(
    "claude_code",
    "cursor",
    "copilot",
    "codex",
    "aider",
    "devin",
    "opencode",
    "windsurf",
    "amp",
    "gemini",
    "qwen_code",
    "roo_code",
    "sweep",
    "no agent"
  )

  val agentColors: Map[String, String] = Map(
    "claude_code" -> "#e76f51",
    "cursor" -> "#2a9d8f",
    "copilot" -> "#264653",
    "codex" -> "#f4a261",
    "aider" -> "#9b5de5",
    "devin" -> "#00bbf9",
    "opencode" -> "#fee440",
    "windsurf" -> "#00f5d4",
    "amp" -> "#577590",
    "gemini" -> "#adb5bd",
    "qwen_code" -> "#43aa8b",
    "roo_code" -> "#90be6d",
    "sweep" -> "#6c757d",
    "no agent" -> "#e9ecef"
  )

// ── Shared output helpers ──────────────────────────────────────────────────

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

      val sampledByDeveloperWeek =
        aggregateData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.map { case (day, dayData) =>
              ((developer, weekStart(LocalDate.parse(day))), dayData.commits.size)
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
            sampled_commits = sampledByDeveloperWeek.getOrElse((developer, week), 0),
            agent = agent,
            count = count
          )
        }
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

    val sampledByWeek =
      aggregateData.iterator
        .filter(_.dev == handle)
        .flatMap { case (_, _, _, snapshot) =>
          snapshot.days.iterator.map { case (day, dayData) =>
            (weekStart(LocalDate.parse(day)), dayData.commits.size)
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
        sampledCommits = sampledByWeek.getOrElse(week, 0),
        countsByType = commitTypeOrder.map(t => t -> countsByWeekType.getOrElse((week, t), 0)).toMap
      )
    }
  }

  private def agentPeriodRowsForDeveloper(handle: String): Vector[AgentPeriodRow] = {
    val rows = periodRows.filter(_.developer == handle)
    val weekKeys = rows.map(_.period_iso).distinct.sorted
    weekKeys.map { week =>
      val weekRows = rows.filter(_.period_iso == week)
      AgentPeriodRow(
        periodIso = week,
        totalCommits = weekRows.headOption.map(_.total_commits).getOrElse(0),
        sampledCommits = weekRows.headOption.map(_.sampled_commits).getOrElse(0),
        countsByAgent = weekRows.map(row => row.agent -> row.count).toMap
      )
    }.toVector.dropWhile(row => row.totalCommits == 0 && row.sampledCommits == 0 && row.countsByAgent.values.sum == 0)
  }

  private def toStackedRows(rows: Vector[AgentPeriodRow]): Vector[Rq2CommitTypeSvg.StackedTimeRow] =
    rows.map(row =>
      Rq2CommitTypeSvg.StackedTimeRow(
        periodIso = row.periodIso,
        totalCommits = row.totalCommits,
        sampledCommits = row.sampledCommits,
        counts = row.countsByAgent
      )
    )

  @main def makeWeeklyPlotSvgs(): Unit = {

    println(s"Running with dataDir=${dataPath.toString} granularity=week")

    Files.createDirectories(outputPath)

    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    trackedHandles.toVector.sorted.foreach { handle =>
      val rows = agentPeriodRowsForDeveloper(handle)
      if rows.nonEmpty then
          val outputFile = outputPath.resolve(s"rq2-agent-use-$handle.svg")
          Rq2CommitTypeSvg.writeSvg(
            outputFile,
            Rq2CommitTypeSvg.renderStackedTimeSeriesSvg(
              s"Agent Usage Over Time — @$handle",
              toStackedRows(rows),
              agentColorOrder,
              agentColors,
              "Top agent"
            )
          )
          println(s"Wrote agent-use SVG for @$handle to $outputFile")
    }
  }

}
