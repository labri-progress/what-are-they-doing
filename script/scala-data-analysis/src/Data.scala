package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import whataretheydoing.DevData.*

given codecMapDayData: JsonValueCodec[Map[String, DayData]] = JsonCodecMaker.make

given codecMonthlySnapshot: JsonValueCodec[MonthlySnapshot] =
  JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true))

given codecCommitFiles: JsonValueCodec[List[CommitFile]] = JsonCodecMaker.make

given codecCommitDetail: JsonValueCodec[CommitDetail] = JsonCodecMaker.make

given codecHeuristicJson: JsonValueCodec[List[AgentHeuristic]] = JsonCodecMaker.make

given codecDeveloperEntry: JsonValueCodec[List[DevSummary]] = JsonCodecMaker.make

case class MonthlySnapshot(
    developer: String,
    month: String,
    repos: Option[List[String]],
    days: Map[String, DayData]
)

case class DayData(
    total_count: Int,
    sampled: Option[Int],
    pages: Option[List[Int]],
    commits: List[CommitEntry]
)

case class CommitEntry(
    sha: String,
    node_id: Option[String],
    commit: CommitInfo,
    url: String,
    html_url: Option[String],
    author: Option[GitHubUser],
    committer: Option[GitHubUser]
)

case class CommitInfo(
    author: GitAuthor,
    committer: GitAuthor,
    message: String,
    tree: Option[TreeRef],
    url: String,
    comment_count: Option[Int],
    verification: Option[Verification]
)

case class GitAuthor(
    name: String,
    email: String,
    date: String
)

case class TreeRef(
    sha: String,
    url: String
)

case class Verification(
    verified: Boolean,
    reason: String,
    signature: Option[String],
    payload: Option[String],
    verified_at: Option[String]
)

case class GitHubUser(
    login: String,
    id: Long,
    node_id: Option[String],
    avatar_url: Option[String],
    gravatar_id: Option[String],
    url: String,
    html_url: Option[String],
    `type`: String
)

// ── Data models for commit detail cache ───────────────────────────────────

case class CommitDetail(
    message: Option[String],
    files: Option[List[CommitFile]]
)

case class CommitFile(
    sha: Option[String],
    filename: String,
    status: String,
    additions: Int,
    deletions: Int,
    changes: Int
)

// ── Data models for heuristic JSON ───────────────────────────────────────

case class AgentHeuristic(
    author_names: List[String],
    author_mails: List[String],
    files: List[String],
    branch_name_prefix: List[String],
    commit_message_prefix: List[String],
    period_start: String,
    period_end: Option[String]
)



case class DevSummary(
    handle: String,
    repos: Option[List[String]] = None
)
