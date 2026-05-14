package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import whataretheydoing.DataAnalysis.CommitType.Unknown
import whataretheydoing.HeuristicMatcher.SignalType

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, LocalDate, YearMonth}
import scala.collection.parallel.immutable.ParVector
import scala.jdk.CollectionConverters.*
import scala.util.{Random, Using}

object DataAnalysis {

  val repoRoot: Path    = Path.of("").toAbsolutePath.normalize.getParent.getParent
  val dataPath: Path    = repoRoot.resolve("data")
  val heuristics: Path  = repoRoot.resolve("agent-mining/agents")
  val commitsPath: Path = repoRoot.resolve("data/commits")
  val devFile: Path     = repoRoot.resolve("developers.json")
  val outputPath: Path  = repoRoot.resolve("figures").resolve("rq2")

  enum CommitType:
      case Build, Chore, Ci, Docs, Feat, Fix, Perf, Refactor, Revert, Style, Test, Unknown

  case class ClassifiedCommit(
      agentSignals: Map[String, Set[SignalType]],
      commitType: CommitType,
      message: String,
      files: List[String]
  ) {
    def agents: Set[String] = agentSignals.keySet
  }

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

  case class TimeSeriesData(
      points: Vector[SVGGraphLib.StackedBarPoint],
      totalCommits: Vector[Int],
      sampledCommits: Vector[Int]
  )

// ── Shared output helpers ──────────────────────────────────────────────────

  inline def time[A](label: String)(inline body: A): A = {
    val start  = System.nanoTime()
    val result = body
    val end    = System.nanoTime()
    println(s"$label: ${(end - start) / 1000000.0} ms")
    result
  }

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

  def allCommits: Iterator[CommitEntry] = aggregateData.iterator.flatMap(_.data.days.valuesIterator.flatMap(_.commits))

  lazy val allDays: Seq[LocalDate] = aggregateData.flatMap(_.data.days.keysIterator)

  private val conventionalCommitPattern =
    "^([a-z]+)(?:\\([^\\r\\n()]+\\))?(!)?:\\s+(.+)$".r

  private def conventionalCommitType(rawType: String): CommitType =
    rawType.toLowerCase match
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
        case _           => CommitType.Unknown

  private def startsWithAny(line: String, prefixes: String*): Boolean =
    prefixes.exists(line.startsWith)

  private def inferCommitTypeFromHeader(header: String): CommitType = {
    val normalized = header.trim.toLowerCase
    if normalized.isEmpty then CommitType.Unknown
    else if startsWithAny(normalized, "feat", "add ", "implement ", "introduce ", "support ", "enable ", "allow ", "create ", "wire ", "integrate ", "expose ", "provide ", "initialize ", "bootstrap ", "accept ", "share ") then CommitType.Feat
    else if startsWithAny(normalized, "fix", "bugfix", "hotfix", "repair ", "resolve ", "correct ", "prevent ", "stabilize ", "hardening", "harden ", "security:", "security ") then CommitType.Fix
    else if startsWithAny(normalized, "perf", "optimize ", "optimise ", "speed up ", "reduce ", "benchmark", "cache ", "faster ", "lazy ") then CommitType.Perf
    else if startsWithAny(normalized, "refactor", "rename ", "move ", "extract ", "reorganize ", "reorganise ", "modularize ", "modularise ", "consolidate ", "simplify ", "streamline ", "cleanup", "clean up", "deduplicate ", "split ", "port ", "migrate ", "rewrite ", "rework ") then CommitType.Refactor
    else if startsWithAny(normalized, "docs", "doc:", "document ", "documentation", "readme", "guide", "tutorial", "blog", "changelog", "adr-", "spec", "planning", "plan ", "prompt:") then CommitType.Docs
    else if startsWithAny(normalized, "test", "tests", "e2e", "integration test", "unit test", "property", "conformance", "coverage", "smoke test", "test:") then CommitType.Test
    else if startsWithAny(normalized, "style", "format", "fmt", "lint", "lint:", "prettier", "rustfmt", "clang-format", "shfmt", "shellcheck", "typo", "whitespace") then CommitType.Style
    else if startsWithAny(normalized, "build", "bump ", "release", "publish ", "package", "packaging", "installer", "install ", "cargo", "cmake", "docker", "homebrew", "nix", "npm ", "pnpm ", "wasm build", "binary", "artifact") then CommitType.Build
    else if startsWithAny(normalized, "ci ", "ci:", "github actions", "workflow", "workflows", "pipeline", "buildkite", "lint/test") then CommitType.Ci
    else if startsWithAny(normalized, "revert") then CommitType.Revert
    else if startsWithAny(normalized, "merge ", "sync ", "track ", "checkpoint", "wip", "tmp", "oops", "updates", "update ", "adjust ", "tweak ", "tune ", "polish ", "note ", "use ", "switch ", "set ", "bake ", "prepare ", "release ") then CommitType.Chore
    else CommitType.Unknown
  }

  def classifyCommitMessage(message: String): CommitType =
    message.linesIterator.nextOption() match
        case Some(conventionalCommitPattern(rawType, _, _)) => conventionalCommitType(rawType.nn)
        case Some(header) => inferCommitTypeFromHeader(header)
        case _ => CommitType.Unknown

  def loadFullCommitData(commit: CommitEntry): CommitDetail = {
    val detailFile = commitsPath.resolve(s"${commit.sha}.json")
    val detail     =
      if Files.exists(detailFile) then
          val bytes = Files.readAllBytes(detailFile)
          try readFromArray[CommitDetail](bytes)
          catch
              case _: Exception =>
                val files = readFromArray[List[CommitFile]](bytes)
                CommitDetail(message = Some(commit.commit.message), files = files)
      else CommitDetail(message = None, files = Nil)

    detail.copy(message = Some(detail.message.getOrElse(commit.commit.message)))
  }

  private def classifyCommit(commit: CommitEntry, detail: CommitDetail): ClassifiedCommit = {

    val message      = detail.message.get
    val changedFiles = detail.files.map(_.filename)
    val author       = commit.commit.author
    val commitAuthor = s"${author.name} <${author.email}>"
    val agentSignals = HeuristicMatcher.detectAgents(
      message,
      commitAuthor,
      changedFiles,
      heuristicsByAgent
    )

    ClassifiedCommit(
      agentSignals = agentSignals,
      commitType = classifyCommitMessage(message),
      message = message,
      files = changedFiles
    )
  }

  lazy val allCommitDetails
      : Map[String, (commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    time("load commit details") {
      allCommits.to(ParVector).map { commit =>
        val detail         = loadFullCommitData(commit)
        val classification = classifyCommit(commit, detail)
        (commit.sha, (commit = commit, detail = detail, classification = classification))
      }.to(Map)
    }

  def commitSignals(sha: String): Option[ClassifiedCommit] = allCommitDetails.get(sha).map(_.classification)

  def weekStart(day: LocalDate): LocalDate = day.`with`(DayOfWeek.MONDAY)

  private lazy val periodRows: Vector[PeriodCsvRow] = {
    time("build weekly period rows") {
      val totalsByDeveloperWeek =
        aggregateData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.map { case (day, dayData) =>
              ((developer, weekStart(day)), dayData.total_count)
            }
          }
          .toVector
          .groupMapReduce(_._1)(_._2)(_ + _)

      val sampledByDeveloperWeek =
        aggregateData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.map { case (day, dayData) =>
              ((developer, weekStart(day)), dayData.commits.size)
            }
          }
          .toVector
          .groupMapReduce(_._1)(_._2)(_ + _)

      val countsByDeveloperWeekAgent =
        aggregateData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.flatMap { case (day, dayData) =>
              val week = weekStart(day)
              dayData.commits.iterator.flatMap { commit =>
                val agents = commitSignals(commit.sha).map(_.agents).getOrElse(Set.empty)
                val labels =
                  if agents.isEmpty then Set("no agent")
                  else if agents.size > 1 then Set("multi agent")
                  else agents
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
  }

  lazy val dailyData: Vector[(developer: String, day: LocalDate, dayData: DayData)] =
    aggregateData.iterator.flatMap { case (developer, _, _, snapshot) =>
      snapshot.days.iterator.map { case (day, dayData) => (developer, day, dayData) }
    }.toVector

  lazy val weeklyData: Map[(String, LocalDate), Vector[DayData]] =
    dailyData.groupBy(e => (e.developer, weekStart(e.day))).map((k, v) => k -> v.map(_.dayData))

  private lazy val commitTypeTotalsByDeveloperWeek: Map[(String, LocalDate), Int] =
    weeklyData.view.mapValues(_.map(_.total_count).sum).toMap

  private lazy val commitTypeSampledByDeveloperWeek: Map[(String, LocalDate), Int] =
    weeklyData.view.mapValues(_.map(_.commits.size).sum).toMap

  private lazy val commitTypeCountsByDeveloperWeekType: Map[(String, LocalDate, CommitType), Int] =
    weeklyData.iterator
      .flatMap { case ((developer, week), dayDataVector) =>
        dayDataVector.iterator.flatMap { dayData =>
          dayData.commits.iterator.map { commit =>
            val commitType = commitSignals(commit.sha).map(_.commitType).getOrElse(CommitType.Unknown)
            ((developer, week, commitType), 1)
          }
        }
      }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)

  lazy val commitTypeSeriesForDeveloper: Map[String, TimeSeriesData] =
    trackedHandles.iterator.map { handle =>
      val weeks =
        commitTypeTotalsByDeveloperWeek.keys.collect { case (dev, week) if dev == handle => week }.toVector.sorted
      handle -> TimeSeriesData(
        points = weeks.map { week =>
          SVGGraphLib.StackedBarPoint(
            xLabel = week.toString,
            values = commitTypeCountsByDeveloperWeekType.collect {
              case ((dev, w, commitType), count) if dev == handle && w == week =>
                commitType.toString.toLowerCase -> count
            }
          )
        },
        totalCommits = weeks.map(week => commitTypeTotalsByDeveloperWeek((handle, week))),
        sampledCommits = weeks.map(week => commitTypeSampledByDeveloperWeek.getOrElse((handle, week), 0))
      )
    }.toMap

  lazy val agentSeriesForDeveloper: Map[String, TimeSeriesData] = {
    val rows = periodRows.groupBy(_.developer)
    rows.map { (handle, rows) =>
      val weekKeys = rows.map(_.period_iso).distinct.sorted
      handle -> TimeSeriesData(
        points = weekKeys.map { week =>
          val weekRows = rows.filter(_.period_iso == week)
          SVGGraphLib.StackedBarPoint(
            xLabel = week,
            values = weekRows.map(row => row.agent -> row.count).toMap
          )
        },
        totalCommits = weekKeys.map(week => rows.find(_.period_iso == week).map(_.total_commits).getOrElse(0)).toVector,
        sampledCommits =
          weekKeys.map(week => rows.find(_.period_iso == week).map(_.sampled_commits).getOrElse(0)).toVector
      )
    }
  }

  val commitUrl = "^https://github.com/(?<org>[^/]+)/(?<repo>[^/]+)/commit/".r.unanchored

  def repoOfCommit(sha: String): String =
    allCommitDetails.get(sha) match {
      case Some(value) => value.commit.html_url match {
          case Some(commitUrl(org, repo)) => s"$org/$repo"
          case _                          => ""
        }
      case None => ""
    }

  lazy val multiagent: Map[String, (commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    allCommitDetails.filter((sha, cc) => cc.classification.agentSignals.sizeIs > 1)

  def printMultiagentRepos(): Unit = {
    pprint.pprintln(multiagent)
    val multiagentRepos = multiagent.map { e =>
      repoOfCommit(e._1)
    }.toSet

    pprint.pprintln(multiagentRepos)

  }

  def writeUnknowCommitTypes(): Unit = {
    val unknownType = allCommitDetails.filter((sha, cc) => cc.classification.commitType == Unknown)
    Files.writeString(
      Path.of("commitheader.txt"),
      unknownType.values.map(
        _.detail.message.get.linesIterator.nextOption().getOrElse("")
      ).toVector.sorted.mkString("\n")
    )
    ()
  }

}
