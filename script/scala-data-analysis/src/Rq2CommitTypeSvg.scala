package whataretheydoing

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object Rq2CommitTypeSvg {
  import DevAgentCommitTypes.*

  private def svgEscape(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

  private def renderCommitTypeSvg(handle: String, rows: Vector[CommitTypePeriodRow]): String = {
    val width = math.max(1100, rows.size * 46 + 220)
    val height = 600
    val left = 62.0
    val right = 20.0
    val top = 30.0
    val bottom = 112.0
    val plotWidth = width - left - right
    val plotHeight = height - top - bottom
    val maxStack = rows.map(_.countsByType.values.sum).maxOption.getOrElse(0)
    val maxTotal = rows.map(_.totalCommits).maxOption.getOrElse(0)
    val yMaxRaw = math.max(maxStack, maxTotal)
    val yStep = math.max(25, ((yMaxRaw + 49) / 50) * 10)
    val yMax = math.max(yStep, ((yMaxRaw + yStep - 1) / yStep) * yStep)
    val slotWidth = if rows.nonEmpty then plotWidth / rows.size else plotWidth
    val barWidth = slotWidth * 0.78

    def xFor(index: Int): Double = left + slotWidth * index + (slotWidth - barWidth) / 2.0
    def yFor(value: Double): Double = top + plotHeight - (value / yMax) * plotHeight
    def fmt(value: Double): String = f"$value%.2f"

    val gridLines =
      (0 to yMax by yStep).map { tick =>
        val y = yFor(tick.toDouble)
        s"<line x1='${fmt(left)}' y1='${fmt(y)}' x2='${fmt(left + plotWidth)}' y2='${fmt(y)}' stroke='#dee2e6' stroke-width='1' />" +
          s"<text x='${fmt(left - 8)}' y='${fmt(y + 4)}' text-anchor='end' font-size='12' fill='#212529'>$tick</text>"
      }.mkString("\n")

    val bars = rows.zipWithIndex.map { case (row, index) =>
      val x = xFor(index)
      val segments =
        commitTypeOrder.foldLeft((0.0, Vector.empty[String])) { case ((bottomValue, acc), commitType) =>
          val count = row.countsByType.getOrElse(commitType, 0)
          if count <= 0 then (bottomValue, acc)
          else
              val yTop = yFor(bottomValue + count)
              val yBottom = yFor(bottomValue)
              val rectHeight = yBottom - yTop
              val rect =
                s"<rect x='${fmt(x)}' y='${fmt(yTop)}' width='${fmt(barWidth)}' height='${fmt(rectHeight)}' fill='${commitTypeColors(commitType)}' stroke='white' stroke-width='0.5' />"
              (bottomValue + count, acc :+ rect)
        }._2

      val labelX = x + barWidth / 2.0
      val labelY = top + plotHeight + 38.0
      val label =
        s"<text x='${fmt(labelX)}' y='${fmt(labelY)}' transform='rotate(45 ${fmt(labelX)} ${fmt(labelY)})' text-anchor='start' font-size='11' fill='#212529'>${svgEscape(row.periodIso)}</text>"
      (segments :+ label).mkString("\n")
    }.mkString("\n")

    val linePoints = rows.zipWithIndex.map { case (row, index) =>
      s"${fmt(xFor(index) + barWidth / 2.0)},${fmt(yFor(row.totalCommits.toDouble))}"
    }.mkString(" ")

    val lineMarkers = rows.zipWithIndex.map { case (row, index) =>
      val cx = xFor(index) + barWidth / 2.0
      val cy = yFor(row.totalCommits.toDouble)
      s"<rect x='${fmt(cx - 2.5)}' y='${fmt(cy - 2.5)}' width='5' height='5' fill='#343a40' transform='rotate(45 ${fmt(cx)} ${fmt(cy)})' />"
    }.mkString("\n")

    val legendEntries =
      (Vector("total commits (snapshot)") ++ commitTypeOrder.map(_.toString.toLowerCase)).zipWithIndex.map {
        case (label, idx) =>
          val y = top + 14 + idx * 20
          if idx == 0 then
              s"<line x1='${fmt(left + 10)}' y1='${fmt(y)}' x2='${fmt(left + 30)}' y2='${fmt(y)}' stroke='#343a40' stroke-width='2' stroke-dasharray='6 4' />" +
                s"<rect x='${fmt(left + 18)}' y='${fmt(y - 3)}' width='6' height='6' fill='#343a40' transform='rotate(45 ${fmt(left + 21)} ${fmt(y)})' />" +
                s"<text x='${fmt(left + 38)}' y='${fmt(y + 4)}' font-size='12' fill='#212529'>${svgEscape(label)}</text>"
          else
              val ct = commitTypeOrder(idx - 1)
              s"<rect x='${fmt(left + 10)}' y='${fmt(y - 8)}' width='18' height='12' fill='${commitTypeColors(ct)}' />" +
                s"<text x='${fmt(left + 38)}' y='${fmt(y + 2)}' font-size='12' fill='#212529'>${svgEscape(label)}</text>"
      }.mkString("\n")

    val totalSnapshot = rows.map(_.totalCommits).sum
    val embeddedRecords = rows.map(_.countsByType.values.sum).sum
    val byTypeTotals = commitTypeOrder.map(t => t -> rows.map(_.countsByType.getOrElse(t, 0)).sum).filter(_._2 > 0)
    val dominantType = byTypeTotals.sortBy(-_._2).headOption.map { case (t, c) => s"Top type: ${t.toString.toLowerCase} ($c)" }.getOrElse("Top type: -")
    val footer = s"Total snapshot commits: $totalSnapshot  |  Embedded records: $embeddedRecords  |  Periods: ${rows.size}  |  $dominantType"

    s"""<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='$height' viewBox='0 0 $width $height'>
<rect width='100%' height='100%' fill='white'/>
<text x='${fmt(width / 2.0)}' y='24' text-anchor='middle' font-size='18' font-weight='700' fill='#111'>Commit Types Over Time — @${svgEscape(handle)}</text>
$gridLines
<rect x='${fmt(left)}' y='${fmt(top)}' width='${fmt(plotWidth)}' height='${fmt(plotHeight)}' fill='none' stroke='#212529' stroke-width='1'/>
$bars
<polyline fill='none' stroke='#343a40' stroke-width='2' stroke-dasharray='6 4' points='$linePoints' />
$lineMarkers
$legendEntries
<text x='22' y='${fmt(top + plotHeight / 2.0)}' transform='rotate(-90 22 ${fmt(top + plotHeight / 2.0)})' text-anchor='middle' font-size='14' fill='#212529'>Commits</text>
<text x='${fmt(left + plotWidth / 2.0)}' y='${fmt(height - 36.0)}' text-anchor='middle' font-size='14' fill='#212529'>Period</text>
<text x='${fmt(width / 2.0)}' y='${fmt(height - 10.0)}' text-anchor='middle' font-size='12' fill='#212529'>${svgEscape(footer)}</text>
</svg>
"""
  }

  private def writeSvg(path: java.nio.file.Path, svg: String): Unit = {
    Files.writeString(path, svg, StandardCharsets.UTF_8)
    ()
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
          writeSvg(svgPath, renderCommitTypeSvg(handle, svgRows))
          println(s"Wrote commit-type SVG for @$handle to $svgPath")
    }
  }
}
