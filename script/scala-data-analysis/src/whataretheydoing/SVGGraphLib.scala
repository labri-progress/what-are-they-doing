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
    val subtitle      = 14
    val axisLabel     = 24
    val tick          = 24
    val barLabel      = 16
    val legend        = 22
    val legendEntry   = 16
    val footer        = 18
    val boxPlotTick   = 12
    val boxPlotLabel  = 11
    val boxPlotTitle  = 18
    val boxPlotAxis   = 14

  private def yAxisLabelPos: Double = FontConfig.axisLabel * 1.5

  private def autoLeftMargin: Double = yAxisLabelPos + FontConfig.axisLabel * 1.5 + FontConfig.tick * 2.0

  private def compactLeftMargin(fontSize: Int, labelFontSize: Int = FontConfig.axisLabel): Double =
    labelFontSize * 1.1 + fontSize * 2.0 + fontSize * 1.3

  /**
   * Approximate advance width of a single character, as a fraction of the font size,
   * for the default sans-serif face. A flat per-character estimate misjudges labels
   * containing wide letters such as `m` against labels made of narrow ones such as
   * `i` and `l`, which visibly uneven the gaps in a legend.
   */
  private def advanceOf(char: Char): Double = char match
    case 'i' | 'j' | 'l' | '!' | '|' | '\'' => 0.30
    case 'f' | 't' | 'r' | '(' | ')' | '[' | ']' | '-' | '/' | '\\' => 0.38
    case 'I' => 0.32
    case 'm' => 0.88
    case 'w' => 0.76
    case 'M' | 'W' => 0.86
    case ' ' => 0.28
    case '.' | ',' | ':' | ';' => 0.30
    case c if c.isUpper => 0.68
    case c if c.isDigit => 0.57
    case _ => 0.56

  private def svgEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def fmt(value: Double): String = String.format(Locale.US, "%.2f", Double.box(value))

  /**
   * Formats an axis tick. Values of a thousand and above are written in thousands
   * with one decimal (e.g. `1.5k`) so that tick labels keep a constant width, and
   * whole thousands drop the decimal (e.g. `2k`).
   */
  private def formatSi(value: Double): String = {
    val rounded = math.rint(value).toLong
    if value != rounded.toDouble then fmt(value)
    else if rounded != 0 && rounded % 1000000000L == 0 then s"${rounded / 1000000000L}G"
    else if rounded != 0 && rounded % 1000000L == 0 then s"${rounded / 1000000L}M"
    else if rounded >= 1000L && rounded < 1000000L then
      if rounded % 1000L == 0 then s"${rounded / 1000L}k"
      else
        val thousands = f"${rounded / 1000.0}%.1f"
        val trimmed   = if thousands.endsWith(".0") then thousands.dropRight(2) else thousands
        s"${trimmed}k"
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

  private def renderGridAndAxes(layout: ChartLayout, scale: ChartScale, tickFontSize: Int = FontConfig.tick): String = {
    val gridLines =
      (0 to scale.yMax by scale.yStep).map { tick =>
        val y = yForValue(layout, scale, tick.toDouble)
        s"<line x1='${fmt(layout.plotX)}' y1='${fmt(y)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(y)}' stroke='#e9ecef' stroke-width='1' />" +
        s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='$tickFontSize' fill='#495057'>${formatSi(tick.toDouble)}</text>"
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

  /**
   * Renders the week axis below the plot: one tick per week, plus labels that are
   * connected to their tick by a short leader line. Short labels (bare week numbers)
   * are staggered over two rows so every week can carry a label; longer labels fall
   * back to rotated, thinned text.
   *
   * Returns the SVG together with the lowest y coordinate the axis content reaches,
   * so the x-axis title can be placed directly beneath it.
   */
  private def renderWeekAxis(
      layout: ChartLayout,
      points: Vector[StackedBarPoint],
      fontSize: Int
  ): (String, Double) = {
    if points.isEmpty then ("", layout.plotY + layout.plotHeight)
    else
      val slot     = slotWidth(layout, points.size)
      val axisY    = layout.plotY + layout.plotHeight
      val tickColor = "#495057"

      def centerX(index: Int) =
        xForBar(layout, points.size, index) + barWidth(layout, points.size) / 2.0

      // Advance-width estimate for the default sans-serif face.
      def labelWidth(label: String) = label.length * fontSize * 0.55

      val shortLabels = points.forall(_.xLabel.length <= 6)

      if !shortLabels then
        // Rotated fallback for long labels (e.g. ISO dates in other charts).
        val minSpacing = fontSize * 1.05
        val step       = math.max(1, math.ceil(minSpacing / math.max(1.0, slot)).toInt)
        val ticks = (0 until points.size).map { index =>
          val cx = centerX(index)
          s"<line x1='${fmt(cx)}' y1='${fmt(axisY)}' x2='${fmt(cx)}' y2='${fmt(axisY + 8)}' stroke='$tickColor' stroke-width='1.2' />"
        }.mkString("\n")
        val labels = points.zipWithIndex.flatMap { case (point, index) =>
          if index % step != 0 then None
          else
            val cx = centerX(index)
            val cy = axisY + 8.0 + fontSize * 0.9
            Some(
              s"<text x='${fmt(cx)}' y='${fmt(cy)}' transform='rotate(60 ${fmt(cx)} ${fmt(cy)})' font-size='$fontSize' text-anchor='start' fill='$tickColor'>${svgEscape(point.xLabel)}</text>"
            )
        }.mkString("\n")
        // Rotated text descends below its anchor point, so allow for a full line.
        (ticks + "\n" + labels, axisY + 8.0 + fontSize * 1.8)
      else
        val rowGap   = fontSize * 0.45
        val rowPitch = fontSize * 1.1
        // Baseline of each stagger row, measured from the axis. Rows 0/1 carry the
        // week numbers; row 2 carries the year markers where the year changes.
        def rowBaseline(row: Int) = axisY + rowGap + fontSize * 0.85 + rowPitch * row
        def rowTop(row: Int)      = rowBaseline(row) - fontSize * 0.72

        // Year markers are split out of the label (format: "36 '25") and drawn on
        // their own row so they never crowd out neighbouring week numbers.
        val parsed = points.map { point =>
          val parts = point.xLabel.split(" ")
          (parts.headOption.getOrElse(""), parts.lift(1))
        }

        def textWidthOf(label: String) = labelWidth(label) + fontSize * 0.3

        // Thin the labels so that neighbours within the same stagger row never
        // collide, then alternate rows. With two rows this yields roughly every
        // other week at the font sizes used for the weekly plots.
        val widestLabel = parsed.map(pair => textWidthOf(pair._1)).maxOption.getOrElse(0.0)
        val needed      = widestLabel + fontSize * 0.35
        // Labels sharing a row are `2 * step` slots apart.
        val step        = math.max(1, math.ceil(needed / math.max(1.0, slot * 2.0)).toInt)

        val drawnIndices = (0 until points.size by step).toVector

        val yearRowBaseline = rowBaseline(2)

        // Also label the weeks where the year changes, so the year marker always
        // has a week number directly above it. Rows are assigned by position in
        // the sorted label list, which keeps near neighbours on opposite rows.
        val yearChangeIndices = parsed.indices.filter(index => parsed(index)._2.isDefined)
        val labelIndices      = (drawnIndices ++ yearChangeIndices).distinct.sorted
        val rowOfLabel        = labelIndices.zipWithIndex.map((index, position) => index -> (position % 2)).toMap

        // Every week gets a tick so the axis reads continuously; unlabelled weeks
        // keep a short stub, labelled weeks reach up to their label row.
        val stubLength = fontSize * 0.4
        val ticks = (0 until points.size).map { index =>
          val cx  = centerX(index)
          val end = rowOfLabel.get(index) match
            case Some(row) => rowTop(row)
            case None      => axisY + stubLength
          val strokeWidth = if rowOfLabel.contains(index) then 1.6 else 1.2
          s"<line x1='${fmt(cx)}' y1='${fmt(axisY)}' x2='${fmt(cx)}' y2='${fmt(end)}' stroke='$tickColor' stroke-width='$strokeWidth' />"
        }

        val labels = labelIndices.zipWithIndex.map { case (index, position) =>
          val cx  = centerX(index)
          val row = position % 2
          s"<text x='${fmt(cx)}' y='${fmt(rowBaseline(row))}' text-anchor='middle' font-size='$fontSize' fill='$tickColor'>${svgEscape(parsed(index)._1)}</text>"
        }

        // Year markers sit on their own row beneath the week numbers.
        val yearLabels = parsed.zipWithIndex.flatMap { case ((_, year), index) =>
          year.map { y =>
            val cx = centerX(index)
            s"<text x='${fmt(cx)}' y='${fmt(yearRowBaseline)}' text-anchor='middle' font-size='${math.round(fontSize * 0.8).toInt}' font-style='italic' fill='$tickColor'>${svgEscape(y)}</text>"
          }
        }

        (ticks.mkString("\n") + "\n" + (labels ++ yearLabels).mkString("\n"), yearRowBaseline)
  }

  private def renderBarLabels(
      layout: ChartLayout,
      points: Vector[StackedBarPoint],
      fontSize: Int = FontConfig.barLabel
  ): String = {
    // Skip labels that would collide when there are many slots. Week numbers are
    // short, so the threshold is kept tight to prefer every-other-week labels.
    val slot       = slotWidth(layout, points.size)
    val minSpacing = fontSize * 1.05
    val step       = math.max(1, math.ceil(minSpacing / math.max(1.0, slot)).toInt)
    points.zipWithIndex.flatMap { case (point, index) =>
      if index % step != 0 then None
      else
        val cx = xForBar(layout, points.size, index) + barWidth(layout, points.size) / 2.0
        // Leave a clear gap below the axis ticks before the rotated label starts.
        val cy = layout.plotY + layout.plotHeight + 8.0 + fontSize * 0.9
        Some(
          s"<text x='${fmt(cx)}' y='${fmt(cy)}' transform='rotate(60 ${fmt(cx)} ${fmt(cy)})' font-size='$fontSize' text-anchor='start' fill='#495057'>${svgEscape(point.xLabel)}</text>"
        )
    }.mkString("\n")
  }

  private val gapHatchDefs: String =
    """<defs>
<pattern id='gapHatch' width='7' height='7' patternUnits='userSpaceOnUse' patternTransform='rotate(45)'>
<rect width='7' height='7' fill='#f8f9fa'/>
<line x1='0' y1='0' x2='0' y2='7' stroke='#adb5bd' stroke-width='2'/>
</pattern>
</defs>"""

  /**
   * Finer version of `gapHatch`, used for the small legend swatch. The plot-scale
   * pattern would only fit two or three stripes into a swatch this size and read as
   * a coarse block rather than as hatching.
   */
  private val legendHatchDefs: String =
    """<defs>
<pattern id='legendHatch' width='5' height='5' patternUnits='userSpaceOnUse' patternTransform='rotate(45)'>
<rect width='5' height='5' fill='#f8f9fa'/>
<line x1='0' y1='0' x2='0' y2='5' stroke='#adb5bd' stroke-width='1.4'/>
</pattern>
</defs>"""

  /** Hatches the first `gapCount` slots of the plot to mark "no data yet". */
  private def renderGapHatch(layout: ChartLayout, pointCount: Int, gapCount: Int): String =
    if gapCount <= 0 || pointCount <= 0 then ""
    else
      val usable    = math.min(gapCount, pointCount)
      val rightEdge = layout.plotX + slotWidth(layout, pointCount) * usable
      val regionW   = math.max(0.0, rightEdge - layout.plotX)
      s"<rect x='${fmt(layout.plotX)}' y='${fmt(layout.plotY)}' width='${fmt(regionW)}' height='${fmt(layout.plotHeight)}' fill='url(#gapHatch)' stroke='none' />"

  private def renderLineSeries(
      layout: ChartLayout,
      scale: ChartScale,
      points: Vector[StackedBarPoint],
      line: LineSeries,
      leadingGapPoints: Int
  ): String = {
    val lineValues = line.values.padTo(points.size, 0).take(points.size)
    val firstValid = math.max(0, math.min(leadingGapPoints, lineValues.size))
    val validIdx   = (firstValid until lineValues.size).toVector

    def cxFor(index: Int): Double =
      xForBar(layout, points.size, index) + barWidth(layout, points.size) / 2.0

    // Keep the line out of the hatched "no data" region at the start of the plot,
    // while still connecting runs that are interrupted by interior empty weeks.
    val polylines =
      if validIdx.sizeIs < 2 then ""
      else
        val polyPoints = validIdx.map { index =>
          s"${fmt(cxFor(index))},${fmt(yForValue(layout, scale, lineValues(index).toDouble))}"
        }.mkString(" ")
        s"<polyline fill='none' stroke='${line.stroke}' stroke-width='3' stroke-dasharray='${line.dashArray}' points='$polyPoints' />"

    val markers = validIdx.map { index =>
      val cx = cxFor(index)
      val cy = yForValue(layout, scale, lineValues(index).toDouble)
      s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='${line.markerFill}' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
    }.mkString("\n")

    polylines + "\n" + markers
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
          s"<line x1='${fmt(sx)}' y1='${fmt(cy)}' x2='${fmt(sx + 20)}' y2='${fmt(cy)}' stroke='${line.stroke}' stroke-width='3' stroke-dasharray='${line.dashArray}' />" +
          s"<rect x='${fmt(sx + 7)}' y='${fmt(cy - 3)}' width='6' height='6' fill='${line.markerFill}' transform='rotate(45 ${fmt(sx + 10)} ${fmt(cy)})' />" + textEl(line.label, cy)
    }.mkString("\n")

    box + "\n" + legendItems
  }

  private def renderTitles(
      layout: ChartLayout,
      title: String,
      yAxisLabel: String,
      xAxisLabel: String,
      yAxisFontSize: Int = FontConfig.axisLabel,
      xLabelY: Option[Double] = None,
      xAxisFontSize: Option[Int] = None
  ): String =
    val xSize = xAxisFontSize.getOrElse(yAxisFontSize)
    val xLabel = if xAxisLabel.isEmpty then "" else
      val y = xLabelY.getOrElse(layout.height - math.max(18.0, xSize * 0.9))
      s"\n<text x='${fmt(layout.plotX + layout.plotWidth / 2.0)}' y='${fmt(y)}' text-anchor='middle' font-size='$xSize' fill='#212529'>${svgEscape(xAxisLabel)}</text>"
    val titleEl = if title.isEmpty then "" else
      s"<text x='${fmt(layout.width / 2.0)}' y='18' text-anchor='middle' font-size='${FontConfig.title}' font-weight='700' fill='#111827'>${svgEscape(title)}</text>"
    val yLabel = if yAxisLabel.isEmpty then "" else
      val x = math.max(yAxisFontSize * 1.1, yAxisLabelPos)
      s"\n<text x='${fmt(x)}' y='${fmt(layout.plotY + layout.plotHeight / 2.0)}' transform='rotate(-90 ${fmt(x)} ${fmt(layout.plotY + layout.plotHeight / 2.0)})' text-anchor='middle' font-size='$yAxisFontSize' fill='#212529'>${svgEscape(yAxisLabel)}</text>"
    titleEl + yLabel + xLabel

  private def renderFooter(
      layout: ChartLayout,
      points: Vector[StackedBarPoint],
      activeKeys: Vector[String],
      topLabel: String,
      activeLines: Vector[LineSeries],
      totalLabel: String
  ): String = {
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
      height = 500,
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
      showLegend: Boolean = true,
      leadingGapPoints: Int = 0,
      width: Int = 900,
      height: Int = 560,
      bottomMargin: Double = 140,
      labelFontSize: Int = FontConfig.barLabel,
      axisFontSize: Int = FontConfig.tick,
      axisTitleFontSize: Int = FontConfig.axisLabel
  ): String = {
    val activeStacks = activeStackKeys(points, stackOrder)
    val activeLines  = activeLineSeries(lineSeries)
    val legendW      = 220.0
    val layout       = ChartLayout(
      width = math.max(width, points.size * 15 + (if showLegend then legendW.toInt + 200 else 100)),
      height = height,
      left = if axisFontSize == FontConfig.tick then autoLeftMargin
             else compactLeftMargin(axisFontSize, axisTitleFontSize),
      right = if showLegend then legendW + 40 else 24,
      top = 44,
      bottom = bottomMargin,
      legendWidth = legendW
    )
    val scale = computeScale(points, activeLines)

    val bars = points.zipWithIndex.map { case (point, index) =>
      renderStackedBar(layout, scale, points.size, index, point, activeStacks, colors)
    }.mkString("\n")

    val lines = activeLines.map(renderLineSeries(layout, scale, points, _, leadingGapPoints)).mkString("\n")

    val (weekAxis, axisContentBottom) = renderWeekAxis(layout, points, labelFontSize)
    // Place the x-axis title just below the deepest week-axis row instead of
    // pinning it to the bottom of the canvas.
    val xLabelY = axisContentBottom + axisTitleFontSize * 1.35

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
$gapHatchDefs
${renderGridAndAxes(layout, scale, axisFontSize)}
${renderTitles(layout, title, yAxisLabel, xAxisLabel, axisTitleFontSize, Some(xLabelY), Some(axisTitleFontSize))}
${renderGapHatch(layout, points.size, leadingGapPoints)}
$bars
$lines
$weekAxis
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

  /** One entry of a shared legend: a colour swatch, a line symbol, or a hatch swatch. */
  private sealed trait LegendEntry
  private case class StackEntry(key: String)                              extends LegendEntry
  private case class LineEntry(line: LineSeries)                          extends LegendEntry
  private case class HatchEntry(label: String)                            extends LegendEntry

  /**
   * Renders a standalone legend that flows entries left-to-right. Used as the single
   * shared legend for a figure composed of many subplots, so each subplot can omit
   * its own legend. Column widths hug the labels they contain, which keeps a
   * single-row legend compact.
   *
   * When `splitRows` is set, the colour swatches form the first row while the line
   * symbol and the optional hatch entry move to a second row, so the long harness
   * list stays on one line.
   */
  def renderSharedLegendSvg(
      stackOrder: Vector[String],
      colors: Map[String, String],
      lineSeries: Vector[LineSeries],
      columns: Int = 4,
      fontSize: Int = FontConfig.legend,
      entryHeight: Double = 26.0,
      padding: Double = 10.0,
      hatchLabel: Option[String] = None,
      splitRows: Boolean = false
  ): String = {
    val dummyPoints =
      if stackOrder.nonEmpty then Vector(StackedBarPoint("", stackOrder.map(k => k -> 1).toMap))
      else Vector.empty
    val activeStacks = activeStackKeys(dummyPoints, stackOrder)
    val activeLines  = activeLineSeries(lineSeries)

    val stackEntries = activeStacks.map(StackEntry(_))
    val extraEntries: Vector[LegendEntry] =
      activeLines.map(LineEntry(_)) ++ hatchLabel.map(HatchEntry(_)).toVector

    // Symbol sizes scale with the font so the legend stays balanced at any size.
    val swatchW   = fontSize * 0.45
    val swatchH   = fontSize * 0.42
    val symbolEnd = swatchW + fontSize * 0.1
    val textGap   = symbolEnd + fontSize * 0.3

    def labelOf(entry: LegendEntry): String = entry match
      case StackEntry(key)   => key
      case LineEntry(line)   => line.label
      case HatchEntry(label) => label

    // Rough advance width for the default sans-serif face. Each entry only reserves
    // what its own label needs, and a constant gap keeps the spacing even. Reserving
    // the widest label for every entry would inflate the image and shrink the type
    // once the legend is scaled down to the text width.
    def textWidth(label: String): Double = label.map(advanceOf).sum * fontSize

    val entryGap = fontSize * 0.25

    def entryWidth(entry: LegendEntry): Double = textGap + textWidth(labelOf(entry))

    def rowWidth(row: Vector[LegendEntry]): Double =
      row.map(entryWidth).sum + entryGap * math.max(0, row.size - 1)

    // Rows of entries. Splitting keeps the harness swatches on a single line.
    val rows: Vector[Vector[LegendEntry]] =
      if splitRows then
        Vector(stackEntries, extraEntries).filter(_.nonEmpty)
      else
        val all = stackEntries ++ extraEntries
        val cols = math.max(1, math.min(columns, all.size))
        all.grouped(cols).toVector

    val width  = math.ceil(padding * 2 + rows.map(rowWidth).maxOption.getOrElse(0.0)).toInt
    val height = math.ceil(padding * 2 + rows.size * entryHeight).toInt

    def textEl(label: String, x: Double, cy: Double): String =
      s"<text x='${fmt(x)}' y='${fmt(cy + fontSize * 0.36)}' font-size='$fontSize' fill='#212529'>${svgEscape(label)}</text>"

    def symbol(entry: LegendEntry, x: Double, cy: Double): String = entry match
      case StackEntry(key) =>
        s"<rect x='${fmt(x)}' y='${fmt(cy - swatchH / 2)}' width='${fmt(swatchW)}' height='${fmt(swatchH)}' fill='${colors.getOrElse(key, "#adb5bd")}' stroke='#ffffff' stroke-width='0.6' />"
      case HatchEntry(_) =>
        s"<rect x='${fmt(x)}' y='${fmt(cy - swatchH / 2)}' width='${fmt(swatchW)}' height='${fmt(swatchH)}' fill='url(#legendHatch)' stroke='#adb5bd' stroke-width='0.8' />"
      case LineEntry(line) =>
        val markerR = fontSize * 0.18
        s"<line x1='${fmt(x)}' y1='${fmt(cy)}' x2='${fmt(x + symbolEnd)}' y2='${fmt(cy)}' stroke='${line.stroke}' stroke-width='3' stroke-dasharray='${line.dashArray}' />" +
          s"<rect x='${fmt(x + symbolEnd / 2 - markerR)}' y='${fmt(cy - markerR)}' width='${fmt(markerR * 2)}' height='${fmt(markerR * 2)}' fill='${line.markerFill}' transform='rotate(45 ${fmt(x + symbolEnd / 2)} ${fmt(cy)})' />"

    val items = rows.zipWithIndex.map { case (row, rowIndex) =>
      val cy = padding + entryHeight * (rowIndex + 0.5)
      // Left-align each row, accumulating its own entry offsets.
      val (_, rowSvg) = row.foldLeft((padding, Vector.empty[String])) { case ((x, acc), entry) =>
        val svg = symbol(entry, x, cy) + textEl(labelOf(entry), x + textGap, cy)
        (x + entryWidth(entry) + entryGap, acc :+ svg)
      }
      rowSvg.mkString("\n")
    }.mkString("\n")

    val defs = if hatchLabel.isDefined then legendHatchDefs else ""

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='$height' viewBox='0 0 $width $height'>
${renderBackground()}
$defs
$items
</svg>
"""
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
      showLegend: Boolean = true,
      leadingGapPoints: Int = 0,
      width: Int = 900,
      height: Int = 560,
      bottomMargin: Double = 140,
      labelFontSize: Int = FontConfig.barLabel,
      axisFontSize: Int = FontConfig.tick,
      axisTitleFontSize: Int = FontConfig.axisLabel
  ): String = {
    val activeStacks = activeStackKeys(points, stackOrder)
    val legendW    = 220.0
    val rightAxisW = 110.0
    val layout     = ChartLayout(
      width = math.max(width, points.size * 15 + (if showLegend then legendW.toInt + 200 else 100)),
      height = height,
      left = if axisFontSize == FontConfig.tick then autoLeftMargin else compactLeftMargin(axisFontSize, axisTitleFontSize),
      right = rightAxisW + (if showLegend then legendW + 40 else 24),
      top = 44,
      bottom = bottomMargin,
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
        s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='$axisFontSize' fill='#495057'>$tick</text>"
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
        s"<text x='${fmt(rightX + 8)}' y='${fmt(y + 4)}' text-anchor='start' font-size='$axisFontSize' fill='#343a40'>${formatSi(tick.toDouble)}</text>"
      }.mkString("\n") +
      s"\n<line x1='${fmt(rightX)}' y1='${fmt(layout.plotY)}' x2='${fmt(rightX)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />"

    val rightAxisLabel: String = {
      val rx = layout.plotX + layout.plotWidth + rightAxisW - axisTitleFontSize * 0.9
      val ry = layout.plotY + layout.plotHeight / 2.0
      s"<text x='${fmt(rx)}' y='${fmt(ry)}' transform='rotate(90 ${fmt(rx)} ${fmt(ry)})' text-anchor='middle' font-size='$axisTitleFontSize' fill='#343a40'>${svgEscape(yRightLabel)}</text>"
    }

    // Keep bars and the count line out of the hatched leading gap.
    val dataStart = math.max(0, math.min(leadingGapPoints, points.size))
    val bars: String = pctByPoint.zipWithIndex.map { case (pctValues, index) =>
      if index < dataStart then ""
      else renderPctBar(layout, points.size, index, pctValues, activeStacks, colors)
    }.mkString("\n")

    val countLine: String = {
      val counts = countPerWeek.padTo(points.size, 0).take(points.size)
      val valid  = (dataStart until points.size).toVector
      val poly =
        if valid.sizeIs < 2 then ""
        else
          val polyPoints = valid.map { i =>
            val cx = xForBar(layout, points.size, i) + barWidth(layout, points.size) / 2.0
            s"${fmt(cx)},${fmt(yForCount(counts(i).toDouble))}"
          }.mkString(" ")
          s"<polyline fill='none' stroke='#343a40' stroke-width='3' stroke-dasharray='6 4' points='$polyPoints' />"
      val markers = valid.map { i =>
        val cx = xForBar(layout, points.size, i) + barWidth(layout, points.size) / 2.0
        val cy = yForCount(counts(i).toDouble)
        s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='#343a40' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
      }.mkString("\n")
      poly + "\n" + markers
    }

    val legendSvg = if showLegend then
      renderLegend(layout, activeStacks, colors, Vector(LineSeries(yRightLabel, "#343a40", "6 4", "#343a40", countPerWeek)),
        lxOverride = Some(layout.plotX + layout.plotWidth + rightAxisW + 16))
    else ""

    val (weekAxis, axisContentBottom) = renderWeekAxis(layout, points, labelFontSize)
    val xLabelY = axisContentBottom + axisTitleFontSize * 1.35

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${layout.width}' height='${layout.height}' viewBox='0 0 ${layout.width} ${layout.height}'>
${renderBackground()}
$gapHatchDefs
$leftAxis
$rightAxis
${renderTitles(layout, title, "Agent share (%)", xAxisLabel, axisTitleFontSize, Some(xLabelY), Some(axisTitleFontSize))}
$rightAxisLabel
${renderGapHatch(layout, points.size, leadingGapPoints)}
$bars
$countLine
$weekAxis
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

  def renderPercentageStackedWithCountLineSvgLog(
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
        s"<text x='${fmt(layout.plotX - 10)}' y='${fmt(y + 4)}' text-anchor='end' font-size='${FontConfig.tick}' fill='#495057'>$tick</text>"
      }.mkString("\n") +
      s"\n<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY + layout.plotHeight)}' x2='${fmt(layout.plotX + layout.plotWidth)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />" +
      s"\n<line x1='${fmt(layout.plotX)}' y1='${fmt(layout.plotY)}' x2='${fmt(layout.plotX)}' y2='${fmt(layout.plotY + layout.plotHeight)}' stroke='#343a40' stroke-width='1.2' />"

    // Right y-axis: logarithmic commit count scale
    val countMax      = math.max(1, countPerWeek.maxOption.getOrElse(1))
    
    // For logarithmic scale, we'll use powers of 10
    def yForCountLog(v: Double): Double = {
      val logMin = math.log10(1.0)  // Start at 1 (log10(1) = 0)
      val logMax = math.log10(countMax.toDouble)
      val logVal = math.log10(math.max(1.0, v))
      layout.plotY + layout.plotHeight - ((logVal - logMin) / (logMax - logMin)) * layout.plotHeight
    }

    val rightX = layout.plotX + layout.plotWidth
    
    // Generate logarithmic ticks (1, 10, 100, 1000, etc.)
    val logTicks = {
      val maxExp = math.ceil(math.log10(countMax.toDouble)).toInt
      val ticks = (0 to maxExp).map(exp => math.pow(10, exp.toDouble).toInt)
      ticks
    }

    val rightAxis: String =
      logTicks.map { tick =>
        val y = yForCountLog(tick.toDouble)
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
        s"${fmt(cx)},${fmt(yForCountLog(c.toDouble))}"
      }.mkString(" ")
      val markers = counts.zipWithIndex.map { case (c, i) =>
        val cx = xForBar(layout, points.size, i) + barWidth(layout, points.size) / 2.0
        val cy = yForCountLog(c.toDouble)
        s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='#343a40' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
      }.mkString("\n")
      s"<polyline fill='none' stroke='#343a40' stroke-width='3' stroke-dasharray='6 4' points='$poly' />\n$markers"
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

  case class TableData(
      headers: Vector[String],
      rows: Vector[Vector[String]],
      rowLabels: Vector[String],
      cellValues: Vector[Vector[Double]]
  )

  def renderColoredTable(
      title: String,
      subtitle: String = "",
      tableData: TableData,
      columnStats: Vector[(Double, Double)], // (mean, stdDev) for each column
      thresholdSigma: Double = 0.25
  ): String = {
    val cellWidth = 80.0
    val cellHeight = 30.0
    val headerHeight = 40.0
    val leftMargin = 150.0
    val topMargin = 60.0
    val rightMargin = 20.0
    val bottomMargin = 60.0

    val numCols = tableData.headers.size
    val numRows = tableData.rows.size

    val width = leftMargin + numCols * cellWidth + rightMargin
    val height = topMargin + headerHeight + numRows * cellHeight + bottomMargin

    // Fine-grained color shading: low opacity for small deviations, full opacity for large deviations
    // Uses a non-linear scaling for better visual distinction
    def getColor(value: Double, colIndex: Int): String = {
      val (mean, stdDev) = columnStats(colIndex)
      if stdDev == 0 then "#ffffff"
      else
        val diff = value - mean
        val sigma = diff / stdDev
        if math.abs(sigma) < thresholdSigma then "#ffffff"
        else
          // Define discrete opacity steps for different sigma ranges
          val absSigma = math.abs(sigma)
          val opacity = 
            if absSigma < 0.5 then 0.2  // 0.25σ - 0.5σ
            else if absSigma < 0.75 then 0.35  // 0.5σ - 0.75σ
            else if absSigma < 1.0 then 0.5  // 0.75σ - 1.0σ
            else if absSigma < 1.5 then 0.6  // 1.0σ - 1.5σ
            else if absSigma < 2.0 then 0.7  // 1.5σ - 2.0σ
            else if absSigma < 3.0 then 0.8  // 2.0σ - 3.0σ
            else 0.9  // 3.0σ+
          
          // Use warmer/cooler colors similar to the existing palette
          if sigma > 0 then s"rgba(230, 57, 70, $opacity)"  // Warmer red
          else s"rgba(13, 110, 253, $opacity)"  // Cooler blue
    }

    val headerCells = tableData.headers.zipWithIndex.map { case (header, colIndex) =>
      s"""<text x='${fmt(leftMargin + colIndex * cellWidth + cellWidth / 2)}' y='${fmt(topMargin + headerHeight - 10)}'
text-anchor='middle' font-size='${FontConfig.boxPlotLabel}' font-weight='600' fill='#111827'>${svgEscape(header)}</text>"""
    }.mkString("\n    ")

    val gridLines = (0 to numCols).map { colIndex =>
      s"""<line x1='${fmt(leftMargin + colIndex * cellWidth)}' y1='${fmt(topMargin + headerHeight)}'
x2='${fmt(leftMargin + colIndex * cellWidth)}' y2='${fmt(topMargin + headerHeight + numRows * cellHeight)}'
stroke='#e9ecef' stroke-width='1'/>"""
    }.mkString("\n    ")

    val rowLines = (0 to numRows).map { rowIndex =>
      s"""<line x1='${fmt(leftMargin)}' y1='${fmt(topMargin + headerHeight + rowIndex * cellHeight)}'
x2='${fmt(leftMargin + numCols * cellWidth)}' y2='${fmt(topMargin + headerHeight + rowIndex * cellHeight)}'
stroke='#e9ecef' stroke-width='1'/>"""
    }.mkString("\n    ")

    val rowLabels = tableData.rowLabels.zipWithIndex.map { case (label, rowIndex) =>
      val isLastRow = rowIndex == numRows - 1
      s"""<text x='${fmt(leftMargin - 10)}' y='${fmt(topMargin + headerHeight + rowIndex * cellHeight + cellHeight / 2 + 4)}'
text-anchor='end' font-size='${FontConfig.boxPlotLabel}' fill='${if isLastRow then "#6c757d" else "#212529"}' font-weight='${if isLastRow then "600" else "400"}'>${svgEscape(label)}</text>"""
    }.mkString("\n    ")

    val dataCells = tableData.rows.zipWithIndex.flatMap { case (row, rowIndex) =>
      row.zipWithIndex.map { case (cellValue, colIndex) =>
        val value = tableData.cellValues(rowIndex)(colIndex)
        val isLastRow = rowIndex == numRows - 1
        val color = if isLastRow then "#e9ecef" else getColor(value, colIndex)
        val textColor = if isLastRow then "#495057" else "#212529"
        val fontWeight = if isLastRow then "600" else "400"

        s"""<rect x='${fmt(leftMargin + colIndex * cellWidth + 1)}' y='${fmt(topMargin + headerHeight + rowIndex * cellHeight + 1)}'
width='${fmt(cellWidth - 2)}' height='${fmt(cellHeight - 2)}' fill='$color' rx='2' ry='2'/>
<text x='${fmt(leftMargin + colIndex * cellWidth + cellWidth / 2)}' y='${fmt(topMargin + headerHeight + rowIndex * cellHeight + cellHeight / 2 + 4)}'
text-anchor='middle' font-size='${FontConfig.boxPlotLabel}' fill='$textColor' font-weight='$fontWeight'>$cellValue</text>"""
      }
    }.mkString("\n    ")

    val legendX = leftMargin - 140  // Position legend further to the left of the table
    val legendYStart = topMargin + headerHeight  // Start legend at the same height as table content
    
    // Create vertical legend with color boxes and labels
    val legendItems = List(
      ("rgba(230, 57, 70, 0.9)", "&gt;= +3.0σ"),
      ("rgba(230, 57, 70, 0.8)", "+2.0σ to +3.0σ"),
      ("rgba(230, 57, 70, 0.7)", "+1.5σ to +2.0σ"),
      ("rgba(230, 57, 70, 0.6)", "+1.0σ to +1.5σ"),
      ("rgba(230, 57, 70, 0.5)", "+0.75σ to +1.0σ"),
      ("rgba(230, 57, 70, 0.35)", "+0.5σ to +0.75σ"),
      ("rgba(230, 57, 70, 0.2)", "+0.25σ to +0.5σ"),
      ("#ffffff", "-0.25σ to +0.25σ"),
      ("rgba(13, 110, 253, 0.2)", "-0.25σ to -0.5σ"),
      ("rgba(13, 110, 253, 0.35)", "-0.5σ to -0.75σ"),
      ("rgba(13, 110, 253, 0.5)", "-0.75σ to -1.0σ"),
      ("rgba(13, 110, 253, 0.6)", "-1.0σ to -1.5σ"),
      ("rgba(13, 110, 253, 0.7)", "-1.5σ to -2.0σ"),
      ("rgba(13, 110, 253, 0.8)", "-2.0σ to -3.0σ"),
      ("rgba(13, 110, 253, 0.9)", "&lt;= -3.0σ")
    )
    
    val legendText = legendItems.zipWithIndex.map { case ((color, label), index) =>
      val yPos = legendYStart + index * 20
      val borderColor = if color == "#ffffff" then "#6c757d" else color
      s"""<rect x='${fmt(legendX)}' y='${fmt(yPos)}' width='15' height='15' fill='$color' stroke='$borderColor' stroke-width='1'/>""" + 
      s"""<text x='${fmt(legendX + 20)}' y='${fmt(yPos + 12)}' font-size='10' fill='#6c757d'>$label</text>"""
    }.mkString("\n    ")

    val titleY = topMargin - 10
    val subtitleY = topMargin + 10
    
    val titleText = if subtitle.isEmpty then 
      s"""<text x='${fmt(width / 2)}' y='${fmt(titleY)}' text-anchor='middle' font-size='${FontConfig.title}' font-weight='700' fill='#111827'>${svgEscape(title)}</text>""" 
    else
      s"""<text x='${fmt(width / 2)}' y='${fmt(titleY)}' text-anchor='middle' font-size='${FontConfig.title}' font-weight='700' fill='#111827'>${svgEscape(title)}</text>
<text x='${fmt(width / 2)}' y='${fmt(subtitleY)}' text-anchor='middle' font-size='${FontConfig.subtitle}' font-weight='400' fill='#495057'>${svgEscape(subtitle)}</text>"""

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='${fmt(width)}' height='${fmt(height)}' viewBox='0 0 ${fmt(width)} ${fmt(height)}'>
${renderBackground()}
$titleText
$headerCells
$gridLines
$rowLines
$rowLabels
$dataCells
$legendText
</svg>
"""
  }
}
