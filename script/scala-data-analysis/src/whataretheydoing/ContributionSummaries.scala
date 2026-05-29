package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray

import java.nio.file.Files
import java.time.LocalDate
import scala.jdk.CollectionConverters.*

object ContributionSummaries {
  lazy val contributionSummaries: Map[String, DeveloperContributionSummary] =
    DataAnalysis.time("load contribution summaries") {
      val dir = GlobalPaths.contributionSummaryDir
      Files.list(dir).nn.iterator.nn.asScala
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
        .map { path =>
          val summary = readFromArray[DeveloperContributionSummary](Files.readAllBytes(path))
          summary.developer -> summary
        }.toMap
    }

  def totalCommitsFromSummary(handle: String, week: LocalDate): Int =
    contributionSummaries.get(handle) match
        case Some(summary) =>
          val weekEnd = week.plusDays(6)
          summary.contributions
            .filter { d =>
              val date = LocalDate.parse(d.date)
              !date.isBefore(week) && !date.isAfter(weekEnd)
            }
            .map(_.contributionCount).sum
        case None => 0

}
