package whataretheydoing

import java.nio.file.Path

object GlobalPaths {
  val repoRoot: Path               = Path.of("").toAbsolutePath.normalize.getParent.getParent
  val dataPath: Path               = repoRoot.resolve("data")
  val heuristics: Path             = repoRoot.resolve("agent-mining/agents")
  val commitsPath: Path            = repoRoot.resolve("data/commits")
  val devFile: Path                = repoRoot.resolve("developers.json")
  val outputPath: Path             = repoRoot.resolve("figures").resolve("rq2")
  val contributionSummaryDir: Path = repoRoot.resolve("data/contribution-summaries")
}
