package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*

import java.nio.file.{Files, Path}
import java.time.{LocalDate, YearMonth}
import scala.collection.parallel.immutable.{ParMap, ParVector}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object DevData {

// ── CSV output ─────────────────────────────────────────────────────────────

  inline def time[A](label: String)(inline body: A): A = {
    val start  = System.nanoTime()
    val result = body
    val end    = System.nanoTime()
    println(s"$label: ${(end - start) / 1000000.0} ms")
    result
  }

  val granularity       = "auto"
  val repoRoot: Path    = Path.of("").toAbsolutePath.normalize.getParent.getParent
  val dataPath: Path    = repoRoot.resolve("data")
  val heuristics: Path  = repoRoot.resolve("agent-mining/agents")
  val commitsPath: Path = repoRoot.resolve("data/commits")
  val devFile: Path     = repoRoot.resolve("developers.json")
  val outputPath: Path  = repoRoot.resolve("figures")
  val startDate         = ""
  val endDate           = ""

  // Load developers
  lazy val developers: List[DevSummary] = time("load devs"):
      val developersJson = Files.readAllBytes(devFile)
      readFromArray[List[DevSummary]](developersJson)

  lazy val trackedHandles: Set[String] = developers.map(_.handle).toSet

  lazy val heuristicsByAgent: Map[String, List[AgentHeuristic]] =
    time("load heuristics")(HeuristicMatcher.loadHeuristics(heuristics))

  lazy val aggregateData: Vector[(dev: String, month: YearMonth, path: Path, data: MonthlySnapshot)] =
    time("loading aggregate data") {
      val snapshotPattern = raw"(.+)-(\d{4}-\d{2})".r
      val jsonsFiles      = Using(Files.list(dataPath)) {
        _.iterator().asScala.flatMap { path =>
          if Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json")
          then
              snapshotPattern.findFirstMatchIn(path.getFileName.toString.stripSuffix(".json")) match {
                case Some(m) =>
                  Some((developer = m.group(1).nn, month = YearMonth.parse(m.group(2)).nn, path = path))
                case _ => None
              }
          else None
        }.toVector
      }.get

      jsonsFiles.flatMap { (dev, month, path) =>
        if trackedHandles.contains(dev) then
            Some((dev, month, path, readFromArray[MonthlySnapshot](Files.readAllBytes(path))))
        else None
      }.toVector
    }

  def allCommits = aggregateData.iterator.flatMap(_.data.days.valuesIterator.flatMap(_.commits))

  lazy val commitSignals: ParMap[String, Set[String]] = time("compute commit signals") {
    allCommits.to(ParVector).map { c => (c.sha, getCommitDetails(c)) }.toMap
  }

  lazy val allDays: Seq[LocalDate] = aggregateData.flatMap(_.data.days.keysIterator.map(LocalDate.parse(_).nn))

  private def getCommitDetails(commit: CommitEntry): Set[String] = {
    val detailFile                   = commitsPath.resolve(s"${commit.sha}.json")
    val (detailMessage, detailFiles) =
      if Files.exists(detailFile) then
          val bytes = Files.readAllBytes(detailFile)
          // Try new format {message, files} first, fall back to old bare array format
          val detail = try
              readFromArray[CommitDetail](bytes)
          catch
              case _: Exception =>
                val files = readFromArray[List[CommitFile]](bytes)
                CommitDetail(message = Some(commit.commit.message), files = Some(files))
          (
            detail.message.getOrElse(commit.commit.message),
            detail.files.getOrElse(Nil).map(_.filename)
          )
      else
          (commit.commit.message, Nil)

    val author       = commit.commit.author
    val commitAuthor = s"${author.name} <${author.email}>"
    HeuristicMatcher.detectAgents(
      detailMessage,
      commitAuthor,
      detailFiles,
      heuristicsByAgent
    )
  }

  @main def run(): Unit = {

    println(s"Running with dataDir=${dataPath.toString} granularity=$granularity")

    Files.createDirectories(outputPath)

    println(s"Tracked developers: ${trackedHandles.mkString(", ")}")

    // Load heuristics
    println(s"Loaded ${heuristicsByAgent.size} agent definitions")

  }

}
