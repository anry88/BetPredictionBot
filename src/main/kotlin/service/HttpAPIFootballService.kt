@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package service

import FootballBot
import dto.LeagueConfig
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class HttpAPIFootballService(private val footballBot: FootballBot) {
    private val logger = LoggerFactory.getLogger(HttpAPIFootballService::class.java)
    private val apiKey: String = Config.getProperty("api-football.token") ?: throw IllegalStateException("API Key not found")
    private val channelId: String = Config.getProperty("channel.chat.id") ?: throw IllegalStateException("Channel ChatID not found")

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

    // Добавляем JSON-парсер
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Загружаем конфигурацию лиг из файла
    private val leaguesConfig: List<LeagueConfig> = loadLeaguesConfig()

    private fun loadLeaguesConfig(): List<LeagueConfig> {
        val leaguesJson = javaClass.getResource("/leagues.json")?.readText()
            ?: throw IllegalStateException("leagues.json not found")
        return json.decodeFromString<List<LeagueConfig>>(leaguesJson)
    }

    suspend fun fetchMatches() {
        val currentDate = LocalDate.now()
        val nextDay = currentDate.plusDays(1)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedCurrentDate = currentDate.format(formatter)
        val formattedNextDay = nextDay.format(formatter)

        leaguesConfig.forEach { leagueConfig ->
            val matches = getUpcomingMatches(leagueConfig.leagueId, leagueConfig.season, formattedCurrentDate, formattedNextDay)
            matches.forEach { match ->
                val fixtureId = match.fixture.id.toString()
                val leagueName = "${match.league.country} ${match.league.name}"

                // Парсим дату и время матча
                val isoDateTime = match.fixture.date // Оригинальная дата и время в ISO формате
                val parsedDateTime = OffsetDateTime.parse(isoDateTime) // Парсим ISO строку

                // Приводим к нужному формату
                val formatterMatchDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val datetime = parsedDateTime.format(formatterMatchDate) // Форматируем дату и время

                val teams = "${match.teams.home.name} vs. ${match.teams.away.name}"

                // Создаём объект MatchInfo перед вызовом matchExists
                val matchInfo = MatchInfo(
                    fixtureId = fixtureId,
                    datetime = datetime,
                    matchType = leagueName,
                    teams = teams,
                    predictedOutcome = null,
                    actualOutcome = null,
                    predictedScore = null,
                    actualScore = null,
                    odds = null,
                    telegramMessageId = null,
                    elapsed = null
                )

                // Проверяем, существует ли матч в базе данных
                if (!DatabaseService.matchExists(matchInfo)) {
                    // Вставляем матч в базу данных
                    DatabaseService.appendRows(listOf(matchInfo))

                    var prediction: MatchInfo? = null
                    val maxAttempts = 3
                    var attempts = 0

                    // Пытаемся получить предсказание до 3 раз
                    while (attempts < maxAttempts && prediction == null) {
                        attempts++
                        try {
                            prediction = ChatGPTService.getMatchPrediction(matchInfo)
                        } catch (e: Exception) {
                            logger.error("Error during ChatGPT prediction attempt $attempts: ${e.message}")
                        }

                        if (prediction != null) {
                            // Обновляем matchInfo предсказанием
                            matchInfo.predictedOutcome = prediction.predictedOutcome
                            matchInfo.predictedScore = prediction.predictedScore
                            matchInfo.odds = prediction.odds

                            // Обновляем базу данных предсказанием
                            DatabaseService.updateMatchPredictions(matchInfo)
                            logger.info("Prediction obtained for match ${matchInfo.teams} at ${matchInfo.datetime} after $attempts attempt(s)")
                        } else {
                            logger.warn("Attempt $attempts: Failed to get prediction for match ${matchInfo.teams} at ${matchInfo.datetime}")
                            if (attempts < maxAttempts) {
                                // Ожидаем перед следующей попыткой
                                Thread.sleep(1000) // Пауза между попытками
                            }
                        }
                    }

                    // Если после 3 попыток предсказание не удалось получить, удаляем матч из базы данных
                    if (prediction == null) {
                        DatabaseService.deleteMatchByFixtureId(matchInfo.fixtureId, matchInfo.matchType)
                        logger.error("Failed to get prediction for match ${matchInfo.teams} at ${matchInfo.datetime} after $attempts attempts. Match deleted from database.")
                    }
                } else {
                    DatabaseService.updateMatchDatetime(matchInfo)
                    logger.info("Duplicate match found: $teams at $datetime")
                }
            }
        }
    }


    suspend fun fetchPastMatches() {
        val currentDate = LocalDate.now()
        val previousDay = currentDate.minusDays(1)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedPreviousDay = previousDay.format(formatter)
        val formattedCurrentDate = currentDate.format(formatter)

        leaguesConfig.forEach { leagueConfig ->
            val matches = getPastMatches(leagueConfig.leagueId, leagueConfig.season, formattedPreviousDay, formattedCurrentDate)

            matches.forEach { match ->
                // Записываем победителя или ничью непосредственно в actualOutcome
                val actualOutcome = when {
                    match.teams.home.winner == true -> match.teams.home.name
                    match.teams.away.winner == true -> match.teams.away.name
                    else -> "Draw"
                }

                val actualScore = "${match.goals?.home ?: 0}:${match.goals?.away ?: 0}"

                // Получаем существующую запись матча из базы данных
                val existingMatchInfo = DatabaseService.getMatchInfo(match.fixture.id.toString(), "${match.league.country} ${match.league.name}")
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
                    telegramMessageId = existingMatchInfo?.telegramMessageId,
                    fixtureId = match.fixture.id.toString(),
                    elapsed = null
                )
                if (matchInfo.telegramMessageId != null) {
                    val updatedMessageText = footballBot.formatMatchInfoWithResult(matchInfo)
                    val messageId = matchInfo.telegramMessageId

                    if (messageId != null) {
                        footballBot.updateMessage(channelId, messageId, updatedMessageText)
                    }
                    else{
                        logger.error("Message wasn't updated because messageId is null")
                    }
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

    suspend fun getLiveMatchInfo(fixtureId: String): MatchInfo? {
        // Сначала получаем текущую информацию о матче из базы данных
        val existingMatchInfo = DatabaseService.getMatchInfoByFixtureId(fixtureId)
        if (existingMatchInfo == null) {
            logger.warn("Match with fixtureId $fixtureId not found in the database")
            return null
        }

        // Затем получаем актуальные данные о матче из API
        val response: HttpResponse = client.get(url) {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
            }
            parameter("id", fixtureId)
        }
        if (response.status == HttpStatusCode.OK) {
            val result = response.body<ApiFootballResponse>()
            val match = result.response.firstOrNull()
            if (match != null) {
                val actualScore = "${match.goals?.home ?: 0}:${match.goals?.away ?: 0}"
                val elapsed = match.fixture.status.elapsed ?: 0
                val statusShort = match.fixture.status.short
                val actualOutcome = if (statusShort == "FT" || statusShort == "AET" || statusShort == "PEN") {
                    when {
                        match.teams.home.winner == true -> match.teams.home.name
                        match.teams.away.winner == true -> match.teams.away.name
                        else -> "Draw"
                    }
                } else {
                    null
                }

                // Создаём обновлённый объект MatchInfo, обновляя только необходимые поля
                val updatedMatchInfo = existingMatchInfo.copy(
                    actualScore = actualScore,
                    actualOutcome = actualOutcome,
                    elapsed = elapsed
                )

                // Возвращаем обновлённый объект
                return updatedMatchInfo
            } else {
                logger.warn("No match data found for fixtureId $fixtureId")
                return null
            }
        } else {
            logger.error("Failed to fetch live match info for fixtureId $fixtureId. HTTP status: ${response.status}")
            return null
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
