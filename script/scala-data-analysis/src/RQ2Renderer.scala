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

  private def defaultLines(data: TimeSeriesData): Vector[LineSeries] =
    Vector(
      LineSeries("sampled commits", "#1d4ed8", "3 3", "#1d4ed8", data.sampledCommits),
      LineSeries("total commits (snapshot)", "#343a40", "6 4", "#343a40", data.totalCommits)
    )

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

  @main def makeAllRq2Svgs(): Unit = {
    makeWeeklyPlotSvgs()
    makeRq2CommitTypeSvgs()
    makeRq2CommitTypePerAgentSvgs()
  }
}
