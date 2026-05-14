package whataretheydoing

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate
import java.util.Locale

object Rq2CommitTypeSvg {
  import DevAgentCommitTypes.*

  case class ChartLayout(
      width: Int,
      height: Int,
      left: Double,
      right: Double,
      top: Double,
      bottom: Double,
      legendWidth: Double
  ) {
    val plotX: Double = left
    val plotY: Double = top
    val plotWidth: Double = width - left - right
    val plotHeight: Double = height - top - bottom
    val legendX: Double = plotX + 10
    val legendY: Double = plotY + 12
  }

  case class ChartScale(yMax: Int, yStep: Int)

  case class ChartSeries(
      titleHandle: String,
      rows: Vector[CommitTypePeriodRow]
  )

  private def svgEscape(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

  private def fmt(value: Double): String = String.format(Locale.US, "%.2f", Double.box(value))

  private def niceStep(maxValue: Int, targetTickCount: Int): Int = {
    if maxValue <= 0 then 1
    else
        val rawStep = maxValue.toDouble / math.max(1, targetTickCount)
        val magnitude = math.pow(10, math.floor(math.log10(rawStep)))
        val normalized = rawStep / magnitude
        val niceNormalized =
          if normalized <= 1 then 1
          else if normalized <= 2 then 2
          else if normalized <= 5 then 5
          else 10
        math.max(1, math.ceil(niceNormalized * magnitude).toInt)
  }

  private def computeScale(rows: Vector[CommitTypePeriodRow]): ChartScale = {
    val maxStack = rows.map(_.countsByType.values.sum).maxOption.getOrElse(0)
    val maxTotal = rows.map(_.totalCommits).maxOption.getOrElse(0)
    val yMaxRaw = math.max(maxStack, maxTotal)
    val yStep = niceStep(yMaxRaw, targetTickCount = 6)
    val yMax = math.max(yStep, ((yMaxRaw + yStep - 1) / yStep) * yStep)
    ChartScale(yMax = yMax, yStep = yStep)
  }

  private def slotWidth(layout: ChartLayout, count: Int): Double =
    if count <= 0 then layout.plotWidth else layout.plotWidth / count

  private def barWidth(layout: ChartLayout, count: Int): Double =
    slotWidth(layout, count) * 0.78

  private def xForBar(layout: ChartLayout, rowCount: Int, index: Int): Double =
    layout.plotX + slotWidth(layout, rowCount) * index + (slotWidth(layout, rowCount) - barWidth(layout, rowCount)) / 2.0

  private def yForValue(layout: ChartLayout, scale: ChartScale, value: Double): Double =
    layout.plotY + layout.plotHeight - (value / scale.yMax) * layout.plotHeight

  private def renderBackground(): String =
    s"<rect width='100%' height='100%' fill='white'/>"

  private def renderGridAndAxes(layout: ChartLayout, scale: ChartScale): String = {
    val gridLines =
      (0 to scale.yMax by scale.yStep).map { tick =>
        val y = yForValue(layout, scale, tick.toDouble)
        s"<line x1='${fmt(layout.plotX)}' y1='${fmt(y)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(y)}' stroke='#e9ecef' stroke-width='1' />" +
          s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='12' fill='#495057'>$tick</text>"
      }.mkString("\n")

    val axes =
      s"<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY + layout.plotHeight)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />" +
        s"<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY)}' x2='${fmt(layout.plotX)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />"

    gridLines + "\n" + axes
  }

  private def renderBarSegment(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      fill: String
  ): String =
    s"<rect x='${fmt(x)}' y='${fmt(y)}' width='${fmt(width)}' height='${fmt(height)}' fill='$fill' stroke='white' stroke-width='0.6' />"

  private def renderStackedBar(
      layout: ChartLayout,
      scale: ChartScale,
      rowCount: Int,
      index: Int,
      row: CommitTypePeriodRow,
      activeTypes: Vector[CommitType]
  ): String = {
    val x = xForBar(layout, rowCount, index)
    val width = barWidth(layout, rowCount)
    val segments =
      activeTypes.foldLeft((0.0, Vector.empty[String])) { case ((bottomValue, acc), commitType) =>
        val count = row.countsByType.getOrElse(commitType, 0)
        if count <= 0 then (bottomValue, acc)
        else
            val yTop = yForValue(layout, scale, bottomValue + count)
            val yBottom = yForValue(layout, scale, bottomValue)
            val segmentHeight = yBottom - yTop
            (
              bottomValue + count,
              acc :+ renderBarSegment(x, yTop, width, segmentHeight, commitTypeColors(commitType))
            )
      }._2

    segments.mkString("\n")
  }

  private def renderBarLabels(layout: ChartLayout, rows: Vector[CommitTypePeriodRow]): String =
    rows.zipWithIndex.map { case (row, index) =>
      val x = xForBar(layout, rows.size, index) + barWidth(layout, rows.size) / 2.0
      val y = layout.plotY + layout.plotHeight + 28.0
      s"<text x='${fmt(x)}' y='${fmt(y)}' transform='rotate(45 ${fmt(x)} ${fmt(y)})' text-anchor='start' font-size='11' fill='#495057'>${svgEscape(row.periodIso)}</text>"
    }.mkString("\n")

  private def renderTotalLine(layout: ChartLayout, scale: ChartScale, rows: Vector[CommitTypePeriodRow]): String = {
    val points = rows.zipWithIndex.map { case (row, index) =>
      val cx = xForBar(layout, rows.size, index) + barWidth(layout, rows.size) / 2.0
      val cy = yForValue(layout, scale, row.totalCommits.toDouble)
      s"${fmt(cx)},${fmt(cy)}"
    }.mkString(" ")

    val markers = rows.zipWithIndex.map { case (row, index) =>
      val cx = xForBar(layout, rows.size, index) + barWidth(layout, rows.size) / 2.0
      val cy = yForValue(layout, scale, row.totalCommits.toDouble)
      s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='#343a40' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
    }.mkString("\n")

    s"<polyline fill='none' stroke='#343a40' stroke-width='2' stroke-dasharray='6 4' points='$points' />\n$markers"
  }

  private def renderLegend(layout: ChartLayout, activeTypes: Vector[CommitType]): String = {
    val entries = Vector("total commits (snapshot)") ++ activeTypes.map(_.toString.toLowerCase)
    val boxHeight = 18 + entries.size * 20
    val box =
      s"<rect x='${fmt(layout.legendX)}' y='${fmt(layout.legendY)}' width='${fmt(layout.legendWidth)}' height='${fmt(boxHeight)}' rx='4' ry='4' fill='white' fill-opacity='0.92' stroke='#ced4da' stroke-width='1' />"

    val lines = entries.zipWithIndex.map { case (label, idx) =>
      val y = layout.legendY + 18 + idx * 20
      if idx == 0 then
          s"<line x1='${fmt(layout.legendX + 10)}' y1='${fmt(y - 4)}' x2='${fmt(layout.legendX + 30)}' y2='${fmt(y - 4)}' stroke='#343a40' stroke-width='2' stroke-dasharray='6 4' />" +
            s"<rect x='${fmt(layout.legendX + 17)}' y='${fmt(y - 7)}' width='6' height='6' fill='#343a40' transform='rotate(45 ${fmt(layout.legendX + 20)} ${fmt(y - 4)})' />" +
            s"<text x='${fmt(layout.legendX + 38)}' y='${fmt(y)}' font-size='12' fill='#212529'>${svgEscape(label)}</text>"
      else
          val commitType = activeTypes(idx - 1)
          s"<rect x='${fmt(layout.legendX + 10)}' y='${fmt(y - 10)}' width='18' height='12' fill='${commitTypeColors(commitType)}' stroke='#ffffff' stroke-width='0.6' />" +
            s"<text x='${fmt(layout.legendX + 38)}' y='${fmt(y)}' font-size='12' fill='#212529'>${svgEscape(label)}</text>"
    }.mkString("\n")

    box + "\n" + lines
  }

  private def renderTitles(layout: ChartLayout, title: String): String =
    s"<text x='${fmt(layout.width / 2.0)}' y='24' text-anchor='middle' font-size='18' font-weight='700' fill='#111827'>${svgEscape(title)}</text>" +
      s"\n<text x='20' y='${fmt(layout.plotY + layout.plotHeight / 2.0)}' transform='rotate(-90 20 ${fmt(layout.plotY + layout.plotHeight / 2.0)})' text-anchor='middle' font-size='14' fill='#212529'>Commits</text>" +
      s"\n<text x='${fmt(layout.plotX + layout.plotWidth / 2.0)}' y='${fmt(layout.height - 40.0)}' text-anchor='middle' font-size='14' fill='#212529'>Period</text>"

  private def renderFooter(layout: ChartLayout, rows: Vector[CommitTypePeriodRow], activeTypes: Vector[CommitType]): String = {
    val totalSnapshot = rows.map(_.totalCommits).sum
    val embeddedRecords = rows.map(_.countsByType.values.sum).sum
    val byTypeTotals = activeTypes.map(t => t -> rows.map(_.countsByType.getOrElse(t, 0)).sum).filter(_._2 > 0)
    val dominantType = byTypeTotals.sortBy(-_._2).headOption.map { case (t, c) => s"Top type: ${t.toString.toLowerCase} ($c)" }.getOrElse("Top type: -")
    val footer = s"Total snapshot commits: $totalSnapshot  |  Embedded records: $embeddedRecords  |  Periods: ${rows.size}  |  $dominantType"
    s"<text x='${fmt(layout.width / 2.0)}' y='${fmt(layout.height - 12.0)}' text-anchor='middle' font-size='12' fill='#212529'>${svgEscape(footer)}</text>"
  }

  private def trimLeadingEmptyWeeks(rows: Vector[CommitTypePeriodRow]): Vector[CommitTypePeriodRow] =
    rows.dropWhile(row => row.totalCommits == 0 && row.countsByType.values.sum == 0)

  private def activeCommitTypes(rows: Vector[CommitTypePeriodRow]): Vector[CommitType] =
    commitTypeOrder.filter(commitType => rows.exists(_.countsByType.getOrElse(commitType, 0) > 0))

  private def renderChart(title: String, rows0: Vector[CommitTypePeriodRow]): String = {
    val rows = trimLeadingEmptyWeeks(rows0)
    val activeTypes = activeCommitTypes(rows)
    val layout = ChartLayout(
      width = math.max(1200, rows.size * 48 + 240),
      height = 620,
      left = 64,
      right = 24,
      top = 32,
      bottom = 132,
      legendWidth = 190
    )
    val scale = computeScale(rows)

    val bars = rows.zipWithIndex.map { case (row, index) =>
      renderStackedBar(layout, scale, rows.size, index, row, activeTypes)
    }.mkString("\n")

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
${renderGridAndAxes(layout, scale)}
${renderTitles(layout, title)}
$bars
${renderTotalLine(layout, scale, rows)}
${renderBarLabels(layout, rows)}
${renderLegend(layout, activeTypes)}
${renderFooter(layout, rows, activeTypes)}
</svg>
"""
  }

  private def writeSvg(path: java.nio.file.Path, svg: String): Unit = {
    Files.writeString(path, svg, StandardCharsets.UTF_8)
    ()
  }

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
        countsByType = commitTypeOrder.map(t => t -> countsByWeekType.getOrElse((week, t), 0)).toMap
      )
    }
  }

  @main def makeRq2CommitTypeSvgs(): Unit = {
    println(s"Running with dataDir=${dataPath.toString} granularity=week")
    Files.createDirectories(outputPath)
    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    trackedHandles.toVector.sorted.foreach { handle =>
      val svgRows = commitTypeRowsForDeveloper(handle)
      if svgRows.nonEmpty then
          val svgPath = outputPath.resolve(s"commit-types-$handle.svg")
          writeSvg(svgPath, renderChart(s"Commit Types Over Time — @$handle", svgRows))
          println(s"Wrote commit-type SVG for @$handle to $svgPath")
    }
  }

  @main def makeRq2CommitTypePerAgentSvgs(): Unit = {
    println(s"Running with dataDir=${dataPath.toString} granularity=week")
    Files.createDirectories(outputPath)
    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

    heuristicsByAgent.keys.toVector.sorted.foreach { agent =>
      val svgRows = commitTypeRowsForAgent(agent)
      if trimLeadingEmptyWeeks(svgRows).nonEmpty && activeCommitTypes(svgRows).nonEmpty then
          val svgPath = outputPath.resolve(s"commit-types-agent-$agent.svg")
          writeSvg(svgPath, renderChart(s"Commit Types Over Time — agent:$agent", svgRows))
          println(s"Wrote commit-type-by-agent SVG for $agent to $svgPath")
    }
  }
}
