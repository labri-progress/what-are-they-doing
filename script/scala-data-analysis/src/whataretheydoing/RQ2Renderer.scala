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
    "pi",
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
    "pi"          -> "#e83e8c",
    "gemini"      -> "#4285f4",
    "qwen_code"   -> "#43aa8b",
    "roo_code"    -> "#90be6d",
    "sweep"       -> "#fa5252",
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
    Files.createDirectories(GlobalPaths.outputPath.resolve("agent-use"))

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

            val outputFile = GlobalPaths.outputPath.resolve("agent-use").resolve(s"rq2-agent-use-$handle.svg")
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
    Files.createDirectories(GlobalPaths.outputPath.resolve("commit-types"))

    trackedHandles.toVector.map { handle =>
      Sync {
        val data = commitTypeSeriesForDeveloper(handle)
        if data.points.nonEmpty then
            val svgPath = GlobalPaths.outputPath.resolve("commit-types").resolve(s"commit-types-$handle.svg")
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
    Files.createDirectories(GlobalPaths.outputPath.resolve("commit-types"))

    val agentCommitCounts: Map[String, Int] =
      CommitProcessing.allCommitDetailsBySha1.valuesIterator
        .flatMap(entry => entry.classification.agents)
        .toVector
        .groupMapReduce(identity)(_ => 1)(_ + _)

    val legendSync = Sync {
      val svgPath = GlobalPaths.outputPath.resolve("commit-types").resolve("commit-types-legend.svg")
      writeSvgAndConvertToPdf(svgPath, renderLegendSvg(commitTypeOrder, commitTypeColors))
      println(s"Wrote commit-types legend SVG to $svgPath")
    }

    val agentSyncs = heuristicsByAgent.keys.toVector.sorted
      .filter(agent => agentCommitCounts.getOrElse(agent, 0) >= 50)
      .map { agent =>
      Sync {
        val data = commitTypeSeriesForAgent(agent)
        if data.points.nonEmpty && activeStackKeys(data.points, commitTypeOrder).nonEmpty then
            val svgPath = GlobalPaths.outputPath.resolve("commit-types").resolve(s"commit-types-agent-$agent.svg")
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
    Files.createDirectories(GlobalPaths.outputPath.resolve("lines-changed"))

    trackedHandles.toVector.sorted.map { handle =>
      Sync {
        val data = linesChangedByAgentSeriesForDeveloper.getOrElse(
          handle,
          TimeSeriesData(Vector.empty, Vector.empty, Vector.empty)
        )
        if data.points.nonEmpty && activeStackKeys(data.points, agentColorOrder).nonEmpty then
            val svgPath = GlobalPaths.outputPath.resolve("lines-changed").resolve(s"lines-changed-weekly-agents-$handle.svg")
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
      val svgPath = GlobalPaths.outputPath.resolve("lines-changed").resolve(s"$stem$suffix.svg")
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
    Files.createDirectories(GlobalPaths.outputPath.resolve("lines-changed"))

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

  private def agentsByCommitTypeSyncs(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath.resolve("agents-by-commit-type"))
    Files.createDirectories(GlobalPaths.outputPath.resolve("agents-by-commit-type").resolve("per-developer"))

    val commitTypeColumns = CommitType.values.toVector.sortBy(_.ordinal)

    // Reorganize: developer → commit_type → agent → count
    val byDevByTypeByAgent: Map[String, Map[CommitType, Map[String, Int]]] =
      taskTypeCounts.toVector
        .flatMap { case ((developer, agent), typeCounts) =>
          typeCounts.toVector.map { case (ct, count) => (developer, ct, agent, count) }
        }
        .groupBy(_._1)
        .view.mapValues { entries =>
          entries.groupBy(_._2)
            .view.mapValues(ctEntries => ctEntries.map(e => e._3 -> e._4).toMap)
            .toMap
        }.toMap

    val developers = byDevByTypeByAgent.keys.toVector.sorted

    // One percentage-stacked SVG per developer: x=commit_type, stacked by agent
    val perDevSyncs: Vector[Sync[Any, Unit]] = developers.map { developer =>
      Sync {
        val typeMap = byDevByTypeByAgent.getOrElse(developer, Map.empty)
        val points: Vector[StackedBarPoint] = commitTypeColumns.flatMap { ct =>
          val agentCounts = typeMap.getOrElse(ct, Map.empty)
          if agentCounts.isEmpty then None
          else Some(StackedBarPoint(xLabel = ct.toString.toLowerCase, values = agentCounts))
        }.toVector

        if points.nonEmpty then
          val countPerType = points.map(p => p.values.values.sum)
          val svgPath = GlobalPaths.outputPath.resolve("agents-by-commit-type").resolve("per-developer").resolve(s"$developer.svg")
          writeSvgAndConvertToPdf(
            svgPath,
            renderPercentageStackedWithCountLineSvg(
              s"Agent Distribution by Task — $developer",
              points,
              agentColorOrder,
              agentColors,
              countPerType,
              yRightLabel = "Commits",
              xAxisLabel = "Task"
            )
          )
          println(s"Wrote agents-by-commit-type SVG for $developer to $svgPath")
      }
    }

    // Aggregate total: overall agent distribution across all types
    val agentTypeCounts: Map[String, Map[CommitType, Int]] = taskTypeCounts
      .groupMapReduce((k, _) => k.agent)((_, v) => v) { (a, b) =>
        commitTypeColumns.map(ct => ct -> (a.getOrElse(ct, 0) + b.getOrElse(ct, 0))).toMap
      }

    val totalSync: Sync[Any, Unit] = Sync {
      val points: Vector[StackedBarPoint] = agentColorOrder.flatMap { agent =>
        val count = commitTypeColumns.map(ct => agentTypeCounts.getOrElse(agent, Map.empty).getOrElse(ct, 0)).sum
        if count > 0 then Some(StackedBarPoint(xLabel = agent, values = Map(agent -> count)))
        else None
      }.toVector

      if points.nonEmpty then
        val svgPath = GlobalPaths.outputPath.resolve("agents-by-commit-type").resolve("agents-by-commit-type-total.svg")
        writeSvgAndConvertToPdf(
          svgPath,
          renderStackedTimeSeriesSvg(
            "Commits by Agent — all types",
            points,
            agentColorOrder,
            agentColors,
            "Top agent",
            Vector.empty,
            xAxisLabel = "Agent"
          )
        )
        println(s"Wrote agents-by-commit-type SVG for total to $svgPath")
    }

    perDevSyncs :+ totalSync
  }

  @main def makeAgentsByCommitTypeSvgs(): Unit = {
    agentsByCommitTypeSyncs().foreach(_.run(using ()))
  }

  private def agentShareWeeklyPlotSyncs(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath.resolve("agent-share"))
    trackedHandles.toVector.map { handle =>
      Sync {
        val data = agentSeriesForDeveloper(handle)
        if data.points.nonEmpty then
          val svgPath = GlobalPaths.outputPath.resolve("agent-share").resolve(s"agent-share-weekly-$handle.svg")
          writeSvgAndConvertToPdf(
            svgPath,
            renderPercentageStackedWithCountLineSvg(
              s"Agent Share Over Time — @$handle",
              data.points,
              agentColorOrder,
              agentColors,
              data.totalCommits
            )
          )
          println(s"Wrote agent-share weekly SVG for @$handle to $svgPath")
      }
    }
  }

  @main def makeAgentShareWeeklyPlotSvgs(): Unit = {
    agentShareWeeklyPlotSyncs().foreach(_.run(using ()))
  }

  @main def makeCommitTypesByAgentSvg(): Unit = {
    Files.createDirectories(GlobalPaths.outputPath.resolve("commit-types"))

    val agentTypeCounts: Map[String, Map[CommitType, Int]] = DataAnalysis.taskTypeCounts
      .groupMapReduce((k, _) => k.agent)((_, v) => v) { (a, b) =>
        CommitType.values.map { ct =>
          ct -> (a.getOrElse(ct, 0) + b.getOrElse(ct, 0))
        }.toMap
      }

    val points: Vector[StackedBarPoint] = agentColorOrder.flatMap { agent =>
      val typeCounts = agentTypeCounts.getOrElse(agent, Map.empty)
      if typeCounts.values.sum > 0 then
        Some(StackedBarPoint(agent, typeCounts.map((ct, c) => ct.toString.toLowerCase -> c)))
      else None
    }.toVector

    if points.nonEmpty then
      val countPerAgent = points.map(_.values.values.sum)

      val svgPath = GlobalPaths.outputPath.resolve("commit-types").resolve("commit-types-by-agent.svg")
      writeSvgAndConvertToPdf(
        svgPath,
        renderStackedTimeSeriesSvg(
          "Commit Types by Agent",
          points,
          commitTypeOrder,
          commitTypeColors,
          "Top type",
          Vector.empty,
          xAxisLabel = "Agent"
        )
      )
      println(s"Wrote commit-types-by-agent SVG to $svgPath")

      val svgPathPct = GlobalPaths.outputPath.resolve("commit-types").resolve("commit-types-by-agent-percentage.svg")
      writeSvgAndConvertToPdf(
        svgPathPct,
        renderPercentageStackedWithCountLineSvg(
          "Commit Type Distribution by Agent",
          points,
          commitTypeOrder,
          commitTypeColors,
          countPerAgent,
          yRightLabel = "Total commits",
          xAxisLabel = "Agent"
        )
      )
      println(s"Wrote commit-types-by-agent-percentage SVG to $svgPathPct")
  }

  private def commitTypesByAgentPerDeveloperSyncs(): Seq[Sync[Any, Unit]] = {
    Files.createDirectories(GlobalPaths.outputPath.resolve("commit-types").resolve("per-developer"))

    val developers = DataAnalysis.taskTypeCounts.keys.map(_.developer).toVector.distinct.sorted

    developers.map { developer =>
      Sync {
        val devAgentCounts = DataAnalysis.taskTypeCounts.collect {
          case (k, v) if k.developer == developer => k.agent -> v
        }

        val points: Vector[StackedBarPoint] = agentColorOrder.flatMap { agent =>
          val typeCounts = devAgentCounts.getOrElse(agent, Map.empty)
          if typeCounts.values.sum > 0 then
            Some(StackedBarPoint(agent, typeCounts.map((ct, c) => ct.toString.toLowerCase -> c)))
          else None
        }.toVector

        if points.nonEmpty then
          val countPerAgent = points.map(_.values.values.sum)

          val svgPath = GlobalPaths.outputPath.resolve("commit-types").resolve("per-developer").resolve(s"$developer.svg")
          writeSvgAndConvertToPdf(
            svgPath,
            renderStackedTimeSeriesSvg(
              s"Commit Types by Agent \u2014 $developer",
              points,
              commitTypeOrder,
              commitTypeColors,
              "Top type",
              Vector.empty,
              xAxisLabel = "Agent"
            )
          )
          println(s"Wrote commit-types-by-agent SVG for $developer to $svgPath")

          val svgPathPct = GlobalPaths.outputPath.resolve("commit-types").resolve("per-developer").resolve(s"$developer-percentage.svg")
          writeSvgAndConvertToPdf(
            svgPathPct,
            renderPercentageStackedWithCountLineSvg(
              s"Commit Type Distribution by Agent \u2014 $developer",
              points,
              commitTypeOrder,
              commitTypeColors,
              countPerAgent,
              yRightLabel = "Total commits",
              xAxisLabel = "Agent"
            )
          )
          println(s"Wrote commit-types-by-agent-percentage SVG for $developer to $svgPathPct")
      }
    }
  }

  @main def makeCommitTypesByAgentPerDeveloperSvgs(): Unit = {
    commitTypesByAgentPerDeveloperSyncs().foreach(_.run(using ()))
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
