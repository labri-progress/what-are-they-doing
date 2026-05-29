package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import definitions.CustomHeuristics
import whataretheydoing.DataAnalysis.CommitType.Unknown
import whataretheydoing.HeuristicMatcher.SignalType

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, LocalDate, YearMonth}
import java.util.Locale
import scala.collection.parallel.immutable.ParVector
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

object DataAnalysis {

  val repoRoot: Path    = Path.of("").toAbsolutePath.normalize.getParent.getParent
  val dataPath: Path    = repoRoot.resolve("data")
  val heuristics: Path  = repoRoot.resolve("agent-mining/agents")
  val commitsPath: Path = repoRoot.resolve("data/commits")
  val devFile: Path     = repoRoot.resolve("developers.json")
  val outputPath: Path  = repoRoot.resolve("figures").resolve("rq2")
  val contributionSummaryDir: Path = repoRoot.resolve("data/contribution-summaries")

  enum CommitType:
      case Build, Chore, Ci, Docs, Feat, Fix, Perf, Refactor, Revert, Style, Test, Unknown

  case class ClassifiedCommit(
      agentSignals: Map[String, Set[SignalType]],
      commitType: CommitType,
      message: String,
      files: List[String],
      trailers: Seq[(key: String, value: String)]
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

  case class LinesChangedStats(
      count: Int,
      min: Int,
      q1: Double,
      median: Double,
      q3: Double,
      max: Int,
      mean: Double,
      standardDeviation: Double,
      whiskerLow: Int,
      whiskerHigh: Int,
      outliers: Vector[Int]
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

  lazy val baseHeuristics: Map[String, AgentHeuristic] =
    time("load heuristics")(HeuristicMatcher.loadHeuristics(heuristics))

  lazy val heuristicsByAgent: Map[String, AgentHeuristic] =
      import de.rmgk.Associative.mapAssoc
      mapAssoc.combine(baseHeuristics, CustomHeuristics.customHeuristics)

  lazy val aggregateData: Vector[(dev: String, month: YearMonth, path: Path, data: MonthlySnapshot)] =
    time("loading aggregate data") {
      val jsonFiles = Using(Files.list(dataPath)) {
        _.iterator().asScala.filter { path =>
          Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json")
        }.toVector
      }.get

      jsonFiles.flatMap { path =>
        try
            val snapshot = readFromArray[MonthlySnapshot](Files.readAllBytes(path))
            if trackedHandles.contains(snapshot.developer) then
                Some((snapshot.developer, YearMonth.parse(snapshot.month), path, snapshot))
            else None
        catch
            case _: Exception =>
              System.err.println(s"Warning: could not parse ${path.getFileName} as MonthlySnapshot, skipping")
              None
      }.toVector
    }

  lazy val contributionSummaries: Map[String, DeveloperContributionSummary] =
    time("load contribution summaries") {
      if Files.isDirectory(contributionSummaryDir) then
          Using(Files.list(contributionSummaryDir)) { stream =>
            stream.iterator().asScala
              .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
              .flatMap { path =>
                try
                    val summary = readFromArray[DeveloperContributionSummary](Files.readAllBytes(path))
                    Some(summary.developer -> summary)
                catch
                    case _: Exception =>
                      System.err.println(s"Warning: could not parse ${path.getFileName} as contribution summary, skipping")
                      None
              }.toMap
          }.get
      else Map.empty
    }

  def allCommits: Iterator[CommitEntry] = aggregateData.iterator.flatMap(_.data.days.valuesIterator.flatMap(_.commits))

  lazy val allDays: Seq[LocalDate] = aggregateData.flatMap(_.data.days.keysIterator)

  case class AuthorStats(
      login: String,
      totalCommits: Int,
      authorCounts: Vector[((String, String), Int)],
      committerCounts: Vector[((String, String), Int)]
  )

  lazy val allAuthorStats: Vector[AuthorStats] =
      val grouped = allCommits.toVector.groupBy(_.author.login)
      grouped.toVector.sortBy((k, _) => k)(using summon[Ordering[String]]).map { case (login, commits) =>
        val authorCounts = commits.groupBy(e => (e.commit.author.name, e.commit.author.email)).view.mapValues(
          _.size
        ).toVector.sortBy(-_._2)
        val committerCounts = commits.groupBy(e => (e.commit.committer.name, e.commit.committer.email)).view.mapValues(
          _.size
        ).toVector.sortBy(-_._2)
        AuthorStats(login, commits.size, authorCounts, committerCounts)
      }

  @main def auth() =
    allAuthorStats.foreach { stats =>
      println(s"--- ${stats.login} (${stats.totalCommits} commits) ---")
      println("  authors:")
      stats.authorCounts.foreach { case ((name, email), count) =>
        println(s"    $count  $name <$email>")
      }
      println("  committers:")
      stats.committerCounts.foreach { case ((name, email), count) =>
        println(s"    $count  $name <$email>")
      }
      println()
    }

  lazy val coAuthorAgentCounts: Vector[(String, Int)] =
    allCommitDetails.valuesIterator
      .flatMap { entry =>
        entry.classification.agentSignals.iterator.collect {
          case (agent, signals) if signals.contains(SignalType.CoAuthoredBy) => agent
        }
      }
      .toVector
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toVector
      .sortBy(-_._2)

  @main def coauthored() =
      println("Detections via CoAuthor signal per agent:")
      coAuthorAgentCounts.foreach { case (agent, count) =>
        println(s"  $count  $agent")
      }

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
        case _          => CommitType.Unknown

  private def startsWithAny(line: String, prefixes: String*): Boolean =
    prefixes.exists(line.startsWith)

  private def inferCommitTypeFromHeader(header: String): CommitType = {
    val normalized = header.trim.toLowerCase
    if normalized.isEmpty then CommitType.Unknown
    // format: off
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
    // format: on
    else CommitType.Unknown
  }

  def classifyCommitMessage(message: String): CommitType =
    message.linesIterator.nextOption() match
        case Some(conventionalCommitPattern(rawType, _, _)) => conventionalCommitType(rawType.nn)
        case Some(header)                                   => inferCommitTypeFromHeader(header)
        case _                                              => CommitType.Unknown

  private def quantile(sortedValues: Vector[Int], p: Double): Double = {
    if sortedValues.isEmpty then 0.0
    else if sortedValues.size == 1 then sortedValues.head.toDouble
    else
        val clamped = math.max(0.0, math.min(1.0, p))
        val index   = clamped * (sortedValues.size - 1)
        val lower   = math.floor(index).toInt
        val upper   = math.ceil(index).toInt
        if lower == upper then sortedValues(lower).toDouble
        else
            val weight = index - lower
            sortedValues(lower) * (1.0 - weight) + sortedValues(upper) * weight
  }

  def totalLinesChanged(detail: CommitDetail): Int =
    detail.files.iterator.map(file => math.max(file.changes, file.additions + file.deletions)).sum

  def summarizeLinesChanged(commits: Iterable[(
      commit: CommitEntry,
      detail: CommitDetail,
      classification: ClassifiedCommit
  )]): LinesChangedStats = {
    val values = commits.iterator.map(entry => totalLinesChanged(entry.detail)).toVector.sorted
    if values.isEmpty then
        LinesChangedStats(
          count = 0,
          min = 0,
          q1 = 0.0,
          median = 0.0,
          q3 = 0.0,
          max = 0,
          mean = 0.0,
          standardDeviation = 0.0,
          whiskerLow = 0,
          whiskerHigh = 0,
          outliers = Vector.empty
        )
    else
        val count    = values.size
        val min      = values.head
        val max      = values.last
        val q1       = quantile(values, 0.25)
        val median   = quantile(values, 0.50)
        val q3       = quantile(values, 0.75)
        val mean     = values.sum.toDouble / count
        val variance = values.iterator.map { value =>
          val delta = value - mean
          delta * delta
        }.sum / count
        val standardDeviation = math.sqrt(variance)
        val iqr               = q3 - q1
        val lowerFence        = q1 - 1.5 * iqr
        val upperFence        = q3 + 1.5 * iqr
        val inliers           = values.filter(value => value >= lowerFence && value <= upperFence)
        val outliers          = values.filterNot(value => value >= lowerFence && value <= upperFence)

        LinesChangedStats(
          count = count,
          min = min,
          q1 = q1,
          median = median,
          q3 = q3,
          max = max,
          mean = mean,
          standardDeviation = standardDeviation,
          whiskerLow = inliers.headOption.getOrElse(min),
          whiskerHigh = inliers.lastOption.getOrElse(max),
          outliers = outliers
        )
  }

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

  private val trailerPattern: Regex = """^([a-zA-Z][a-zA-Z0-9_.-]+):\s+(.+)$""".r

  private def parseTrailers(message: String): Seq[(key: String, value: String)] =
    message.linesIterator.toVector
      .drop(1)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        line match
            case trailerPattern(key, value) => Some((key.nn.toLowerCase(Locale.ROOT).nn, value.nn))
            case _                          => None
      }

  private def classifyCommit(commit: CommitEntry, detail: CommitDetail): ClassifiedCommit = {

    val message  = detail.message.get
    val trailers = parseTrailers(message)

    val agentSignals = HeuristicMatcher.detectAgents(
      commit,
      detail,
      trailers,
      heuristicsByAgent
    )

    ClassifiedCommit(
      agentSignals = agentSignals,
      commitType = classifyCommitMessage(message),
      message = message,
      files = detail.files.map(_.filename),
      trailers = trailers
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
                  if agents.isEmpty then Set("no signal")
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
        totalCommits = weeks.map { week =>
          val fromSummary = totalCommitsFromSummary(handle, week)
          if fromSummary > 0 then fromSummary
          else commitTypeTotalsByDeveloperWeek((handle, week))
        },
        sampledCommits = weeks.map(week => commitTypeSampledByDeveloperWeek.getOrElse((handle, week), 0))
      )
    }.toMap

  lazy val linesChangedByAgentSeriesForDeveloper: Map[String, TimeSeriesData] = {
    val rows = periodRows.groupBy(_.developer)
    rows.map { (handle, developerRows) =>
      val weekKeys = developerRows.map(_.period_iso).distinct.sorted
      handle -> TimeSeriesData(
        points = weekKeys.map { week =>
          val weekRows = developerRows.filter(_.period_iso == week)
          val values   = weekRows.flatMap { row =>
            val totalLines =
              weeklyData.get((handle, LocalDate.parse(week))).toVector.flatten.flatMap(_.commits).flatMap { commit =>
                allCommitDetails.get(commit.sha).toVector.filter { entry =>
                  val agents = entry.classification.agents
                  val bucket =
                    if agents.isEmpty then "no signal"
                    else if agents.size > 1 then "multi agent"
                    else agents.head
                  bucket == row.agent
                }.map(entry => totalLinesChanged(entry.detail))
              }.sum
            Option.when(totalLines > 0)(row.agent -> totalLines)
          }.toMap
          SVGGraphLib.StackedBarPoint(xLabel = week, values = values)
        },
        totalCommits = weekKeys.map(_ => 0).toVector,
        sampledCommits = weekKeys.map(_ => 0).toVector
      )
    }
  }

  def totalCommitsFromSummary(handle: String, week: LocalDate): Int =
    contributionSummaries.get(handle) match
      case Some(summary) =>
        val weekEnd = week.plusDays(6)
        summary.contributions
          .filter { d =>
            val date = LocalDate.parse(d.date)
            !date.isBefore(week) && !date.isAfter(weekEnd)
          }
          .map(_.contributionCount).sum
      case None => 0

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
        totalCommits = weekKeys.map { week =>
          val fromSummary = totalCommitsFromSummary(handle, LocalDate.parse(week))
          if fromSummary > 0 then fromSummary
          else rows.find(_.period_iso == week).map(_.total_commits).getOrElse(0)
        }.toVector,
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
    allCommitDetails.filter((_, cc) => cc.classification.agentSignals.sizeIs > 1)

  def printMultiagentRepos(): Unit = {
    pprint.pprintln(multiagent)
    val multiagentRepos = multiagent.map { e =>
      repoOfCommit(e._1)
    }.toSet

    pprint.pprintln(multiagentRepos)

  }

  def writeUnknowCommitTypes(): Unit = {
    val unknownType = allCommitDetails.filter((_, cc) => cc.classification.commitType == Unknown)
    Files.writeString(
      Path.of("commitheader.txt"),
      unknownType.values.map(
        _.detail.message.get.linesIterator.nextOption().getOrElse("")
      ).toVector.sorted.mkString("\n")
    )
    ()
  }

}
