package whataretheydoing

import com.github.tototoshi.csv.*
import whataretheydoing.DataAnalysis.taskTypeCounts

import java.nio.file.Files

object CsvTasks {

  @main def exportTasksPerAgent(): Unit = {
    val commitTypeColumns = CommitType.values.toVector.sortBy(_.ordinal)

    val csvData = taskTypeCounts.toVector.sortBy((k, _) => (k.developer, k.agent)).map { case ((developer, agent), typeCounts) =>
      val total = typeCounts.values.sum
      Map[String, String](
        "developer"       -> developer,
        "agent"           -> agent,
        "commits_total"   -> total.toString
      ) ++ commitTypeColumns.map { ct =>
        s"commits_${ct.toString.toLowerCase}" -> typeCounts.getOrElse(ct, 0).toString
      }.toMap
    }

    val header = "developer" +: "agent" +: "commits_total" +: commitTypeColumns.map(ct =>
      s"commits_${ct.toString.toLowerCase}"
    )

    val outputFile = GlobalPaths.outputPath.resolve("tasks-per-agent.csv").toFile
    Files.createDirectories(GlobalPaths.outputPath)
    val writer = CSVWriter.open(outputFile)
    try
      writer.writeRow(header)
      csvData.foreach { row =>
        writer.writeRow(header.map(row))
      }
    finally
      writer.close()

    println(s"Wrote ${csvData.size} rows to $outputFile")
  }
}
