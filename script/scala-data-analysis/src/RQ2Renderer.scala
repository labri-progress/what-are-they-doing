package whataretheydoing

import whataretheydoing.DataAnalysis.*
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
    "no agent"
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
    "no agent"    -> "#e9ecef"
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
      LineSeries("sampled commits", "#1d4ed8", "3 3", "#1d4ed8", data.sampledCommits),
      LineSeries("total commits (snapshot)", "#343a40", "6 4", "#343a40", data.totalCommits)
    )

  private def commitsForDeveloper(handle: String): Vector[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    aggregateData.iterator
      .filter(_.dev == handle)
      .flatMap { case (_, _, _, snapshot) =>
        snapshot.days.iterator.flatMap { case (_, dayData) =>
          dayData.commits.iterator.flatMap { commit =>
            allCommitDetails.get(commit.sha)
          }
        }
      }
      .toVector

  private def commitsForAgent(agent: String): Vector[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    allCommitDetails.valuesIterator.filter(_.classification.agents.contains(agent)).toVector

  private def boxPlotStatsByCommitType(
      commits: Iterable[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)]
  ): Vector[BoxPlotStats] =
    CommitType.values.toVector.sortBy(_.ordinal).flatMap { commitType =>
      val bucket = commits.iterator.filter(_.`classification`.commitType == commitType).toVector
      if bucket.nonEmpty then
          val stats = summarizeLinesChanged(bucket)
          Some(
            BoxPlotStats(
              label = commitType.toString.toLowerCase,
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
          )
      else None
    }

  private def commitAgentBucket(entry: (commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)): String = {
    val agents = entry.classification.agents
    if agents.isEmpty then "no agent"
    else if agents.size > 1 then "multi agent"
    else agents.head
  }

  private def toBoxPlotStats(label: String, commits: Vector[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)]): BoxPlotStats = {
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

  private def boxPlotStatsByAgentBucket(
      commits: Iterable[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)]
  ): Vector[BoxPlotStats] =
    agentColorOrder.flatMap { agent =>
      val bucket = commits.iterator.filter(entry => commitAgentBucket(entry) == agent).toVector
      if bucket.nonEmpty then Some(toBoxPlotStats(agent, bucket)) else None
    }

  private def boxPlotStatsByDeveloper: Vector[BoxPlotStats] =
    trackedHandles.toVector.sorted.flatMap { handle =>
      val commits = commitsForDeveloper(handle)
      if commits.nonEmpty then Some(toBoxPlotStats(handle.stripPrefix("@"), commits)) else None
    }

  private def boxPlotStatsByAgent: Vector[BoxPlotStats] =
    agentColorOrder.flatMap { agent =>
      val bucket = allCommitDetails.valuesIterator.filter(entry => commitAgentBucket(entry) == agent).toVector
      if bucket.nonEmpty then Some(toBoxPlotStats(agent, bucket)) else None
    }

  private def commitTypeSeriesForAgent(agent: String): TimeSeriesData = {
    val agentCommitsByWeek: Vector[(LocalDate, CommitType)] =
      aggregateData.iterator
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

  @main def makeWeeklyPlotSvgs(): Unit = {
    Files.createDirectories(outputPath)

    trackedHandles.toVector.sorted.foreach { handle =>
      val data = agentSeriesForDeveloper(handle)
      if data.points.nonEmpty then
          val outputFile = outputPath.resolve(s"rq2-agent-use-$handle.svg")
          writeSvg(
            outputFile,
            renderStackedTimeSeriesSvg(
              s"Agent Usage Over Time — @$handle",
              data.points,
              agentColorOrder,
              agentColors,
              "Top agent",
              defaultLines(data)
            )
          )
          println(s"Wrote agent-use SVG for @$handle to $outputFile")
    }
  }

  @main def makeRq2CommitTypeSvgs(): Unit = {
    Files.createDirectories(outputPath)

    trackedHandles.toVector.sorted.foreach { handle =>
      val data = commitTypeSeriesForDeveloper(handle)
      if data.points.nonEmpty then
          val svgPath = outputPath.resolve(s"commit-types-$handle.svg")
          writeSvg(
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

  @main def makeRq2CommitTypePerAgentSvgs(): Unit = {
    Files.createDirectories(outputPath)

    heuristicsByAgent.keys.toVector.sorted.foreach { agent =>
      val data = commitTypeSeriesForAgent(agent)
      if data.points.nonEmpty && activeStackKeys(data.points, commitTypeOrder).nonEmpty then
          val svgPath = outputPath.resolve(s"commit-types-agent-$agent.svg")
          writeSvg(
            svgPath,
            renderStackedTimeSeriesSvg(
              s"Commit Types Over Time — agent:$agent",
              data.points,
              commitTypeOrder,
              commitTypeColors,
              "Top type",
              defaultLines(data)
            )
          )
          println(s"Wrote commit-type-by-agent SVG for $agent to $svgPath")
    }
  }

  private def writeLinesChangedVariants(
      stem: String,
      title: String,
      stats: Vector[BoxPlotStats],
      fillByLabel: Map[String, String] = Map.empty,
      strokeByLabel: Map[String, String] = Map.empty
  ): Unit = {
    val variants = Vector(
      ("-outlier-suppressed", s"$title (Outliers Suppressed)", BoxPlotScale.Linear, true),
      ("-logscale", s"$title (Log Scale)", BoxPlotScale.Log10, false)
    )

    variants.foreach { case (suffix, variantTitle, scale, suppressOutliers) =>
      val svgPath = outputPath.resolve(s"$stem$suffix.svg")
      writeSvg(
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

  @main def makeRq2LinesChangedBoxplots(): Unit = {
    Files.createDirectories(outputPath)

    val byDeveloper = boxPlotStatsByDeveloper
    if byDeveloper.nonEmpty then
        val colors = developerColors(byDeveloper.map(_.label))
        writeLinesChangedVariants(
          stem = "lines-changed-by-developer",
          title = "Lines Changed by Developer",
          stats = byDeveloper,
          fillByLabel = colors,
          strokeByLabel = colors
        )

    val byAgent = boxPlotStatsByAgent
    if byAgent.nonEmpty then
        writeLinesChangedVariants(
          stem = "lines-changed-by-agent",
          title = "Lines Changed by Agent Bucket",
          stats = byAgent,
          fillByLabel = agentColors,
          strokeByLabel = agentColors
        )
  }

  @main def makeAllRq2Svgs(): Unit = {
    makeWeeklyPlotSvgs()
    makeRq2CommitTypeSvgs()
    makeRq2CommitTypePerAgentSvgs()
    makeRq2LinesChangedBoxplots()
  }
}
