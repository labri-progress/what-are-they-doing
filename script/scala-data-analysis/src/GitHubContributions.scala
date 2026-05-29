package whataretheydoing

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.nio.file.Path
import whataretheydoing.given

object GitHubContributions {

  given codecGraphQLResponse: JsonValueCodec[GraphQLResponse] = JsonCodecMaker.make
  given codecGraphQLQuery: JsonValueCodec[GraphQLQuery]       = JsonCodecMaker.make

  val httpClient: HttpClient = HttpClient.newHttpClient()
  val githubGraphQLEndpoint  = "https://api.github.com/graphql"
  val summaryDir: Path       = GlobalPaths.repoRoot.resolve("data/contribution-summaries")

  private def getContributions(
      token: String,
      username: String,
      fromDate: String,
      toDate: String
  ): List[ContributionDay] = {

    val query =
      s"""query {
         |  user(login: "$username") {
         |    contributionsCollection(from: "$fromDate", to: "$toDate") {
         |      contributionCalendar {
         |        totalContributions
         |        weeks {
         |          contributionDays {
         |            date
         |            contributionCount
         |          }
         |        }
         |      }
         |    }
         |  }
         |}""".stripMargin

    val requestBody = writeToString(GraphQLQuery(query))

    val request = HttpRequest.newBuilder()
      .uri(URI.create(githubGraphQLEndpoint))
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if response.statusCode() != 200 then
        throw new RuntimeException(s"GraphQL request failed with status ${response.statusCode()}: ${response.body()}")

    val parsed = readFromString[GraphQLResponse](response.body())

    parsed.data
      .flatMap(_.user)
      .map(_.contributionsCollection.contributionCalendar.weeks)
      .getOrElse(Nil)
      .flatMap(_.contributionDays)
  }

  @main def fetchContributions(token: String): Unit = {
    val fromDate = "2025-09-01T00:00:00Z"
    val toDate   = "2026-04-30T23:59:59Z"

    Files.createDirectories(summaryDir)

    DataAnalysis.trackedHandles.toVector.sorted.foreach { handle =>
      println(s"Fetching contributions for @$handle ...")
      try
          val contributions = getContributions(token, handle, fromDate, toDate)
          val summary       = DeveloperContributionSummary(
            developer = handle,
            fromDate = fromDate,
            toDate = toDate,
            contributions = contributions
          )
          val outPath = summaryDir.resolve(s"$handle.json")
          Files.writeString(outPath, writeToString(summary))
          println(s"  Wrote ${contributions.size} days to $outPath")
      catch
          case e: Exception =>
            System.err.println(s"  Failed for @$handle: ${e.getMessage}")
    }
  }
}
