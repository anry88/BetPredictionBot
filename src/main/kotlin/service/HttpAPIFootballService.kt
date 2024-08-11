package service

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

    suspend fun getUpcomingMatches(leagueId: Int, season: Int, nextMatches: Int): List<Match> {
        val response: HttpResponse = client.get("https://api-football-v1.p.rapidapi.com/v3/fixtures") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
            }
            parameter("league", leagueId)
            parameter("season", season)
            parameter("next", nextMatches)
        }

        // Получаем количество оставшихся бесплатных запросов
        val remainingRequests = response.headers["X-RateLimit-requests-Remaining"]
        logger.info("Remaining free requests: $remainingRequests")

        return if (response.status == HttpStatusCode.OK) {
            val result = response.body<ApiFootballResponse>()
            result.response.map { it.toMatch() }
        } else {
            emptyList()
        }
    }

    suspend fun getLeagueInfo(leagueId: Int): League {
        val response: HttpResponse = client.get("https://api-football-v1.p.rapidapi.com/v3/leagues") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
            }
            parameter("id", leagueId)
        }

        // Получаем количество оставшихся бесплатных запросов
        val remainingRequests = response.headers["X-RateLimit-requests-Remaining"]
        logger.info("Remaining free requests: $remainingRequests")

        return if (response.status == HttpStatusCode.OK) {
            val result = response.body<ApiFootballResponse>()
            result.response.first().league
        } else {
            throw Exception("Failed to fetch league info")
        }
    }

    @Serializable
    data class ApiFootballResponse(val response: List<FixtureResponse>)

    @Serializable
    data class FixtureResponse(val fixture: Fixture, val league: League, val teams: Teams, val goals: Goals)

    @Serializable
    data class Fixture(val id: Int, val date: String)

    @Serializable
    data class League(val id: Int, val name: String)

    @Serializable
    data class Teams(val home: Team, val away: Team)

    @Serializable
    data class Team(val id: Int, val name: String)

    @Serializable
    data class Goals(val home: Int?, val away: Int?)

    data class Match(val id: Int, val date: String, val homeTeam: String, val awayTeam: String, val homeGoals: Int?, val awayGoals: Int?)

    data class LeagueInfo(val id: Int, val name: String)

    private fun FixtureResponse.toMatch(): Match {
        return Match(
            id = this.fixture.id,
            date = this.fixture.date,
            homeTeam = this.teams.home.name,
            awayTeam = this.teams.away.name,
            homeGoals = this.goals.home,
            awayGoals = this.goals.away
        )
    }
}
