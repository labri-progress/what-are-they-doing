package whataretheydoing

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

object SVGGraphLib {
  case class StackedTimeRow(
      periodIso: String,
      totalCommits: Int,
      sampledCommits: Int,
      counts: Map[String, Int]
  )

  case class LineSeries(
      label: String,
      stroke: String,
      dashArray: String,
      markerFill: String,
      valueOf: StackedTimeRow => Int
  )

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

  private def svgEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def fmt(value: Double): String = String.format(Locale.US, "%.2f", Double.box(value))

  private def niceStep(maxValue: Int, targetTickCount: Int): Int =
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

  private def computeScale(rows: Vector[StackedTimeRow], lines: Vector[LineSeries]): ChartScale = {
    val maxStack = rows.map(_.counts.values.sum).maxOption.getOrElse(0)
    val maxLine = lines.flatMap(line => rows.map(row => line.valueOf(row))).maxOption.getOrElse(0)
    val yMaxRaw = math.max(maxStack, maxLine)
    val yStep = niceStep(yMaxRaw, 6)
    val yMax = math.max(yStep, ((yMaxRaw + yStep - 1) / yStep) * yStep)
    ChartScale(yMax, yStep)
  }

  private def slotWidth(layout: ChartLayout, count: Int): Double =
    if count <= 0 then layout.plotWidth else layout.plotWidth / count

  private def barWidth(layout: ChartLayout, count: Int): Double =
    slotWidth(layout, count) * 0.78

  private def xForBar(layout: ChartLayout, rowCount: Int, index: Int): Double =
    layout.plotX + slotWidth(layout, rowCount) * index + (slotWidth(layout, rowCount) - barWidth(layout, rowCount)) / 2.0

  private def yForValue(layout: ChartLayout, scale: ChartScale, value: Double): Double =
    layout.plotY + layout.plotHeight - (value / scale.yMax) * layout.plotHeight

  private def renderBackground(): String = "<rect width='100%' height='100%' fill='white'/>"

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

  private def renderBarSegment(x: Double, y: Double, width: Double, height: Double, fill: String): String =
    s"<rect x='${fmt(x)}' y='${fmt(y)}' width='${fmt(width)}' height='${fmt(height)}' fill='$fill' stroke='white' stroke-width='0.6' />"

  private def renderStackedBar(
      layout: ChartLayout,
      scale: ChartScale,
      rowCount: Int,
      index: Int,
      row: StackedTimeRow,
      activeKeys: Vector[String],
      colors: Map[String, String]
  ): String = {
    val x = xForBar(layout, rowCount, index)
    val width = barWidth(layout, rowCount)
    activeKeys.foldLeft((0.0, Vector.empty[String])) { case ((bottomValue, acc), key) =>
      val count = row.counts.getOrElse(key, 0)
      if count <= 0 then (bottomValue, acc)
      else
          val yTop = yForValue(layout, scale, bottomValue + count)
          val yBottom = yForValue(layout, scale, bottomValue)
          (bottomValue + count, acc :+ renderBarSegment(x, yTop, width, yBottom - yTop, colors.getOrElse(key, "#adb5bd")))
    }._2.mkString("\n")
  }

  private def renderBarLabels(layout: ChartLayout, rows: Vector[StackedTimeRow]): String =
    rows.zipWithIndex.map { case (row, index) =>
      val x = xForBar(layout, rows.size, index) + barWidth(layout, rows.size) / 2.0
      val y = layout.plotY + layout.plotHeight + 28.0
      s"<text x='${fmt(x)}' y='${fmt(y)}' transform='rotate(45 ${fmt(x)} ${fmt(y)})' text-anchor='start' font-size='11' fill='#495057'>${svgEscape(row.periodIso)}</text>"
    }.mkString("\n")

  private def renderLineSeries(layout: ChartLayout, scale: ChartScale, rows: Vector[StackedTimeRow], line: LineSeries): String = {
    val points = rows.zipWithIndex.map { case (row, index) =>
      val cx = xForBar(layout, rows.size, index) + barWidth(layout, rows.size) / 2.0
      val cy = yForValue(layout, scale, line.valueOf(row).toDouble)
      s"${fmt(cx)},${fmt(cy)}"
    }.mkString(" ")

    val markers = rows.zipWithIndex.map { case (row, index) =>
      val cx = xForBar(layout, rows.size, index) + barWidth(layout, rows.size) / 2.0
      val cy = yForValue(layout, scale, line.valueOf(row).toDouble)
      s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='${line.markerFill}' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
    }.mkString("\n")

    s"<polyline fill='none' stroke='${line.stroke}' stroke-width='2' stroke-dasharray='${line.dashArray}' points='$points' />\n$markers"
  }

  private def renderOverlayLines(layout: ChartLayout, scale: ChartScale, rows: Vector[StackedTimeRow], lines: Vector[LineSeries]): String =
    lines.map(renderLineSeries(layout, scale, rows, _)).mkString("\n")

  private def renderLegend(
      layout: ChartLayout,
      activeKeys: Vector[String],
      colors: Map[String, String],
      activeLines: Vector[LineSeries]
  ): String = {
    val entries = activeKeys.map(Left(_)) ++ activeLines.map(Right(_))
    val boxHeight = 18 + entries.size * 20
    val box =
      s"<rect x='${fmt(layout.legendX)}' y='${fmt(layout.legendY)}' width='${fmt(layout.legendWidth)}' height='${fmt(boxHeight)}' rx='4' ry='4' fill='white' fill-opacity='0.92' stroke='#ced4da' stroke-width='1' />"

    val lines = entries.zipWithIndex.map {
      case (Left(key), idx) =>
        val y = layout.legendY + 18 + idx * 20
        s"<rect x='${fmt(layout.legendX + 10)}' y='${fmt(y - 10)}' width='18' height='12' fill='${colors.getOrElse(key, "#adb5bd")}' stroke='#ffffff' stroke-width='0.6' />" +
          s"<text x='${fmt(layout.legendX + 38)}' y='${fmt(y)}' font-size='12' fill='#212529'>${svgEscape(key)}</text>"
      case (Right(line), idx) =>
        val y = layout.legendY + 18 + idx * 20
        s"<line x1='${fmt(layout.legendX + 10)}' y1='${fmt(y - 4)}' x2='${fmt(layout.legendX + 30)}' y2='${fmt(y - 4)}' stroke='${line.stroke}' stroke-width='2' stroke-dasharray='${line.dashArray}' />" +
          s"<rect x='${fmt(layout.legendX + 17)}' y='${fmt(y - 7)}' width='6' height='6' fill='${line.markerFill}' transform='rotate(45 ${fmt(layout.legendX + 20)} ${fmt(y - 4)})' />" +
          s"<text x='${fmt(layout.legendX + 38)}' y='${fmt(y)}' font-size='12' fill='#212529'>${svgEscape(line.label)}</text>"
    }.mkString("\n")

    box + "\n" + lines
  }

  private def renderTitles(layout: ChartLayout, title: String): String =
    s"<text x='${fmt(layout.width / 2.0)}' y='24' text-anchor='middle' font-size='18' font-weight='700' fill='#111827'>${svgEscape(title)}</text>" +
      s"\n<text x='20' y='${fmt(layout.plotY + layout.plotHeight / 2.0)}' transform='rotate(-90 20 ${fmt(layout.plotY + layout.plotHeight / 2.0)})' text-anchor='middle' font-size='14' fill='#212529'>Commits</text>" +
      s"\n<text x='${fmt(layout.plotX + layout.plotWidth / 2.0)}' y='${fmt(layout.height - 40.0)}' text-anchor='middle' font-size='14' fill='#212529'>Period</text>"

  private def renderFooter(layout: ChartLayout, rows: Vector[StackedTimeRow], activeKeys: Vector[String], topLabel: String): String = {
    val totalSnapshot = rows.map(_.totalCommits).sum
    val sampledCommits = rows.map(_.sampledCommits).sum
    val embeddedRecords = rows.map(_.counts.values.sum).sum
    val totalsByKey = activeKeys.map(key => key -> rows.map(_.counts.getOrElse(key, 0)).sum).filter(_._2 > 0)
    val dominant = totalsByKey.sortBy(-_._2).headOption.map { case (key, c) => s"$topLabel: $key ($c)" }.getOrElse(s"$topLabel: -")
    val footer = s"Total snapshot commits: $totalSnapshot  |  Sampled commits: $sampledCommits  |  Embedded records: $embeddedRecords  |  Periods: ${rows.size}  |  $dominant"
    s"<text x='${fmt(layout.width / 2.0)}' y='${fmt(layout.height - 12.0)}' text-anchor='middle' font-size='12' fill='#212529'>${svgEscape(footer)}</text>"
  }

  def activeKeys(rows: Vector[StackedTimeRow], stackOrder: Vector[String]): Vector[String] = {
    val present: Set[String] = rows.iterator.flatMap(_.counts.iterator.collect { case (key, value) if value > 0 => key }).toSet
    val known = stackOrder.filter(present.contains)
    val extra = present.diff(stackOrder.toSet).toVector.sorted.filterNot(_ == "no agent")
    known.filterNot(_ == "no agent") ++ extra ++ (if present.contains("no agent") then Vector("no agent") else Vector.empty)
  }

  def activeLineSeries(rows: Vector[StackedTimeRow], lines: Vector[LineSeries]): Vector[LineSeries] =
    lines.filter(line => rows.exists(row => line.valueOf(row) > 0))

  def renderStackedTimeSeriesSvg(
      title: String,
      rows: Vector[StackedTimeRow],
      stackOrder: Vector[String],
      colors: Map[String, String],
      topLabel: String,
      lineSeries: Vector[LineSeries]
  ): String = {
    val active = activeKeys(rows, stackOrder)
    val activeLines = activeLineSeries(rows, lineSeries)
    val layout = ChartLayout(
      width = math.max(1200, rows.size * 48 + 240),
      height = 620,
      left = 64,
      right = 24,
      top = 32,
      bottom = 132,
      legendWidth = 190
    )
    val scale = computeScale(rows, activeLines)

    val bars = rows.zipWithIndex.map { case (row, index) =>
      renderStackedBar(layout, scale, rows.size, index, row, active, colors)
    }.mkString("\n")

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
${renderGridAndAxes(layout, scale)}
${renderTitles(layout, title)}
$bars
${renderOverlayLines(layout, scale, rows, activeLines)}
${renderBarLabels(layout, rows)}
${renderLegend(layout, active, colors, activeLines)}
${renderFooter(layout, rows, active, topLabel)}
</svg>
"""
  }

  def writeSvg(path: java.nio.file.Path, svg: String): Unit = {
    Files.writeString(path, svg, StandardCharsets.UTF_8)
    ()
  }
}
