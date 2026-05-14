package whataretheydoing

import java.nio.file.Files
import java.time.LocalDate

object RQ2Renderer {
  import DevAgentCommitTypes.*
  import SVGGraphLib.*

  private val totalCommitsLine = LineSeries(
    label = "total commits (snapshot)",
    stroke = "#343a40",
    dashArray = "6 4",
    markerFill = "#343a40",
    valueOf = _.totalCommits
  )

  private val sampledCommitsLine = LineSeries(
    label = "sampled commits",
    stroke = "#1d4ed8",
    dashArray = "3 3",
    markerFill = "#1d4ed8",
    valueOf = _.sampledCommits
  )

  private val defaultLines = Vector(sampledCommitsLine, totalCommitsLine)

  private def toStackedCommitTypeRows(rows: Vector[CommitTypePeriodRow]): Vector[StackedTimeRow] =
    rows.map(row =>
      StackedTimeRow(
        periodIso = row.periodIso,
        totalCommits = row.totalCommits,
        sampledCommits = row.sampledCommits,
        counts = row.countsByType.map { case (k, v) => k.toString.toLowerCase -> v }
      )
    )

  private def toStackedAgentRows(rows: Vector[AgentPeriodRow]): Vector[StackedTimeRow] =
    rows.map(row =>
      StackedTimeRow(
        periodIso = row.periodIso,
        totalCommits = row.totalCommits,
        sampledCommits = row.sampledCommits,
        counts = row.countsByAgent
      )
    )

  private def commitTypeRowsForAgent(agent: String): Vector[CommitTypePeriodRow] = {
    val agentCommitsByWeek: Vector[(LocalDate, CommitType)] =
      aggregateData.iterator
        .flatMap { case (_, _, _, snapshot) =>
          snapshot.days.iterator.flatMap { case (day, dayData) =>
            val week = weekStart(LocalDate.parse(day))
            dayData.commits.iterator.flatMap { commit =>
              commitSignals.get(commit.sha).toVector.flatMap { classified =>
                if classified.agents.contains(agent) then Vector((week, classified.commitType)) else Vector.empty
              }
            }
          }
        }
        .toVector

    val totalsByWeek: Map[LocalDate, Int] =
      agentCommitsByWeek.groupMapReduce(_._1)(_ => 1)(_ + _)

    val countsByWeekType: Map[(LocalDate, CommitType), Int] =
      agentCommitsByWeek.groupMapReduce(identity)(_ => 1)(_ + _)

    totalsByWeek.keys.toVector.sorted.map { week =>
      CommitTypePeriodRow(
        periodIso = week.toString,
        totalCommits = totalsByWeek(week),
        sampledCommits = totalsByWeek(week),
        countsByType = commitTypeOrder.map(t => t -> countsByWeekType.getOrElse((week, t), 0)).toMap
      )
    }
  }

  @main def makeWeeklyPlotSvgs(): Unit = {
    println(s"Running with dataDir=${dataPath.toString} granularity=week")
    Files.createDirectories(outputPath)
    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    trackedHandles.toVector.sorted.foreach { handle =>
      val rows = agentPeriodRowsForDeveloper(handle)
      if rows.nonEmpty then
          val outputFile = outputPath.resolve(s"rq2-agent-use-$handle.svg")
          writeSvg(
            outputFile,
            renderStackedTimeSeriesSvg(
              s"Agent Usage Over Time — @$handle",
              toStackedAgentRows(rows),
              agentColorOrder,
              agentColors,
              "Top agent",
              defaultLines
            )
          )
          println(s"Wrote agent-use SVG for @$handle to $outputFile")
    }
  }

  @main def makeRq2CommitTypeSvgs(): Unit = {
    println(s"Running with dataDir=${dataPath.toString} granularity=week")
    Files.createDirectories(outputPath)
    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    val stackOrder = commitTypeOrder.map(_.toString.toLowerCase)
    val colors = commitTypeColors.map((k, v) => k.toString.toLowerCase -> v)

    trackedHandles.toVector.sorted.foreach { handle =>
      val rows = commitTypeRowsForDeveloper(handle)
      if rows.nonEmpty then
          val svgPath = outputPath.resolve(s"commit-types-$handle.svg")
          writeSvg(
            svgPath,
            renderStackedTimeSeriesSvg(
              s"Commit Types Over Time — @$handle",
              toStackedCommitTypeRows(rows),
              stackOrder,
              colors,
              "Top type",
              defaultLines
            )
          )
          println(s"Wrote commit-type SVG for @$handle to $svgPath")
    }
  }

  @main def makeRq2CommitTypePerAgentSvgs(): Unit = {
    println(s"Running with dataDir=${dataPath.toString} granularity=week")
    Files.createDirectories(outputPath)
    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    val stackOrder = commitTypeOrder.map(_.toString.toLowerCase)
    val colors = commitTypeColors.map((k, v) => k.toString.toLowerCase -> v)

    heuristicsByAgent.keys.toVector.sorted.foreach { agent =>
      val rows = toStackedCommitTypeRows(commitTypeRowsForAgent(agent))
      if trimLeadingEmptyWeeks(rows).nonEmpty && activeKeys(rows, stackOrder).nonEmpty then
          val svgPath = outputPath.resolve(s"commit-types-agent-$agent.svg")
          writeSvg(
            svgPath,
            renderStackedTimeSeriesSvg(
              s"Commit Types Over Time — agent:$agent",
              rows,
              stackOrder,
              colors,
              "Top type",
              defaultLines
            )
          )
          println(s"Wrote commit-type-by-agent SVG for $agent to $svgPath")
    }
  }

  @main def makeAllRq2Svgs(): Unit = {
    makeWeeklyPlotSvgs()
    makeRq2CommitTypeSvgs()
    makeRq2CommitTypePerAgentSvgs()
  }
}
