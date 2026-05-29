package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import whataretheydoing.DataAnalysis.time

import java.nio.file.{Files, Path}
import java.time.{LocalDate, YearMonth}
import java.util.Locale
import scala.collection.parallel.immutable.ParVector
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

object CommitProcessing {

  private val trailerPattern: Regex = """^([a-zA-Z][a-zA-Z0-9_.-]+):\s+(.+)$""".r

  private def parseTrailers(message: String): Seq[(key: String, value: String)] =
    message.linesIterator.toVector
      .drop(1)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        line match
            case trailerPattern(key, value) => Some((key.nn.toLowerCase(Locale.ROOT).nn, value.nn))
            case _                          => None
      }

  private val conventionalCommitPattern =
    "^([a-z]+)(?:\\([^\\r\\n()]+\\))?(!)?:\\s+(.+)$".r

  private def conventionalCommitType(rawType: String): CommitType =
    rawType.toLowerCase match
        case "build"    => CommitType.Build
        case "chore"    => CommitType.Chore
        case "ci"       => CommitType.Ci
        case "docs"     => CommitType.Docs
        case "feat"     => CommitType.Feat
        case "fix"      => CommitType.Fix
        case "perf"     => CommitType.Perf
        case "refactor" => CommitType.Refactor
        case "revert"   => CommitType.Revert
        case "style"    => CommitType.Style
        case "test"     => CommitType.Test
        case _          => CommitType.Unknown

  private def startsWithAny(line: String, prefixes: String*): Boolean =
    prefixes.exists(line.startsWith)

  private def inferCommitTypeFromHeader(header: String): CommitType =
      val normalized = header.trim.toLowerCase
      if normalized.isEmpty then CommitType.Unknown
      // format: off
      else if startsWithAny(normalized, "feat", "add ", "implement ", "introduce ", "support ", "enable ", "allow ", "create ", "wire ", "integrate ", "expose ", "provide ", "initialize ", "bootstrap ", "accept ", "share ") then CommitType.Feat
      else if startsWithAny(normalized, "fix", "bugfix", "hotfix", "repair ", "resolve ", "correct ", "prevent ", "stabilize ", "hardening", "harden ", "security:", "security ") then CommitType.Fix
      else if startsWithAny(normalized, "perf", "optimize ", "optimise ", "speed up ", "reduce ", "benchmark", "cache ", "faster ", "lazy ") then CommitType.Perf
      else if startsWithAny(normalized, "refactor", "rename ", "move ", "extract ", "reorganize ", "reorganise ", "modularize ", "modularise ", "consolidate ", "simplify ", "streamline ", "cleanup", "clean up", "deduplicate ", "split ", "port ", "migrate ", "rewrite ", "rework ") then CommitType.Refactor
      else if startsWithAny(normalized, "docs", "doc:", "document ", "documentation", "readme", "guide", "tutorial", "blog", "changelog", "adr-", "spec", "planning", "plan ", "prompt:") then CommitType.Docs
      else if startsWithAny(normalized, "test", "tests", "e2e", "integration test", "unit test", "property", "conformance", "coverage", "smoke test", "test:") then CommitType.Test
      else if startsWithAny(normalized, "style", "format", "fmt", "lint", "lint:", "prettier", "rustfmt", "clang-format", "shfmt", "shellcheck", "typo", "whitespace") then CommitType.Style
      else if startsWithAny(normalized, "build", "bump ", "release", "publish ", "package", "packaging", "installer", "install ", "cargo", "cmake", "docker", "homebrew", "nix", "npm ", "pnpm ", "wasm build", "binary", "artifact") then CommitType.Build
      else if startsWithAny(normalized, "ci ", "ci:", "github actions", "workflow", "workflows", "pipeline", "buildkite", "lint/test") then CommitType.Ci
      else if startsWithAny(normalized, "revert") then CommitType.Revert
      else if startsWithAny(normalized, "merge ", "sync ", "track ", "checkpoint", "wip", "tmp", "oops", "updates", "update ", "adjust ", "tweak ", "tune ", "polish ", "note ", "use ", "switch ", "set ", "bake ", "prepare ", "release ") then CommitType.Chore
      // format: on
      else CommitType.Unknown

  def classifyCommitMessage(message: String): CommitType =
    message.linesIterator.nextOption() match
        case Some(conventionalCommitPattern(rawType, _, _)) => conventionalCommitType(rawType.nn)
        case Some(header)                                   => inferCommitTypeFromHeader(header)
        case _                                              => CommitType.Unknown

  private def loadFullCommitData(commit: CommitEntry): CommitDetail =
      val detailFile = GlobalPaths.commitsPath.resolve(s"${commit.sha}.json")
      val detail     =
        if Files.exists(detailFile) then
            val bytes = Files.readAllBytes(detailFile)
            try readFromArray[CommitDetail](bytes)
            catch
                case _: Exception =>
                  val files = readFromArray[List[CommitFile]](bytes)
                  CommitDetail(message = Some(commit.commit.message), files = files)
        else CommitDetail(message = None, files = Nil)
      detail.copy(message = Some(detail.message.getOrElse(commit.commit.message)))

  private def classifyCommit(
      commit: CommitEntry,
      detail: CommitDetail,
  ): ClassifiedCommit =
      val message      = detail.message.get
      val trailers     = parseTrailers(message)
      val agentSignals = HeuristicMatcher.detectAgents(commit, detail, trailers)
      ClassifiedCommit(
        agentSignals = agentSignals,
        commitType = classifyCommitMessage(message),
        message = message,
        files = detail.files.map(_.filename),
        trailers = trailers
      )

  lazy val aggregateCommitData: Vector[(dev: String, month: YearMonth, path: Path, data: MonthlySnapshot)] =
    time("loading aggregate data") {
      val jsonFiles = Using(Files.list(GlobalPaths.dataPath)) {
        _.iterator().asScala.filter { path =>
          Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json")
        }.toVector
      }.get

      jsonFiles.map { path =>
        val snapshot = readFromArray[MonthlySnapshot](Files.readAllBytes(path))
        (snapshot.developer, YearMonth.parse(snapshot.month), path, snapshot)
      }
    }

  def allCommits: Iterator[CommitEntry] =
    aggregateCommitData.iterator.flatMap(_.data.days.valuesIterator.flatMap(_.commits))

  lazy val allDays: Seq[LocalDate] = aggregateCommitData.flatMap(_.data.days.keysIterator)

  lazy val allCommitDetails: ParVector[(commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] = {
    DataAnalysis.time("load commit details") {
      allCommits.to(ParVector).map { commit =>
        val detail         = loadFullCommitData(commit)
        val classification = classifyCommit(commit, detail)
        (commit = commit, detail = detail, classification = classification)
      }
    }
  }

  lazy val allCommitDetailsBySha1
      : Map[String, (commit: CommitEntry, detail: CommitDetail, classification: ClassifiedCommit)] =
    allCommitDetails.iterator.map(det => det.commit.sha -> det).toMap

  def commitSignals(sha: String): Option[ClassifiedCommit] =
    allCommitDetailsBySha1.get(sha).map(_.classification)

}
