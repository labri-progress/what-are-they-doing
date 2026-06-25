package whataretheydoing

import com.github.tototoshi.csv.*
import whataretheydoing.DataAnalysis.taskTypeCounts

import java.nio.file.Files
import java.time.{DayOfWeek, LocalDate}

object CsvTasks {

  private def fmtPct(count: Int, total: Int): String =
    if total == 0 then "0.0" else f"${count.toDouble / total * 100}%.1f"

  private def writeCsv(path: java.nio.file.Path, header: Vector[String], rows: Vector[Map[String, String]]): Unit =
    Files.createDirectories(path.getParent)
    val writer = CSVWriter.open(path.toFile)
    try
      writer.writeRow(header)
      rows.foreach(row => writer.writeRow(header.map(row)))
    finally
      writer.close()
    println(s"Wrote ${rows.size} rows to $path")

  @main def exportTasksPerAgent(): Unit = {
    val commitTypeColumns = CommitType.values.toVector.sortBy(_.ordinal)

    val header = "developer" +: "agent" +: "commits_total" +: commitTypeColumns.map(ct =>
      s"commits_${ct.toString.toLowerCase}"
    )

    val (countRows, pctRows) = taskTypeCounts.toVector.sortBy((k, _) => (k.developer, k.agent)).map { case ((developer, agent), typeCounts) =>
      val total = typeCounts.values.sum
      val countRow = Map[String, String](
        "developer"       -> developer,
        "agent"           -> agent,
        "commits_total"   -> total.toString
      ) ++ commitTypeColumns.map { ct =>
        s"commits_${ct.toString.toLowerCase}" -> typeCounts.getOrElse(ct, 0).toString
      }.toMap
      val pctRow = Map[String, String](
        "developer"       -> developer,
        "agent"           -> agent,
        "commits_total"   -> "100.0"
      ) ++ commitTypeColumns.map { ct =>
        s"commits_${ct.toString.toLowerCase}" -> fmtPct(typeCounts.getOrElse(ct, 0), total)
      }.toMap
      (countRow, pctRow)
    }.unzip

    writeCsv(GlobalPaths.outputPath.resolve("tasks-per-agent.csv"), header, countRows)
    writeCsv(GlobalPaths.outputPath.resolve("tasks-per-agent-percentage.csv"), header, pctRows)
  }

  @main def exportCommitTypesByAgent(): Unit = {
    val commitTypeColumns = CommitType.values.toVector.sortBy(_.ordinal)

    val agentTypeCounts: Map[String, Map[CommitType, Int]] = taskTypeCounts
      .groupMapReduce((k, _) => k.agent)((_, v) => v) { (a, b) =>
        commitTypeColumns.map { ct =>
          ct -> (a.getOrElse(ct, 0) + b.getOrElse(ct, 0))
        }.toMap
      }

    val header = "agent" +: "commits_total" +: commitTypeColumns.map(ct =>
      s"commits_${ct.toString.toLowerCase}"
    )

    val (countRows, pctRows) = agentTypeCounts.toVector.sortBy(_._1).map { case (agent, typeCounts) =>
      val total = typeCounts.values.sum
      val countRow = Map[String, String](
        "agent"         -> agent,
        "commits_total" -> total.toString
      ) ++ commitTypeColumns.map { ct =>
        s"commits_${ct.toString.toLowerCase}" -> typeCounts.getOrElse(ct, 0).toString
      }.toMap
      val pctRow = Map[String, String](
        "agent"         -> agent,
        "commits_total" -> "100.0"
      ) ++ commitTypeColumns.map { ct =>
        s"commits_${ct.toString.toLowerCase}" -> fmtPct(typeCounts.getOrElse(ct, 0), total)
      }.toMap
      (countRow, pctRow)
    }.unzip

    writeCsv(GlobalPaths.outputPath.resolve("commit-types-by-agent.csv"), header, countRows)
    writeCsv(GlobalPaths.outputPath.resolve("commit-types-by-agent-percentage.csv"), header, pctRows)
  }

  @main def exportAgentsByCommitType(): Unit = {
    val commitTypeColumns = CommitType.values.toVector.sortBy(_.ordinal)

    val agentTypeCounts: Map[String, Map[CommitType, Int]] = taskTypeCounts
      .groupMapReduce((k, _) => k.agent)((_, v) => v) { (a, b) =>
        commitTypeColumns.map { ct =>
          ct -> (a.getOrElse(ct, 0) + b.getOrElse(ct, 0))
        }.toMap
      }

    val agents = agentTypeCounts.keys.toVector.sorted
    val header = "commit_type" +: agents

    val (countRows, pctRows) = commitTypeColumns.map { ct =>
      val counts = agents.map { agent =>
        agent -> agentTypeCounts.getOrElse(agent, Map.empty).getOrElse(ct, 0)
      }
      val rowTotal = counts.map(_._2).sum
      val countRow = Map[String, String]("commit_type" -> ct.toString.toLowerCase) ++
        counts.map((agent, count) => agent -> count.toString).toMap
      val pctRow = Map[String, String]("commit_type" -> ct.toString.toLowerCase) ++
        counts.map((agent, count) => agent -> fmtPct(count, rowTotal)).toMap
      (countRow, pctRow)
    }.unzip

    writeCsv(GlobalPaths.outputPath.resolve("agents-by-commit-type.csv"), header, countRows)
    writeCsv(GlobalPaths.outputPath.resolve("agents-by-commit-type-percentage.csv"), header, pctRows)
  }

  @main def exportAgentSignalRatePerDeveloper(): Unit = {
    // Aggregate per (developer, week) → Map[agentBucket, commitCount]
    val weeklyData: Map[(String, LocalDate), Map[String, Int]] =
      CommitProcessing.aggregateCommitData.iterator
        .flatMap { case (developer, _, _, snapshot) =>
          snapshot.days.iterator.flatMap { case (day, dayData) =>
            val week = day.`with`(DayOfWeek.MONDAY)
            dayData.commits.iterator.flatMap { commit =>
              CommitProcessing.commitSignals(commit.sha).map { classified =>
                val bucket =
                  if classified.agents.isEmpty then "no signal"
                  else if classified.agents.size > 1 then "multi agent"
                  else classified.agents.head
                ((developer, week), bucket)
              }
            }
          }
        }
        .toVector
        .groupMapReduce(_._1)(e => Map(e._2 -> 1)) { (a, b) =>
          (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0) + b.getOrElse(k, 0))).toMap
        }

    def summarize(values: Vector[Double]): Map[String, Double] =
      val sorted = values.sorted
      val n      = values.size
      val mean   = values.sum / n
      val median = if n % 2 == 0 then (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0 else sorted(n / 2)
      val stddev = math.sqrt(values.map(v => math.pow(v - mean, 2)).sum / n)
      Map("min" -> sorted.head, "max" -> sorted.last, "mean" -> mean, "median" -> median, "stddev" -> stddev)

    // Agent bucket ordering: named agents first (sorted), then "multi agent", then "no signal"
    val allBuckets   = weeklyData.values.flatMap(_.keys).toVector.distinct
    val agentBuckets =
      allBuckets.filter(a => a != "no signal" && a != "multi agent").sorted ++
        (if allBuckets.contains("multi agent") then Vector("multi agent") else Vector.empty) ++
        (if allBuckets.contains("no signal") then Vector("no signal") else Vector.empty)

    val header = Vector("week", "commits_total", "commits_with_agent_signal", "agent_rate") ++
      agentBuckets.flatMap(a => Vector(s"${a.replace(" ", "_")}_count", s"${a.replace(" ", "_")}_pct"))

    weeklyData.toVector
      .groupBy(_._1._1)
      .foreach { case (developer, entries) =>
        val weekEntries = entries.sortBy(_._1._2).map { case ((_, week), bucketCounts) =>
          val total     = bucketCounts.values.sum
          val withAgent = total - bucketCounts.getOrElse("no signal", 0)
          val rate      = if total == 0 then 0.0 else withAgent.toDouble / total * 100
          (week, total, withAgent, rate, bucketCounts)
        }

        val totalStats       = summarize(weekEntries.map(_._2.toDouble))
        val agentStats       = summarize(weekEntries.map(_._3.toDouble))
        val rateStats        = summarize(weekEntries.map(_._4))
        val bucketCountStats = agentBuckets.map { bucket =>
          bucket -> summarize(weekEntries.map(_._5.getOrElse(bucket, 0).toDouble))
        }.toMap
        val bucketPctStats   = agentBuckets.map { bucket =>
          bucket -> summarize(weekEntries.map { case (_, total, _, _, bc) =>
            if total == 0 then 0.0 else bc.getOrElse(bucket, 0).toDouble / total * 100
          })
        }.toMap

        val dataRows = weekEntries.map { case (week, total, withAgent, rate, bucketCounts) =>
          Map(
            "week"                      -> week.toString,
            "commits_total"             -> total.toString,
            "commits_with_agent_signal" -> withAgent.toString,
            "agent_rate"                -> f"$rate%.1f"
          ) ++ agentBuckets.flatMap { agent =>
            val col   = agent.replace(" ", "_")
            val count = bucketCounts.getOrElse(agent, 0)
            Vector(s"${col}_count" -> count.toString, s"${col}_pct" -> fmtPct(count, total))
          }.toMap
        }

        val sepRow      = header.map(_ -> "").toMap
        val summaryRows = Vector("min", "max", "mean", "median", "stddev").map { stat =>
          Map(
            "week"                      -> stat,
            "commits_total"             -> f"${totalStats(stat)}%.1f",
            "commits_with_agent_signal" -> f"${agentStats(stat)}%.1f",
            "agent_rate"                -> f"${rateStats(stat)}%.1f"
          ) ++ agentBuckets.flatMap { agent =>
            val col = agent.replace(" ", "_")
            Vector(
              s"${col}_count" -> f"${bucketCountStats(agent)(stat)}%.1f",
              s"${col}_pct"   -> f"${bucketPctStats(agent)(stat)}%.1f"
            )
          }.toMap
        }

        val outputDir = GlobalPaths.outputPath.resolve("agent-signal-rate")
        writeCsv(outputDir.resolve(s"$developer.csv"), header, dataRows ++ Vector(sepRow) ++ summaryRows)
      }
  }

  @main def exportAgentHarnessDistribution(): Unit = {
    val agentCountsByDev: Map[String, Map[String, Int]] = taskTypeCounts
      .groupMapReduce((k, _) => k.developer)((k, v) => Map(k.agent -> v.values.sum)) { (a, b) =>
        (a.keySet ++ b.keySet).map { agent =>
          agent -> (a.getOrElse(agent, 0) + b.getOrElse(agent, 0))
        }.toMap
      }

    val allAgents = agentCountsByDev.values.flatMap(_.keys).toVector.distinct.sorted

    val header = "developer" +: "commits_total" +: allAgents.flatMap { agent =>
      val col = agent.replace(" ", "_")
      Vector(s"${col}_count", s"${col}_pct")
    }

    val rows = agentCountsByDev.toVector.sortBy(_._1).map { case (developer, agentCounts) =>
      val total = agentCounts.values.sum
      Map[String, String]("developer" -> developer, "commits_total" -> total.toString) ++
        allAgents.flatMap { agent =>
          val col   = agent.replace(" ", "_")
          val count = agentCounts.getOrElse(agent, 0)
          Vector(s"${col}_count" -> count.toString, s"${col}_pct" -> fmtPct(count, total))
        }.toMap
    }

    writeCsv(GlobalPaths.outputPath.resolve("agent-harness-distribution.csv"), header, rows)
  }
}
