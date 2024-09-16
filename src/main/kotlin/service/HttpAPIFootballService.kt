package service

import DatabaseService.matchExists
import FootballBot
import dto.MatchInfo
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

class HttpAPIFootballService(private val footballBot: FootballBot) {
    private val logger = LoggerFactory.getLogger(HttpAPIFootballService::class.java)
    private val apiKey: String = Config.getProperty("api-football.token") ?: throw IllegalStateException("API Key not found")
    private val channelId: String = Config.getProperty("channel.chat.id") ?: throw IllegalStateException("Channel ChatID not found")
    private val popularLeagues = listOf(
        39, 140, 135, 78, 61,   // Премьер-лига, Ла Лига, Серия А, Бундеслига, Лига 1
        88, 333, 94, 235, 10,   // Эредивизи, Премьер-лига Украины, Примейра-лига, Российская Премьер Лига, Дружеские
        2, 3, 5, 32, 34, 848,   // Лига наций, Квалификации ЧМ, Лига чемпионов, Лига Европы, Лига конференций
        203, 253, 307           // Турецкая Супер лига, США МЛС, Про Лига Саудовской Аравии
    )
    private val url = "https://api-football-v1.p.rapidapi.com/v3/fixtures"
//    private val url = "http://localhost:1080/v3/fixtures"

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
        val nextDay = currentDate.plusDays(1)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedCurrentDate = currentDate.format(formatter)
        val formattedNextDay = nextDay.format(formatter)

        popularLeagues.forEach { leagueId ->
            val matches = getUpcomingMatches(leagueId, 2024, formattedCurrentDate, formattedNextDay)
            val remainingRequests = matches.firstOrNull()?.remainingRequests ?: "Unknown"

            logger.info("Found ${matches.size} matches for league ID $leagueId between $formattedCurrentDate and $formattedNextDay")
            logger.info("Remaining API calls: $remainingRequests")
            matches.forEach { match ->
                logger.info("Match found: ${match.teams.home.name} vs ${match.teams.away.name} on ${match.fixture.date}, League: ${match.league.name}")
            }

            if (matches.isNotEmpty()) {
                // Разбиваем матчи на пачки по 10 штук и отправляем их последовательно
                matches.chunked(10).forEach { matchChunk ->
                    val matchesText = matchChunk.joinToString(separator = "\n") { match ->
                        "[Match Start UTC]: [${match.fixture.date}] [Match Type]: [${match.league.country} ${match.league.name}] [Teams]: [${match.teams.home.name} vs. ${match.teams.away.name}]"
                    }

                    val predictions = ChatGPTService.getMatchPredictionsWithRetry(matchesText)
                    val newMatches = mutableListOf<MatchInfo>()

                    predictions.forEach { prediction ->
                        if (!matchExists(prediction)) {
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
                }
            } else {
                logger.error("No matches found for the specified dates for league ID $leagueId.")
            }
        }
    }

    suspend fun fetchPastMatches() {
        val currentDate = LocalDate.now()
        val previousDay = currentDate.minusDays(1)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedPreviousDay = previousDay.format(formatter)
        val formattedCurrentDate = currentDate.format(formatter)

        popularLeagues.forEach { leagueId ->
            val matches = getPastMatches(leagueId, 2024, formattedPreviousDay, formattedCurrentDate)

            matches.forEach { match ->
                // Записываем победителя или ничью непосредственно в actualOutcome
                val actualOutcome = when {
                    match.teams.home.winner == true -> match.teams.home.name
                    match.teams.away.winner == true -> match.teams.away.name
                    else -> "Draw"
                }

                val actualScore = "${match.goals?.home ?: 0}:${match.goals?.away ?: 0}"

                // Получаем существующую запись матча из базы данных
                val existingMatchInfo = DatabaseService.getMatchInfo(match.fixture.date, "${match.teams.home.name} vs. ${match.teams.away.name}", "${match.league.country} ${match.league.name}")
                // Создаем новый экземпляр MatchInfo, сохраняя прогнозы и обновляя реальные результаты
                val matchInfo = existingMatchInfo?.copy(
                    actualOutcome = actualOutcome,
                    actualScore = actualScore
                ) ?: MatchInfo(
                    datetime = match.fixture.date,
                    matchType = "${match.league.country} ${match.league.name}",
                    teams = "${match.teams.home.name} vs. ${match.teams.away.name}",
                    predictedOutcome = existingMatchInfo?.predictedOutcome,  // Сохранение существующего значения
                    actualOutcome = actualOutcome,
                    predictedScore = existingMatchInfo?.predictedScore,      // Сохранение существующего значения
                    actualScore = actualScore,
                    odds = existingMatchInfo?.odds,                          // Сохранение существующего значения
                    telegramMessageId = existingMatchInfo?.telegramMessageId
                )
                if (matchInfo.telegramMessageId != null) {
                    val updatedMessageText = footballBot.formatMatchInfoWithResult(matchInfo)

                    footballBot.updateMessage(channelId, matchInfo.telegramMessageId, updatedMessageText)
                }

                // Обновляем запись в базе данных
                DatabaseService.updateMatchResult(matchInfo)
                Thread.sleep(10000)
            }
        }
    }


    private suspend fun getUpcomingMatches(leagueId: Int, season: Int, fromDate: String, toDate: String): List<Match> {
        val response: HttpResponse = client.get(url) {
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

    private suspend fun getPastMatches(leagueId: Int, season: Int, fromDate: String, toDate: String): List<Match> {
        val response: HttpResponse = client.get(url) {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
            }
            parameter("league", leagueId)
            parameter("season", season)
            parameter("from", fromDate)
            parameter("to", toDate)
            parameter("status", "FT") // "FT" означает завершённый матч
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

    @Serializable
    data class ApiFootballResponse(val response: List<Match>)

    @Serializable
    data class Match(
        val fixture: Fixture,
        val league: League,
        val teams: Teams,
        val goals: Goals?,
        val odds: Odds? = null,
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
    data class Odds(
        val homeWin: Double?,
        val draw: Double?,
        val awayWin: Double?
    )

}
