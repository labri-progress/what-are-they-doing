package whataretheydoing

import com.github.tototoshi.csv.*

import java.nio.file.Files

object CsvTasks {

  private def agentBucket(agents: Set[String]): String =
    if agents.isEmpty then "no signal"
    else if agents.size > 1 then "multi agent"
    else agents.head

  @main def exportTasksPerAgent(): Unit = {
    val commitTypeColumns = CommitType.values.toVector.sortBy(_.ordinal)

    val rows = CommitProcessing.aggregateCommitData.iterator
      .flatMap { case (developer, _, _, snapshot) =>
        snapshot.days.valuesIterator.flatMap { dayData =>
          dayData.commits.iterator.flatMap { commit =>
            CommitProcessing.commitSignals(commit.sha).map { classified =>
              (developer, agentBucket(classified.agents), classified.commitType)
            }
          }
        }
      }
      .toVector
      .groupMapReduce(entry => (entry._1, entry._2))(e => Map[CommitType, Int](e._3 -> 1)) { (a, b) =>
        a ++ b.view.mapValues(_ + a.getOrElse(b.head._1, 0))
      }

    val csvData = rows.toVector.sortBy((k, _) => (k._1, k._2)).map { case ((developer, agent), typeCounts) =>
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
