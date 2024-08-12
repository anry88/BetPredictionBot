package service

import dto.MatchInfo
import kotlinx.coroutines.runBlocking
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HttpAPIFootballService {

    private val logger = LoggerFactory.getLogger(HttpAPIFootballService::class.java)
    private val apiKey: String = Config.getProperty("api-football.token") ?: throw IllegalStateException("API Key not found")

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun fetchMatches() {
        val currentDate = LocalDate.now()
        val nextDay = currentDate.plusDays(2)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedCurrentDate = currentDate.format(formatter)
        val formattedNextDay = nextDay.format(formatter)

        val popularLeagues = listOf(
            39, 140, 135, 78, 61,   // Премьер-лига, Ла Лига, Серия А, Бундеслига, Лига 1
            88, 99, 94, 129, 235,       // Эредивизи (Нидерланды), Премьер-лига Украины, Примейра-лига (Португалия), Бундеслига Австрии, Российская Премьер Лига
            2, 3, 5, 6, 7, 8        // Лига наций, Квалификации Евро и ЧМ, Лига чемпионов, Лига Европы, Лига конференций
        )

        val allMatches = mutableListOf<Match>()

        popularLeagues.forEach { leagueId ->
            val matches = getUpcomingMatches(leagueId, 2024, formattedCurrentDate, formattedNextDay)
            val remainingRequests = matches.firstOrNull()?.remainingRequests ?: "Unknown"

            logger.info("Found ${matches.size} matches for league ID $leagueId between $formattedCurrentDate and $formattedNextDay")
            logger.info("Remaining API calls: $remainingRequests")
            matches.forEach { match ->
                logger.info("Match found: ${match.teams.home.name} vs ${match.teams.away.name} on ${match.fixture.date}, League: ${match.league.name}")
            }
            allMatches.addAll(matches)
        }

        if (allMatches.isNotEmpty()) {
            logger.info("Successfully fetched a total of ${allMatches.size} matches across all leagues.")
            val matchesText = allMatches.joinToString(separator = "\n") { match ->
                "[Match Start UTC]: [${match.fixture.date}] [Match Type]: [${match.league.name}] [Teams]: [${match.teams.home.name} vs ${match.teams.away.name}]"
            }

            val predictions = ChatGPTService.getMatchPredictionsWithRetry(matchesText)
            val newMatches = mutableListOf<MatchInfo>()

            predictions.forEach { prediction ->
                logger.info("Prediction: $prediction")
                if (!isMatchInDatabase(prediction)) {
                    newMatches.add(prediction)
                } else {
                    logger.info("Duplicate match found: ${prediction.teams} at ${prediction.datetime}")
                }
            }

            if (newMatches.isNotEmpty()) {
                DatabaseService.appendRows(newMatches)
                logger.info("New matches appended to database: ${newMatches.size} matches added.")
            } else {
                logger.info("All matches are duplicates, no new matches to append.")
            }
        } else {
            logger.error("No matches found for the specified dates.")
        }
    }

    private suspend fun getUpcomingMatches(leagueId: Int, season: Int, fromDate: String, toDate: String): List<Match> {
        val response: HttpResponse = client.get("https://api-football-v1.p.rapidapi.com/v3/fixtures") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
            }
            parameter("league", leagueId)
            parameter("season", season)
            parameter("from", fromDate)
            parameter("to", toDate)
        }

        val remainingRequests = response.headers["X-RateLimit-requests-Remaining"] ?: "Unknown"
        logger.info("Remaining API calls after request: $remainingRequests")

        return if (response.status == HttpStatusCode.OK) {
            val result = response.body<ApiFootballResponse>()
            result.response
        } else {
            emptyList()
        }
    }

    private fun isMatchInDatabase(matchInfo: MatchInfo): Boolean {
        val upcomingMatches = DatabaseService.getUpcomingMatches()
        val isDuplicate = upcomingMatches.any { it.teams == matchInfo.teams && it.datetime == matchInfo.datetime }
        logger.info("Checking match: ${matchInfo.teams} at ${matchInfo.datetime}, isDuplicate: $isDuplicate")
        return isDuplicate
    }

    @Serializable
    data class ApiFootballResponse(val response: List<Match>)

    @Serializable
    data class Match(
        val fixture: Fixture,
        val league: League,
        val teams: Teams,
        val goals: Goals?,
        val score: Score?,
        val remainingRequests: String? = null
    )

    @Serializable
    data class Fixture(
        val id: Int,
        val referee: String?,
        val timezone: String,
        val date: String,
        val timestamp: Long,
        val venue: Venue,
        val status: Status
    )

    @Serializable
    data class Venue(
        val id: Int?,
        val name: String,
        val city: String
    )

    @Serializable
    data class Status(
        val long: String,
        val short: String,
        val elapsed: Int?
    )

    @Serializable
    data class League(
        val id: Int,
        val name: String,
        val country: String,
        val logo: String?,
        val flag: String?,
        val season: Int,
        val round: String
    )

    @Serializable
    data class Teams(
        val home: Team,
        val away: Team
    )

    @Serializable
    data class Team(
        val id: Int,
        val name: String,
        val logo: String?,
        val winner: Boolean?
    )

    @Serializable
    data class Goals(
        val home: Int?,
        val away: Int?
    )

    @Serializable
    data class Score(
        val halftime: ScoreDetail?,
        val fulltime: ScoreDetail?,
        val extratime: ScoreDetail?,
        val penalty: ScoreDetail?
    )

    @Serializable
    data class ScoreDetail(
        val home: Int?,
        val away: Int?
    )
}
