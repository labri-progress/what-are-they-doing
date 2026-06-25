package whataretheydoing

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

object SVGGraphLib {
  case class StackedBarPoint(
      xLabel: String,
      values: Map[String, Int]
  )

  case class LineSeries(
      label: String,
      stroke: String,
      dashArray: String,
      markerFill: String,
      values: Vector[Int]
  )

  case class BoxPlotStats(
      label: String,
      count: Int,
      min: Double,
      q1: Double,
      median: Double,
      q3: Double,
      max: Double,
      whiskerLow: Double,
      whiskerHigh: Double,
      mean: Double,
      outliers: Vector[Double]
  )

  enum BoxPlotScale:
      case Linear, Log10

  case class ChartLayout(
      width: Int,
      height: Int,
      left: Double,
      right: Double,
      top: Double,
      bottom: Double,
      legendWidth: Double
  ) {
    val plotX: Double      = left
    val plotY: Double      = top
    val plotWidth: Double  = width - left - right
    val plotHeight: Double = height - top - bottom
    val legendX: Double    = plotX + plotWidth + 16
    val legendY: Double    = plotY
  }

  case class ChartScale(yMax: Int, yStep: Int)

  object FontConfig:
    val title         = 22
    val axisLabel     = 24
    val tick          = 24
    val barLabel      = 22
    val legend        = 22
    val legendEntry   = 16
    val footer        = 18
    val boxPlotTick   = 12
    val boxPlotLabel  = 11
    val boxPlotTitle  = 18
    val boxPlotAxis   = 14

  private def yAxisLabelPos: Double = FontConfig.axisLabel * 1.5

  private def autoLeftMargin: Double = yAxisLabelPos + FontConfig.axisLabel * 1.5 + FontConfig.tick * 2.0

  private def svgEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def fmt(value: Double): String = String.format(Locale.US, "%.2f", Double.box(value))

  private def formatSi(value: Double): String = {
    val rounded = math.rint(value).toLong
    if value != rounded.toDouble then fmt(value)
    else if rounded != 0 && rounded % 1000000000L == 0 then s"${rounded / 1000000000L}G"
    else if rounded != 0 && rounded % 1000000L == 0 then s"${rounded / 1000000L}M"
    else if rounded != 0 && rounded % 1000L == 0 then s"${rounded / 1000L}k"
    else rounded.toString
  }

  private def niceStep(maxValue: Int, targetTickCount: Int): Int =
    if maxValue <= 0 then 1
    else
        val rawStep        = maxValue.toDouble / math.max(1, targetTickCount)
        val magnitude      = math.pow(10, math.floor(math.log10(rawStep)))
        val normalized     = rawStep / magnitude
        val niceNormalized =
          if normalized <= 1 then 1
          else if normalized <= 2 then 2
          else if normalized <= 5 then 5
          else 10
        math.max(1, math.ceil(niceNormalized * magnitude).toInt)

  def activeStackKeys(points: Vector[StackedBarPoint], stackOrder: Vector[String]): Vector[String] = {
    val present: Set[String] = points.iterator.flatMap(_.values.iterator.collect {
      case (key, value) if value > 0 => key
    }).toSet
    val known = stackOrder.filter(present.contains)
    val extra = present.diff(stackOrder.toSet).toVector.sorted.filterNot(_ == "no signal")
    known.filterNot(_ == "no signal") ++ extra ++ (if present.contains("no signal") then Vector("no signal")
                                                   else Vector.empty)
  }

  def activeLineSeries(lines: Vector[LineSeries]): Vector[LineSeries] =
    lines.filter(_.values.exists(_ > 0))

  private def computeScale(points: Vector[StackedBarPoint], lines: Vector[LineSeries]): ChartScale = {
    val maxStack = points.map(_.values.values.sum).maxOption.getOrElse(0)
    val maxLine  = lines.flatMap(_.values).maxOption.getOrElse(0)
    val yMaxRaw  = math.max(maxStack, maxLine)
    val yStep    = niceStep(yMaxRaw, 6)
    val yMax     = math.max(yStep, ((yMaxRaw + yStep - 1) / yStep) * yStep)
    ChartScale(yMax, yStep)
  }

  private def slotWidth(layout: ChartLayout, count: Int): Double =
    if count <= 0 then layout.plotWidth else layout.plotWidth / count

  private def barWidth(layout: ChartLayout, count: Int): Double =
    slotWidth(layout, count) * 0.78

  private def xForBar(layout: ChartLayout, pointCount: Int, index: Int): Double =
    layout.plotX + slotWidth(
      layout,
      pointCount
    ) * index + (slotWidth(layout, pointCount) - barWidth(layout, pointCount)) / 2.0

  private def yForValue(layout: ChartLayout, scale: ChartScale, value: Double): Double =
    layout.plotY + layout.plotHeight - (value / scale.yMax) * layout.plotHeight

  private def renderBackground(): String = "<rect width='100%' height='100%' fill='white'/>"

  private def renderGridAndAxes(layout: ChartLayout, scale: ChartScale): String = {
    val gridLines =
      (0 to scale.yMax by scale.yStep).map { tick =>
        val y = yForValue(layout, scale, tick.toDouble)
        s"<line x1='${fmt(layout.plotX)}' y1='${fmt(y)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(y)}' stroke='#e9ecef' stroke-width='1' />" +
        s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='${FontConfig.tick}' fill='#495057'>${formatSi(tick.toDouble)}</text>"
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
      pointCount: Int,
      index: Int,
      point: StackedBarPoint,
      activeKeys: Vector[String],
      colors: Map[String, String]
  ): String = {
    val x     = xForBar(layout, pointCount, index)
    val width = barWidth(layout, pointCount)
    activeKeys.foldLeft((0.0, Vector.empty[String])) { case ((bottomValue, acc), key) =>
      val count = point.values.getOrElse(key, 0)
      if count <= 0 then (bottomValue, acc)
      else
          val yTop    = yForValue(layout, scale, bottomValue + count)
          val yBottom = yForValue(layout, scale, bottomValue)
          (
            bottomValue + count,
            acc :+ renderBarSegment(x, yTop, width, yBottom - yTop, colors.getOrElse(key, "#adb5bd"))
          )
    }._2.mkString("\n")
  }

  private def renderBarLabels(layout: ChartLayout, points: Vector[StackedBarPoint]): String =
    points.zipWithIndex.map { case (point, index) =>
      val cx = xForBar(layout, points.size, index) + barWidth(layout, points.size) / 2.0
      val cy = layout.plotY + layout.plotHeight + 10.0
      s"<text x='${fmt(cx)}' y='${fmt(cy)}' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' font-size='10' text-anchor='start' fill='#495057'>${svgEscape(point.xLabel)}</text>"
    }.mkString("\n")

  private def renderLineSeries(
      layout: ChartLayout,
      scale: ChartScale,
      points: Vector[StackedBarPoint],
      line: LineSeries
  ): String = {
    val lineValues = line.values.padTo(points.size, 0).take(points.size)
    val polyPoints = lineValues.zipWithIndex.map { case (value, index) =>
      val cx = xForBar(layout, points.size, index) + barWidth(layout, points.size) / 2.0
      val cy = yForValue(layout, scale, value.toDouble)
      s"${fmt(cx)},${fmt(cy)}"
    }.mkString(" ")

    val markers = lineValues.zipWithIndex.map { case (value, index) =>
      val cx = xForBar(layout, points.size, index) + barWidth(layout, points.size) / 2.0
      val cy = yForValue(layout, scale, value.toDouble)
      s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='${line.markerFill}' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
    }.mkString("\n")

    s"<polyline fill='none' stroke='${line.stroke}' stroke-width='2' stroke-dasharray='${line.dashArray}' points='$polyPoints' />\n$markers"
  }

  def renderLegend(
      layout: ChartLayout,
      activeKeys: Vector[String],
      colors: Map[String, String],
      activeLines: Vector[LineSeries],
      lxOverride: Option[Double] = None,
      lyOverride: Option[Double] = None
  ): String = {
    val lx         = lxOverride.getOrElse(layout.legendX)
    val ly         = lyOverride.getOrElse(layout.legendY)
    val entries    = activeKeys.map(Left(_)) ++ activeLines.map(Right(_))
    val rowSpacing = 24
    val padY       = 8
    val boxHeight  = 2 * padY + entries.size * rowSpacing
    val box        =
      s"<rect x='${fmt(lx)}' y='${fmt(ly)}' width='${fmt(layout.legendWidth)}' height='${fmt(boxHeight)}' rx='4' ry='4' fill='white' fill-opacity='0.92' stroke='#ced4da' stroke-width='1' />"

    def textEl(label: String, cy: Double): String =
      s"<text x='${fmt(lx + 38)}' y='${fmt(cy + 8)}' font-size='${FontConfig.legend}' fill='#212529'>${svgEscape(label)}</text>"

    val legendItems = entries.reverse.zipWithIndex.map { case (entry, idx) =>
      val cy = ly + padY + rowSpacing / 2.0 + idx * rowSpacing
      entry match
        case Left(key)   =>
          val sx = lx + 10
          s"<rect x='${fmt(sx)}' y='${fmt(cy - 6)}' width='18' height='12' fill='${colors.getOrElse(key, "#adb5bd")}' stroke='#ffffff' stroke-width='0.6' />" + textEl(key, cy)
        case Right(line) =>
          val sx = lx + 10
          s"<line x1='${fmt(sx)}' y1='${fmt(cy)}' x2='${fmt(sx + 20)}' y2='${fmt(cy)}' stroke='${line.stroke}' stroke-width='2' stroke-dasharray='${line.dashArray}' />" +
          s"<rect x='${fmt(sx + 7)}' y='${fmt(cy - 3)}' width='6' height='6' fill='${line.markerFill}' transform='rotate(45 ${fmt(sx + 10)} ${fmt(cy)})' />" + textEl(line.label, cy)
    }.mkString("\n")

    box + "\n" + legendItems
  }

  private def renderTitles(layout: ChartLayout, title: String, yAxisLabel: String, xAxisLabel: String): String =
    val xLabel = if xAxisLabel.isEmpty then "" else
      s"\n<text x='${fmt(layout.plotX + layout.plotWidth / 2.0)}' y='${fmt(layout.height - 40.0)}' text-anchor='middle' font-size='${FontConfig.axisLabel}' fill='#212529'>${svgEscape(xAxisLabel)}</text>"
    s"<text x='${fmt(layout.width / 2.0)}' y='18' text-anchor='middle' font-size='${FontConfig.title}' font-weight='700' fill='#111827'>${svgEscape(title)}</text>" +
    s"\n<text x='${fmt(yAxisLabelPos)}' y='${fmt(layout.plotY + layout.plotHeight / 2.0)}' transform='rotate(-90 ${fmt(yAxisLabelPos)} ${fmt(layout.plotY + layout.plotHeight / 2.0)})' text-anchor='middle' font-size='${FontConfig.axisLabel}' fill='#212529'>${svgEscape(yAxisLabel)}</text>" +
    xLabel

  private def renderFooter(
      layout: ChartLayout,
      points: Vector[StackedBarPoint],
      activeKeys: Vector[String],
      topLabel: String,
      activeLines: Vector[LineSeries],
      totalLabel: String
  ): String = {
    val totalValue  = points.map(_.values.values.sum).sum
    val totalsByKey = activeKeys.map(key => key -> points.map(_.values.getOrElse(key, 0)).sum).filter(_._2 > 0)
    val dominant    =
      totalsByKey.sortBy(-_._2).headOption.map { case (key, c) => s"$topLabel: $key ($c)" }.getOrElse(s"$topLabel: -")
    val lineSummaries = activeLines.map(line => s"${line.label}: ${line.values.sum}")
    val footer        =
      (lineSummaries :+ s"$totalLabel: $totalValue" :+ s"Periods: ${points.size}" :+ dominant).mkString("  |  ")
    ""
  }

  private def computeBoxPlotYMax(stats: Vector[BoxPlotStats], suppressOutliers: Boolean): Double = {
    val maxValue =
      if suppressOutliers then stats.iterator.map(_.whiskerHigh).maxOption.getOrElse(0.0)
      else stats.iterator.flatMap(s => Iterator(s.max, s.whiskerHigh, s.mean) ++ s.outliers).maxOption.getOrElse(0.0)
    val padded = if maxValue <= 0.0 then 1.0 else maxValue * 1.02
    val step   = niceStep(math.ceil(padded).toInt, 6).toDouble
    math.max(step, math.ceil(padded / step) * step)
  }

  private def scaleValue(value: Double, scale: BoxPlotScale): Double =
    scale match
        case BoxPlotScale.Linear => value
        case BoxPlotScale.Log10  => math.log10(math.max(1.0, value + 1.0))

  private def scaledYMax(stats: Vector[BoxPlotStats], scale: BoxPlotScale, suppressOutliers: Boolean): Double =
    scaleValue(computeBoxPlotYMax(stats, suppressOutliers), scale)

  private def yForContinuousValue(layout: ChartLayout, scaledMax: Double, value: Double, scale: BoxPlotScale): Double =
      val scaled = scaleValue(value, scale)
      layout.plotY + layout.plotHeight - (scaled / scaledMax) * layout.plotHeight

  private def renderContinuousGridAndAxes(layout: ChartLayout, yMax: Double, scale: BoxPlotScale): String = {
    val ticks =
      scale match
          case BoxPlotScale.Linear =>
            val step = niceStep(math.ceil(yMax).toInt, 6)
            (0 to math.ceil(yMax).toInt by step).map(_.toDouble).toVector
          case BoxPlotScale.Log10 =>
            val exponents = 0 to math.ceil(math.log10(math.max(1.0, yMax + 1.0))).toInt
            exponents.map(e => math.pow(10, e) - 1.0).toVector

    val scaledMax = scaleValue(yMax, scale)
    val gridLines = ticks.map { tick =>
      val y     = yForContinuousValue(layout, scaledMax, tick, scale)
      val label =
        scale match
            case BoxPlotScale.Linear => formatSi(tick)
            case BoxPlotScale.Log10  => formatSi(tick)
      s"<line x1='${fmt(layout.plotX)}' y1='${fmt(y)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(y)}' stroke='#e9ecef' stroke-width='1' />" +
      s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='${FontConfig.boxPlotTick}' fill='#495057'>$label</text>"
    }.mkString("\n")

    val axes =
      s"<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY + layout.plotHeight)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />" +
      s"<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY)}' x2='${fmt(layout.plotX)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />"

    gridLines + "\n" + axes
  }

  private def renderBoxPlotLabels(layout: ChartLayout, stats: Vector[BoxPlotStats]): String =
    stats.zipWithIndex.map { case (stat, index) =>
      val x = xForBar(layout, stats.size, index) + barWidth(layout, stats.size) / 2.0
      val y = layout.plotY + layout.plotHeight + 28.0
      s"<text x='${fmt(x)}' y='${fmt(y)}' transform='rotate(45 ${fmt(x)} ${fmt(y)})' text-anchor='start' font-size='${FontConfig.boxPlotLabel}' fill='#495057'>${svgEscape(stat.label)}</text>"
    }.mkString("\n")

  private def renderBoxPlot(
      layout: ChartLayout,
      scaledMax: Double,
      pointCount: Int,
      index: Int,
      stat: BoxPlotStats,
      boxFill: String,
      stroke: String,
      meanColor: String,
      outlierColor: String,
      scale: BoxPlotScale,
      suppressOutliers: Boolean
  ): String = {
    val x            = xForBar(layout, pointCount, index)
    val width        = barWidth(layout, pointCount)
    val centerX      = x + width / 2.0
    val boxTop       = yForContinuousValue(layout, scaledMax, stat.q3, scale)
    val boxBottom    = yForContinuousValue(layout, scaledMax, stat.q1, scale)
    val medianY      = yForContinuousValue(layout, scaledMax, stat.median, scale)
    val lowWhiskerY  = yForContinuousValue(layout, scaledMax, stat.whiskerLow, scale)
    val highWhiskerY = yForContinuousValue(layout, scaledMax, stat.whiskerHigh, scale)
    val meanY        = yForContinuousValue(layout, scaledMax, stat.mean, scale)
    val capWidth     = width * 0.55
    val meanSize     = 4.0

    val outliers =
      if suppressOutliers then ""
      else
          stat.outliers.map { value =>
            val cy = yForContinuousValue(layout, scaledMax, value, scale)
            s"<circle cx='${fmt(centerX)}' cy='${fmt(cy)}' r='3' fill='$outlierColor' fill-opacity='0.85' />"
          }.mkString("\n")

    s"""<line x1='${fmt(centerX)}' y1='${fmt(highWhiskerY)}' x2='${fmt(centerX)}' y2='${fmt(
        boxTop
      )}' stroke='$stroke' stroke-width='1.5' />
<line x1='${fmt(centerX)}' y1='${fmt(boxBottom)}' x2='${fmt(centerX)}' y2='${fmt(
        lowWhiskerY
      )}' stroke='$stroke' stroke-width='1.5' />
<line x1='${fmt(centerX - capWidth / 2.0)}' y1='${fmt(highWhiskerY)}' x2='${fmt(centerX + capWidth / 2.0)}' y2='${fmt(
        highWhiskerY
      )}' stroke='$stroke' stroke-width='1.5' />
<line x1='${fmt(centerX - capWidth / 2.0)}' y1='${fmt(lowWhiskerY)}' x2='${fmt(centerX + capWidth / 2.0)}' y2='${fmt(
        lowWhiskerY
      )}' stroke='$stroke' stroke-width='1.5' />
<rect x='${fmt(x)}' y='${fmt(boxTop)}' width='${fmt(width)}' height='${fmt(
        boxBottom - boxTop
      )}' fill='$boxFill' fill-opacity='0.75' stroke='$stroke' stroke-width='1.5' rx='3' ry='3' />
<line x1='${fmt(x)}' y1='${fmt(medianY)}' x2='${fmt(x + width)}' y2='${fmt(
        medianY
      )}' stroke='$stroke' stroke-width='2' />
<rect x='${fmt(centerX - meanSize)}' y='${fmt(meanY - meanSize)}' width='${fmt(meanSize * 2)}' height='${fmt(
        meanSize * 2
      )}' fill='$meanColor' transform='rotate(45 ${fmt(centerX)} ${fmt(meanY)})' />
$outliers"""
  }

  private def renderBoxPlotLegend(layout: ChartLayout): String = {
    val boxHeight = 18 + 4 * 20
    val box       =
      s"<rect x='${fmt(layout.legendX)}' y='${fmt(layout.legendY)}' width='${fmt(layout.legendWidth)}' height='${fmt(boxHeight)}' rx='4' ry='4' fill='white' fill-opacity='0.92' stroke='#ced4da' stroke-width='1' />"

    val legendColor = "#111827"
    val entries     = Vector(
      s"<rect x='${fmt(layout.legendX + 10)}' y='${fmt(layout.legendY + 8)}' width='18' height='12' fill='white' stroke='$legendColor' stroke-width='1.2' /><text x='${fmt(layout.legendX + 38)}' y='${fmt(layout.legendY + 18)}' font-size='${FontConfig.boxPlotTick}' fill='#212529'>IQR (Q1–Q3)</text>",
      s"<line x1='${fmt(layout.legendX + 10)}' y1='${fmt(layout.legendY + 34)}' x2='${fmt(layout.legendX + 30)}' y2='${fmt(layout.legendY + 34)}' stroke='$legendColor' stroke-width='2' /><text x='${fmt(layout.legendX + 38)}' y='${fmt(layout.legendY + 38)}' font-size='${FontConfig.boxPlotTick}' fill='#212529'>Median</text>",
      s"<rect x='${fmt(layout.legendX + 17)}' y='${fmt(layout.legendY + 47)}' width='6' height='6' fill='$legendColor' transform='rotate(45 ${fmt(layout.legendX + 20)} ${fmt(layout.legendY + 50)})' /><text x='${fmt(layout.legendX + 38)}' y='${fmt(layout.legendY + 54)}' font-size='${FontConfig.boxPlotTick}' fill='#212529'>Mean</text>",
      s"<circle cx='${fmt(layout.legendX + 20)}' cy='${fmt(layout.legendY + 70)}' r='3' fill='$legendColor' fill-opacity='0.85' /><text x='${fmt(layout.legendX + 38)}' y='${fmt(layout.legendY + 74)}' font-size='${FontConfig.boxPlotTick}' fill='#212529'>Outliers</text>"
    ).mkString("\n")

    box + "\n" + entries
  }

  def renderBoxPlotSvg(
      title: String,
      yLabel: String,
      stats: Vector[BoxPlotStats],
      boxFill: String = "#93c5fd",
      stroke: String = "#1d4ed8",
      fillByLabel: Map[String, String] = Map.empty,
      strokeByLabel: Map[String, String] = Map.empty,
      scale: BoxPlotScale = BoxPlotScale.Linear,
      suppressOutliers: Boolean = false
  ): String = {
    val layout = ChartLayout(
      width = math.max(1200, stats.size * 72 + 220),
      height = 600,
      left = autoLeftMargin,
      right = 24,
      top = 44,
      bottom = 132,
      legendWidth = 170
    )
    val yMax      = computeBoxPlotYMax(stats, suppressOutliers)
    val scaledMax = scaledYMax(stats, scale, suppressOutliers)
    val plots     = stats.zipWithIndex.map { case (stat, index) =>
      val labelFill   = fillByLabel.getOrElse(stat.label, boxFill)
      val labelStroke = strokeByLabel.getOrElse(stat.label, stroke)
      renderBoxPlot(
        layout,
        scaledMax,
        stats.size,
        index,
        stat,
        labelFill,
        labelStroke,
        labelStroke,
        labelStroke,
        scale,
        suppressOutliers
      )
    }.mkString("\n")

    val footer =
      s"<text x='${fmt(layout.width / 2.0)}' y='${fmt(layout.height - 12.0)}' text-anchor='middle' font-size='${FontConfig.boxPlotTick}' fill='#212529'>Groups: ${stats.size}  |  Samples: ${stats.map(_.count).sum}</text>"

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
${renderContinuousGridAndAxes(layout, yMax, scale)}
<text x='${fmt(
        layout.width / 2.0
      )}' y='18' text-anchor='middle' font-size='${FontConfig.boxPlotTitle}' font-weight='700' fill='#111827'>${svgEscape(title)}</text>
<text x='${fmt(yAxisLabelPos)}' y='${fmt(layout.plotY + layout.plotHeight / 2.0)}' transform='rotate(-90 ${fmt(yAxisLabelPos)} ${fmt(
        layout.plotY + layout.plotHeight / 2.0
      )})' text-anchor='middle' font-size='${FontConfig.boxPlotAxis}' fill='#212529'>${svgEscape(yLabel)}</text>
<text x='${fmt(layout.plotX + layout.plotWidth / 2.0)}' y='${fmt(
        layout.height - 40.0
      )}' text-anchor='middle' font-size='${FontConfig.boxPlotAxis}' fill='#212529'>Group</text>
$plots
${renderBoxPlotLabels(layout, stats)}
${renderBoxPlotLegend(layout)}
$footer
</svg>
"""
  }

  def renderStackedTimeSeriesSvg(
      title: String,
      points: Vector[StackedBarPoint],
      stackOrder: Vector[String],
      colors: Map[String, String],
      topLabel: String,
      lineSeries: Vector[LineSeries],
      yAxisLabel: String = "Commits",
      xAxisLabel: String = "",
      totalLabel: String = "Embedded records",
      showLegend: Boolean = true
  ): String = {
    val activeStacks = activeStackKeys(points, stackOrder)
    val activeLines  = activeLineSeries(lineSeries)
    val legendW      = 220.0
    val layout       = ChartLayout(
      width = math.max(900, points.size * 20 + legendW.toInt + 200),
      height = 560,
      left = autoLeftMargin,
      right = legendW + 40,
      top = 44,
      bottom = 140,
      legendWidth = legendW
    )
    val scale = computeScale(points, activeLines)

    val bars = points.zipWithIndex.map { case (point, index) =>
      renderStackedBar(layout, scale, points.size, index, point, activeStacks, colors)
    }.mkString("\n")

    val lines = activeLines.map(renderLineSeries(layout, scale, points, _)).mkString("\n")

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
${renderGridAndAxes(layout, scale)}
${renderTitles(layout, title, yAxisLabel, xAxisLabel)}
$bars
$lines
${renderBarLabels(layout, points)}
${if showLegend then renderLegend(layout, activeStacks, colors, activeLines) else ""}
${renderFooter(layout, points, activeStacks, topLabel, activeLines, totalLabel)}
</svg>
"""
  }

  def renderLegendOnlySvg(
      stackOrder: Vector[String],
      colors: Map[String, String],
      lineSeries: Vector[LineSeries]
  ): String = {
    val activeStacks = activeStackKeys(Vector.empty, stackOrder)
    val activeLines  = activeLineSeries(lineSeries)
    val layout       = ChartLayout(
      width = 400,
      height = 300,
      left = 10,
      right = 10,
      top = 10,
      bottom = 10,
      legendWidth = 380
    )
    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
${renderLegend(layout, activeStacks, colors, activeLines, lxOverride = Some(10.0), lyOverride = Some(10.0))}
</svg>
"""
  }

  def renderLegendSvg(
      stackOrder: Vector[String],
      colors: Map[String, String]
  ): String = {
    val allKeys = stackOrder.filterNot(_ == "no signal")
    val dummyPoint = StackedBarPoint("", allKeys.map(k => k -> 1).toMap)
    val layout = ChartLayout(width = 220, height = 360, left = 10, right = 10, top = 10, bottom = 10, legendWidth = 200)
    s"""<svg xmlns='http://www.w3.org/2000/svg' width='220' height='360' viewBox='0 0 220 360'>
<rect width='100%' height='100%' fill='white'/>
${renderLegend(layout, activeStackKeys(Vector(dummyPoint), stackOrder), colors, Vector.empty, lxOverride = Some(10.0), lyOverride = Some(10.0))}
</svg>"""
  }

  private def renderPctBar(
      layout: ChartLayout,
      pointCount: Int,
      index: Int,
      pctValues: Map[String, Double],
      activeKeys: Vector[String],
      colors: Map[String, String]
  ): String = {
    val x     = xForBar(layout, pointCount, index)
    val width = barWidth(layout, pointCount)
    def yForPct(v: Double): Double =
      layout.plotY + layout.plotHeight - (v / 100.0) * layout.plotHeight
    activeKeys.foldLeft((0.0, Vector.empty[String])) { case ((bottom, acc), key) =>
      val pct = pctValues.getOrElse(key, 0.0)
      if pct < 0.01 then (bottom, acc)
      else
        val yTop    = yForPct(bottom + pct)
        val yBottom = yForPct(bottom)
        (bottom + pct, acc :+ renderBarSegment(x, yTop, width, yBottom - yTop, colors.getOrElse(key, "#adb5bd")))
    }._2.mkString("\n")
  }

  def renderPercentageStackedWithCountLineSvg(
      title: String,
      points: Vector[StackedBarPoint],
      stackOrder: Vector[String],
      colors: Map[String, String],
      countPerWeek: Vector[Int],
      yRightLabel: String = "Commits",
      xAxisLabel: String = "Week",
      showLegend: Boolean = true
  ): String = {
    val activeStacks = activeStackKeys(points, stackOrder)
    val legendW    = 220.0
    val rightAxisW = 80.0
    val layout     = ChartLayout(
      width = math.max(900, points.size * 20 + legendW.toInt + 200),
      height = 560,
      left = autoLeftMargin,
      right = rightAxisW + legendW + 40,
      top = 44,
      bottom = 140,
      legendWidth = legendW
    )

    val pctByPoint: Vector[Map[String, Double]] = points.map { pt =>
      val total = pt.values.values.sum.toDouble
      if total == 0 then Map.empty
      else pt.values.view.mapValues(_.toDouble / total * 100.0).toMap
    }

    // Left y-axis: fixed 0-100%
    val leftAxis: String =
      (0 to 100 by 10).map { tick =>
        val y = layout.plotY + layout.plotHeight - (tick / 100.0) * layout.plotHeight
        s"<line x1='${fmt(layout.plotX)}' y1='${fmt(y)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(y)}' stroke='#e9ecef' stroke-width='1' />" +
        s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='${FontConfig.tick}' fill='#495057'>$tick%</text>"
      }.mkString("\n") +
      s"\n<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY + layout.plotHeight)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />" +
      s"\n<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY)}' x2='${fmt(layout.plotX)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />"

    // Right y-axis: commit count scale
    val countMax      = math.max(1, countPerWeek.maxOption.getOrElse(1))
    val countStep     = niceStep(countMax, 6)
    val countScaleMax = math.max(countStep, ((countMax + countStep - 1) / countStep) * countStep)
    val rightX        = layout.plotX + layout.plotWidth

    def yForCount(v: Double): Double =
      layout.plotY + layout.plotHeight - (v / countScaleMax) * layout.plotHeight

    val rightAxis: String =
      (0 to countScaleMax by countStep).map { tick =>
        val y = yForCount(tick.toDouble)
        s"<text x='${fmt(rightX + 8)}' y='${fmt(y + 4)}' text-anchor='start' font-size='${FontConfig.tick}' fill='#343a40'>${formatSi(tick.toDouble)}</text>"
      }.mkString("\n") +
      s"\n<line x1='${fmt(rightX)}' y1='${fmt(layout.plotY)}' x2='${fmt(rightX)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />"

    val rightAxisLabel: String = {
      val rx = layout.plotX + layout.plotWidth + rightAxisW - FontConfig.axisLabel * 0.5
      val ry = layout.plotY + layout.plotHeight / 2.0
      s"<text x='${fmt(rx)}' y='${fmt(ry)}' transform='rotate(90 ${fmt(rx)} ${fmt(ry)})' text-anchor='middle' font-size='${FontConfig.axisLabel}' fill='#343a40'>${svgEscape(yRightLabel)}</text>"
    }

    val bars: String = pctByPoint.zipWithIndex.map { case (pctValues, index) =>
      renderPctBar(layout, points.size, index, pctValues, activeStacks, colors)
    }.mkString("\n")

    val countLine: String = {
      val counts = countPerWeek.padTo(points.size, 0).take(points.size)
      val poly   = counts.zipWithIndex.map { case (c, i) =>
        val cx = xForBar(layout, points.size, i) + barWidth(layout, points.size) / 2.0
        s"${fmt(cx)},${fmt(yForCount(c.toDouble))}"
      }.mkString(" ")
      val markers = counts.zipWithIndex.map { case (c, i) =>
        val cx = xForBar(layout, points.size, i) + barWidth(layout, points.size) / 2.0
        val cy = yForCount(c.toDouble)
        s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='#343a40' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
      }.mkString("\n")
      s"<polyline fill='none' stroke='#343a40' stroke-width='2' stroke-dasharray='6 4' points='$poly' />\n$markers"
    }

    val legendSvg = if showLegend then
      renderLegend(layout, activeStacks, colors, Vector(LineSeries(yRightLabel, "#343a40", "6 4", "#343a40", countPerWeek)),
        lxOverride = Some(layout.plotX + layout.plotWidth + rightAxisW + 16))
    else ""

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
$leftAxis
$rightAxis
${renderTitles(layout, title, "Agent share (%)", xAxisLabel)}
$rightAxisLabel
$bars
$countLine
${renderBarLabels(layout, points)}
$legendSvg
</svg>
"""
  }

  def writeSvg(path: java.nio.file.Path, svg: String): Unit = {
    Files.writeString(path, svg, StandardCharsets.UTF_8)
    ()
  }

  def writeSvgAndConvertToPdf(path: java.nio.file.Path, svg: String): Unit = {
    Files.writeString(path, svg, StandardCharsets.UTF_8)
    ()
  }
}
