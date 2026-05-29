package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import whataretheydoing.CommitProcessing.{aggregateCommitData, commitSignals}
import whataretheydoing.HeuristicMatcher.SignalType
import whataretheydoing.definitions.CustomHeuristics
import whataretheydoing.{AgentHeuristic, CommitDetail, CommitEntry, CommitProcessing, DayData, DevSummary, HeuristicMatcher, MonthlySnapshot, SVGGraphLib}

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, LocalDate, YearMonth}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object DataAnalysis {

  inline def time[A](label: String)(inline body: A): A = {
    val start  = System.nanoTime()
    val result = body
    val end    = System.nanoTime()
    println(s"$label: ${(end - start) / 1000000.0} ms")
    result
  }

  // Load developers
  lazy val developers: List[DevSummary] = time("load devs"):
      val developersJson = Files.readAllBytes(GlobalPaths.devFile)
      readFromArray[List[DevSummary]](developersJson)

  lazy val trackedHandles: Set[String] = developers.map(_.handle).toSet


  case class AuthorStats(
      login: String,
      totalCommits: Int,
      authorCounts: Vector[((String, String), Int)],
      committerCounts: Vector[((String, String), Int)]
  )

  lazy val allAuthorStats: Vector[AuthorStats] =
      val grouped = CommitProcessing.allCommits.toVector.groupBy(_.author.login)
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
    CommitProcessing.allCommitDetailsBySha1.valuesIterator
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

  def weekStart(day: LocalDate): LocalDate = day.`with`(DayOfWeek.MONDAY)

  private lazy val periodRows: Vector[PeriodCsvRow] = {
    time("build weekly period rows") {
      val totalsByDeveloperWeek =
        aggregateCommitData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.map { case (day, dayData) =>
              ((developer, weekStart(day)), dayData.total_count)
            }
          }
          .toVector
          .groupMapReduce(_._1)(_._2)(_ + _)

      val sampledByDeveloperWeek =
        aggregateCommitData.iterator
          .flatMap { case (developer, _, _, snapshot) =>
            snapshot.days.iterator.map { case (day, dayData) =>
              ((developer, weekStart(day)), dayData.commits.size)
            }
          }
          .toVector
          .groupMapReduce(_._1)(_._2)(_ + _)

      val countsByDeveloperWeekAgent =
        aggregateCommitData.iterator
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
    aggregateCommitData.iterator.flatMap { case (developer, _, _, snapshot) =>
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
          val fromSummary = ContributionSummaries.totalCommitsFromSummary(handle, week)
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
                CommitProcessing.allCommitDetailsBySha1.get(commit.sha).toVector.filter { entry =>
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
          val fromSummary = ContributionSummaries.totalCommitsFromSummary(handle, LocalDate.parse(week))
          if fromSummary > 0 then fromSummary
          else rows.find(_.period_iso == week).map(_.total_commits).getOrElse(0)
        }.toVector,
        sampledCommits =
          weekKeys.map(week => rows.find(_.period_iso == week).map(_.sampled_commits).getOrElse(0)).toVector
      )
    }
  }

  private val commitUrl = "^https://github.com/(?<org>[^/]+)/(?<repo>[^/]+)/commit/".r.unanchored

  def repoOfCommit(sha: String): String =
    CommitProcessing.allCommitDetailsBySha1.get(sha) match {
      case Some(value) => value.commit.html_url match {
          case Some(commitUrl(org, repo)) => s"$org/$repo"
          case _                          => ""
        }
      case None => ""
    }

  lazy val multiagent: Map[String, (commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    CommitProcessing.allCommitDetailsBySha1.filter((_, cc) => cc.classification.agentSignals.sizeIs > 1)

  def printMultiagentRepos(): Unit = {
    pprint.pprintln(multiagent)
    val multiagentRepos = multiagent.map { e =>
      repoOfCommit(e._1)
    }.toSet
    pprint.pprintln(multiagentRepos)
  }

  def writeUnknowCommitTypes(): Unit = {
    val unknownType =
      CommitProcessing.allCommitDetailsBySha1.filter((_, cc) => cc.classification.commitType == CommitType.Unknown)
    Files.writeString(
      Path.of("commitheader.txt"),
      unknownType.values.map(
        _.detail.message.get.linesIterator.nextOption().getOrElse("")
      ).toVector.sorted.mkString("\n")
    )
    ()
  }

}
