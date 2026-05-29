package whataretheydoing

import whataretheydoing.HeuristicMatcher.SignalType

enum CommitType:
    case Build, Chore, Ci, Docs, Feat, Fix, Perf, Refactor, Revert, Style, Test, Unknown

case class ClassifiedCommit(
    agentSignals: Map[String, Set[SignalType]],
    commitType: CommitType,
    message: String,
    files: List[String],
    trailers: Seq[(key: String, value: String)]
) {
  def agents: Set[String] = agentSignals.keySet
}

case class PeriodCsvRow(
    developer: String,
    period_iso: String,
    total_commits: Int,
    sampled_commits: Int,
    agent: String,
    count: Int
)

case class AgentPeriodRow(
    periodIso: String,
    totalCommits: Int,
    sampledCommits: Int,
    countsByAgent: Map[String, Int]
)

case class TimeSeriesData(
    points: Vector[SVGGraphLib.StackedBarPoint],
    totalCommits: Vector[Int],
    sampledCommits: Vector[Int]
)

case class LinesChangedStats(
    count: Int,
    min: Int,
    q1: Double,
    median: Double,
    q3: Double,
    max: Int,
    mean: Double,
    standardDeviation: Double,
    whiskerLow: Int,
    whiskerHigh: Int,
    outliers: Vector[Int]
)
