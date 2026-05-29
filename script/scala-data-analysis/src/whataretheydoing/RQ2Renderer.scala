package whataretheydoing

import de.rmgk.delay.Sync
import whataretheydoing.CommitProcessing.{aggregateCommitData, allCommitDetailsBySha1, commitSignals}
import whataretheydoing.DataAnalysis.*
import whataretheydoing.HeuristicMatcher.heuristicsByAgent
import whataretheydoing.SVGGraphLib.*

import java.nio.file.Files
import java.time.LocalDate

object RQ2Renderer {
  private val commitTypeOrder: Vector[String] =
    CommitType.values.toVector.sortBy(_.ordinal).map(_.toString.toLowerCase)

  private val commitTypeColors: Map[String, String] = Map(
    CommitType.Build    -> "#4d908e",
    CommitType.Chore    -> "#adb5bd",
    CommitType.Ci       -> "#277da1",
    CommitType.Docs     -> "#577590",
    CommitType.Feat     -> "#e76f51",
    CommitType.Fix      -> "#f4a261",
    CommitType.Perf     -> "#43aa8b",
    CommitType.Refactor -> "#2a9d8f",
    CommitType.Revert   -> "#6c757d",
    CommitType.Style    -> "#90be6d",
    CommitType.Test     -> "#9b5de5",
    CommitType.Unknown  -> "#e9ecef"
  ).map((k, v) => k.toString.toLowerCase -> v)

  private val agentColorOrder = Vector(
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
    "multi agent",
    "no signal"
  )

  private val agentColors: Map[String, String] = Map(
    "claude_code" -> "#e76f51",
    "cursor"      -> "#2a9d8f",
    "copilot"     -> "#264653",
    "codex"       -> "#f4a261",
    "aider"       -> "#9b5de5",
    "devin"       -> "#00bbf9",
    "opencode"    -> "#fee440",
    "windsurf"    -> "#00f5d4",
    "amp"         -> "#577590",
    "gemini"      -> "#adb5bd",
    "qwen_code"   -> "#43aa8b",
    "roo_code"    -> "#90be6d",
    "sweep"       -> "#6c757d",
    "multi agent" -> "#c77dff",
    "no signal"   -> "#e9ecef"
  )

  private val developerPalette: Vector[String] = Vector(
    "#e76f51",
    "#2a9d8f",
    "#264653",
    "#f4a261",
    "#9b5de5",
    "#00bbf9",
    "#43aa8b",
    "#577590",
    "#90be6d",
    "#c77dff"
  )

  private def developerColors(labels: Vector[String]): Map[String, String] =
    labels.zipWithIndex.map { case (label, index) =>
      label -> developerPalette(index % developerPalette.size)
    }.toMap

  private def defaultLines(data: TimeSeriesData): Vector[LineSeries] =
    Vector(
      LineSeries("total commits", "#343a40", "6 4", "#343a40", data.totalCommits)
    )

  private def commitsForDeveloper(handle: String)
      : Vector[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    aggregateCommitData.iterator
      .filter(_.dev == handle)
      .flatMap { case (_, _, _, snapshot) =>
        snapshot.days.iterator.flatMap { case (_, dayData) =>
          dayData.commits.iterator.flatMap { commit =>
            allCommitDetailsBySha1.get(commit.sha)
          }
        }
      }
      .toVector

  private def commitAgentBucket(entry: (commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit))
      : String = {
    val agents = entry.classification.agents
    if agents.isEmpty then "no signal"
    else if agents.size > 1 then "multi agent"
    else agents.head
  }

  private def toBoxPlotStats(
      label: String,
      commits: Vector[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)]
  ): BoxPlotStats = {
    val stats = summarizeLinesChanged(commits)
    BoxPlotStats(
      label = label,
      count = stats.count,
      min = stats.min,
      q1 = stats.q1,
      median = stats.median,
      q3 = stats.q3,
      max = stats.max,
      whiskerLow = stats.whiskerLow,
      whiskerHigh = stats.whiskerHigh,
      mean = stats.mean,
      outliers = stats.outliers.map(_.toDouble)
    )
  }

  private def boxPlotStatsByDeveloper: Vector[BoxPlotStats] =
    trackedHandles.toVector.sorted.flatMap { handle =>
      val commits = commitsForDeveloper(handle)
      if commits.nonEmpty then Some(toBoxPlotStats(handle.stripPrefix("@"), commits)) else None
    }

  private def boxPlotStatsByAgent: Vector[BoxPlotStats] =
    agentColorOrder.flatMap { agent =>
      val bucket = allCommitDetailsBySha1.valuesIterator.filter(entry => commitAgentBucket(entry) == agent).toVector
      if bucket.nonEmpty then Some(toBoxPlotStats(agent, bucket)) else None
    }

  private def boxPlotStatsByDeveloperWeek(handle: String): Vector[BoxPlotStats] =
    aggregateCommitData.iterator
      .filter(_.dev == handle)
      .flatMap { case (_, _, _, snapshot) =>
        snapshot.days.iterator.flatMap { case (day, dayData) =>
          val week = weekStart(day)
          dayData.commits.iterator.flatMap { commit =>
            allCommitDetailsBySha1.get(commit.sha).map(entry => week -> entry)
          }
        }
      }
      .toVector
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .map { case (week, rows) =>
        toBoxPlotStats(week.toString, rows.map(_._2))
      }

  private def commitTypeSeriesForAgent(agent: String): TimeSeriesData = {
    val agentCommitsByWeek: Vector[(LocalDate, CommitType)] =
      aggregateCommitData.iterator
        .flatMap { case (_, _, _, snapshot) =>
          snapshot.days.iterator.flatMap { case (day, dayData) =>
            val week = weekStart(day)
            dayData.commits.iterator.flatMap { commit =>
              commitSignals(commit.sha).toVector.flatMap { classified =>
                if classified.agents.contains(agent) then Vector((week, classified.commitType)) else Vector.empty
              }
            }
          }
        }
        .toVector

    val countsByWeekType: Map[(LocalDate, CommitType), Int] =
      agentCommitsByWeek.groupMapReduce(identity)(_ => 1)(_ + _)

    val weeks  = agentCommitsByWeek.map(_._1).distinct.sorted
    val points = weeks.map { week =>
      StackedBarPoint(
        xLabel = week.toString,
        values = countsByWeekType.collect {
          case ((w, commitType), count) if w == week => commitType.toString.toLowerCase -> count
        }
      )
    }.toVector
    val totals = points.map(_.values.values.sum)
    TimeSeriesData(points = points, totalCommits = totals, sampledCommits = totals)
  }

  def makeWeeklyPlotSvgs(): Seq[Sync[Any, Unit]] = {

    trackedHandles.toVector.map { handle =>
      Sync {
        val data = agentSeriesForDeveloper(handle)
        if data.points.nonEmpty then
            val agentTotals =
              data.points.iterator.flatMap(_.values).toVector.groupMapReduce(_._1)(_._2)(_ + _)
            val activeAgents = agentTotals.filter(_._2 >= 50).keySet

            val points = data.points.map { pt =>
              pt.copy(values = pt.values.filter((k, _) => activeAgents.contains(k)))
            }

            val outputFile = GlobalPaths.outputPath.resolve(s"rq2-agent-use-$handle.svg")
            writeSvgAndConvertToPdf(
              outputFile,
              renderStackedTimeSeriesSvg(
                s"Agent Usage Over Time — @$handle",
                points,
                agentColorOrder,
                agentColors,
                "Top agent",
                defaultLines(data)
              )
            )
            println(s"Wrote agent-use SVG for @$handle to $outputFile")
      }
    }
  }

  def makeRq2CommitTypeSvgs(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath)

    trackedHandles.toVector.map { handle =>
      Sync {
        val data = commitTypeSeriesForDeveloper(handle)
        if data.points.nonEmpty then
            val svgPath = GlobalPaths.outputPath.resolve(s"commit-types-$handle.svg")
            writeSvgAndConvertToPdf(
              svgPath,
              renderStackedTimeSeriesSvg(
                s"Commit Types Over Time — @$handle",
                data.points,
                commitTypeOrder,
                commitTypeColors,
                "Top type",
                defaultLines(data)
              )
            )
            println(s"Wrote commit-type SVG for @$handle to $svgPath")
      }
    }
  }

  def makeRq2CommitTypePerAgentSvgs(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath)

    val agentCommitCounts: Map[String, Int] =
      CommitProcessing.allCommitDetailsBySha1.valuesIterator
        .flatMap(entry => entry.classification.agents)
        .toVector
        .groupMapReduce(identity)(_ => 1)(_ + _)

    val legendSync = Sync {
      val svgPath = GlobalPaths.outputPath.resolve("commit-types-legend.svg")
      writeSvgAndConvertToPdf(svgPath, renderLegendSvg(commitTypeOrder, commitTypeColors))
      println(s"Wrote commit-types legend SVG to $svgPath")
    }

    val agentSyncs = heuristicsByAgent.keys.toVector.sorted
      .filter(agent => agentCommitCounts.getOrElse(agent, 0) >= 50)
      .map { agent =>
      Sync {
        val data = commitTypeSeriesForAgent(agent)
        if data.points.nonEmpty && activeStackKeys(data.points, commitTypeOrder).nonEmpty then
            val svgPath = GlobalPaths.outputPath.resolve(s"commit-types-agent-$agent.svg")
            writeSvgAndConvertToPdf(
              svgPath,
              renderStackedTimeSeriesSvg(
                s"Commit Types Over Time — agent:$agent",
                data.points,
                commitTypeOrder,
                commitTypeColors,
                "Top type",
                defaultLines(data),
                showLegend = false
              )
            )
            println(s"Wrote commit-type-by-agent SVG for $agent to $svgPath")
      }
    }

    legendSync +: agentSyncs
  }

  def makeRq2LinesChangedWeeklyStackedSvgs(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath)

    trackedHandles.toVector.sorted.map { handle =>
      Sync {
        val data = linesChangedByAgentSeriesForDeveloper.getOrElse(
          handle,
          TimeSeriesData(Vector.empty, Vector.empty, Vector.empty)
        )
        if data.points.nonEmpty && activeStackKeys(data.points, agentColorOrder).nonEmpty then
            val svgPath = GlobalPaths.outputPath.resolve(s"lines-changed-weekly-agents-$handle.svg")
            writeSvgAndConvertToPdf(
              svgPath,
              renderStackedTimeSeriesSvg(
                s"Lines Changed by Agent Over Time — @$handle",
                data.points,
                agentColorOrder,
                agentColors,
                "Top agent",
                Vector.empty,
                yAxisLabel = "Lines changed",
                xAxisLabel = "Week",
                totalLabel = "Total lines changed"
              )
            )
            println(s"Wrote weekly lines-changed-by-agent SVG for @$handle to $svgPath")
      }
    }
  }

  private def writeLinesChangedVariants(
      stem: String,
      title: String,
      stats: Vector[BoxPlotStats],
      fillByLabel: Map[String, String],
      strokeByLabel: Map[String, String]
  ): Unit = {
    val variants = Vector(
      ("-outlier-suppressed", s"$title (Outliers Suppressed)", BoxPlotScale.Linear, true),
      ("-logscale", s"$title (Log Scale)", BoxPlotScale.Log10, false)
    )

    variants.foreach { case (suffix, variantTitle, scale, suppressOutliers) =>
      val svgPath = GlobalPaths.outputPath.resolve(s"$stem$suffix.svg")
      writeSvgAndConvertToPdf(
        svgPath,
        renderBoxPlotSvg(
          title = variantTitle,
          yLabel = "Lines changed per commit",
          stats = stats,
          fillByLabel = fillByLabel,
          strokeByLabel = strokeByLabel,
          scale = scale,
          suppressOutliers = suppressOutliers
        )
      )
      println(s"Wrote $svgPath")
    }
  }

  def makeRq2LinesChangedBoxplots(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath)

    val byDeveloper      = boxPlotStatsByDeveloper
    val byDeveloperSyncs =
      if byDeveloper.nonEmpty then
          val colors = developerColors(byDeveloper.map(_.label))
          Seq(
            Sync(writeLinesChangedVariants(
              stem = "lines-changed-by-developer",
              title = "Lines Changed by Developer",
              stats = byDeveloper,
              fillByLabel = colors,
              strokeByLabel = colors
            ))
          )
      else Seq.empty

    val byAgent      = boxPlotStatsByAgent
    val byAgentSyncs =
      if byAgent.nonEmpty then
          Seq(
            Sync(writeLinesChangedVariants(
              stem = "lines-changed-by-agent",
              title = "Lines Changed by Agent Bucket",
              stats = byAgent,
              fillByLabel = agentColors,
              strokeByLabel = agentColors
            ))
          )
      else Seq.empty

    val byWeekSyncs = trackedHandles.toVector.sorted.map { handle =>
      Sync {
        val byWeek = boxPlotStatsByDeveloperWeek(handle)
        if byWeek.nonEmpty then
            val colors = developerColors(byWeek.map(_.label))
            writeLinesChangedVariants(
              stem = s"lines-changed-by-week-$handle",
              title = s"Lines Changed by Week — @$handle",
              stats = byWeek,
              fillByLabel = colors,
              strokeByLabel = colors
            )
      }
    }

    byDeveloperSyncs ++ byAgentSyncs ++ byWeekSyncs
  }

  @main def makeAllRq2Svgs(): Unit = {
    val allSyncs = Vector(
      makeWeeklyPlotSvgs(),
      // makeRq2CommitTypeSvgs(),
      makeRq2CommitTypePerAgentSvgs(),
      // makeRq2LinesChangedBoxplots(),
      // makeRq2LinesChangedWeeklyStackedSvgs(),
    ).flatten

    allSyncs.strucMap(_.run(using ()))
    ()

  }
}
