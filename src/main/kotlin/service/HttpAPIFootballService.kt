@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package service

import FootballBot
import dto.BookmakerInfo
import dto.LeagueConfig
import dto.MatchInfo
import dto.OddsInfo
import dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HttpAPIFootballService(private val footballBot: FootballBot) {
    private val logger = LoggerFactory.getLogger(HttpAPIFootballService::class.java)
    private val apiKey: String = Config.getProperty("api-football.token") ?: throw IllegalStateException("API Key not found")

    private val bookmakers = listOf(
        BookmakerInfo(16, "Unibet"),
        BookmakerInfo(8, "Bet365"),
        BookmakerInfo(7, "William Hill"),
        BookmakerInfo(11, "1xBet"),
        BookmakerInfo(2, "Marathonbet"),
        BookmakerInfo(27, "NordicBet")
        // Add more bookmakers as needed
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

    // Добавляем JSON-парсер
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class FixtureInfo(
        val statusShort: String?,
        val datetime: String,
        val homeTeam: String,
        val awayTeam: String
    )

    // Загружаем конфигурацию лиг из файла
    private val leaguesConfig: List<LeagueConfig> = loadLeaguesConfig()

    private fun loadLeaguesConfig(): List<LeagueConfig> {
        val leaguesJson = javaClass.getResource("/leagues.json")?.readText()
            ?: throw IllegalStateException("leagues.json not found")
        return json.decodeFromString(leaguesJson)
    }

    fun getModelBasedLeaguesFromConfig(): List<LeagueConfig> {
        return leaguesConfig.filter { it.premiumSelection }
    }

    suspend fun fetchMatches() {
        val currentDate = LocalDate.now()
        val twoDaysAhead = currentDate.plusDays(1)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedCurrentDate = currentDate.format(formatter)
        val formattedNextDay = twoDaysAhead.format(formatter)

        val chatGptMaxAttempts = 10

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

                val homeTeamName = match.teams.home.name
                val awayTeamName = match.teams.away.name
                val teams = "$homeTeamName vs. $awayTeamName"

                val homeMatchesCount = DatabaseService.matches.getTeamMatchesCountLastTwoYears(homeTeamName)
                val awayMatchesCount = DatabaseService.matches.getTeamMatchesCountLastTwoYears(awayTeamName)

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
                    elapsed = null,
                    strategyTelegramMessageId = null,
                    bookmakerName = null,
                    homeWinOdds = null,
                    drawOdds = null,
                    awayWinOdds = null,
                    modelHomeWinProb = null,
                    modelDrawProb = null,
                    modelAwayWinProb = null,
                    modelExpectedHomeGoals = null,
                    modelExpectedAwayGoals = null,
                    homeMatchesLastTwoYears = homeMatchesCount,
                    awayMatchesLastTwoYears = awayMatchesCount
                )

                // Проверяем, существует ли матч в базе данных
                if (!DatabaseService.matches.matchExists(matchInfo)) {
                    // Вставляем матч в базу данных
                    DatabaseService.matches.appendRows(listOf(matchInfo))

                    // Получаем прогноз
                    val homeTeam = match.teams.home.name
                    val awayTeam = match.teams.away.name

                    var finalPrediction: MatchInfo? = null

                    finalPrediction = HttpLocalModelService.getModelPrediction(
                        homeTeam = homeTeam,
                        awayTeam = awayTeam,
                        matchInfo = matchInfo
                    )
                    // Если локальная модель вернула null, fallback на ChatGPT
                    if (finalPrediction == null) {
                        var attempts = 0
                        while (attempts < chatGptMaxAttempts && finalPrediction == null) {
                            attempts++
                            try {
                                finalPrediction = ChatGPTService.getMatchPrediction(matchInfo)
                            } catch (e: Exception) {
                                logger.error("ChatGPT error on attempt #$attempts: ${e.message}")
                            }
                            if (finalPrediction == null) {
                                logger.warn("ChatGPT attempt #$attempts failed, will retry...")
                            }
                        }
                    }

                    // Если и ChatGPT не смог (finalPrediction = null), удаляем матч
                    if (finalPrediction == null) {
                        DatabaseService.matches.deleteMatchByFixtureId(matchInfo.fixtureId, matchInfo.matchType)
                        logger.error("Failed to get any prediction for $teams; match removed from DB.")
                    } else {
                        // Обновляем базу
                        matchInfo.predictedOutcome = finalPrediction.predictedOutcome
                        matchInfo.predictedScore = finalPrediction.predictedScore
                        matchInfo.odds = finalPrediction.odds
                        matchInfo.modelHomeWinProb = finalPrediction.modelHomeWinProb
                        matchInfo.modelDrawProb = finalPrediction.modelDrawProb
                        matchInfo.modelAwayWinProb = finalPrediction.modelAwayWinProb
                        matchInfo.modelExpectedHomeGoals = finalPrediction.modelExpectedHomeGoals
                        matchInfo.modelExpectedAwayGoals = finalPrediction.modelExpectedAwayGoals
                        DatabaseService.matches.updateMatchPredictions(matchInfo)

                        val matchDateTime = LocalDateTime.parse(matchInfo.datetime, formatterMatchDate)
                        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
                        if (matchDateTime.isBefore(now.plusHours(28))) {
                            val oddsInfo = getOddsForFixture(
                                matchInfo.fixtureId,
                                matchInfo.predictedOutcome ?: "",
                                homeTeam,
                                awayTeam
                            )
                            if (oddsInfo != null) {
                                matchInfo.odds = oddsInfo.odds.toString()
                                matchInfo.bookmakerName = oddsInfo.bookmakerName
                                matchInfo.homeWinOdds = oddsInfo.homeWinOdds?.toString()
                                matchInfo.drawOdds = oddsInfo.drawOdds?.toString()
                                matchInfo.awayWinOdds = oddsInfo.awayWinOdds?.toString()
                                DatabaseService.matches.updateMatchOdds(matchInfo)
                            }
                        }
                    }
                } else {
                    DatabaseService.matches.updateMatchDatetime(matchInfo)
                    logger.info("Duplicate match found: $teams at $datetime")

                    val existingMatch = DatabaseService.matches.getMatchInfoByFixtureId(matchInfo.fixtureId)
                    if (existingMatch != null) {
                        val matchDateTime = LocalDateTime.parse(existingMatch.datetime, formatterMatchDate)
                        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
                        val needsOdds = existingMatch.homeWinOdds == null ||
                                existingMatch.drawOdds == null ||
                                existingMatch.awayWinOdds == null

                        if (matchDateTime.isBefore(now.plusDays(1)) && needsOdds) {
                            val oddsInfo = getOddsForFixture(
                                existingMatch.fixtureId,
                                existingMatch.predictedOutcome ?: "",
                                match.teams.home.name,
                                match.teams.away.name
                            )
                            if (oddsInfo != null) {
                                val updatedMatch = existingMatch.copy(
                                    odds = oddsInfo.odds.toString(),
                                    bookmakerName = oddsInfo.bookmakerName,
                                    homeWinOdds = oddsInfo.homeWinOdds?.toString(),
                                    drawOdds = oddsInfo.drawOdds?.toString(),
                                    awayWinOdds = oddsInfo.awayWinOdds?.toString()
                                )
                                DatabaseService.matches.updateMatchOdds(updatedMatch)
                            }
                        }
                    }
                }
                if (match.fixture.status?.short == "CANC"){
                    DatabaseService.matches.deleteMatchByFixtureId(matchInfo.fixtureId, matchInfo.matchType)
                }
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

    suspend fun getPastMatches(leagueId: Int, season: Int, fromDate: String, toDate: String): List<Match> {
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

    suspend fun updatePastMatches() {
        val currentDate = LocalDate.now()
        val oneDayAgo = currentDate.minusDays(1)

        val messagesToUpdate = mutableMapOf<Pair<String?, String?>, MatchInfo>()

        val matches = DatabaseService.matches.getMatchesFromLastDaysWithoutResult(2)
        for (match in matches) {
            val updatedMatchInfo = getLiveMatchInfo(match.fixtureId)
            if (updatedMatchInfo != null && updatedMatchInfo.actualOutcome != null) {
                DatabaseService.matches.updateMatchResult(updatedMatchInfo)
                val key = updatedMatchInfo.telegramMessageId to updatedMatchInfo.strategyTelegramMessageId
                if (key.first != null || key.second != null) {
                    messagesToUpdate.putIfAbsent(key, updatedMatchInfo)
                }
            }
            delay(1000)
        }

        for ((_, info) in messagesToUpdate) {
            footballBot.updateMatchMessages(info)
            delay(10000)
        }

        // Delete matches older than one day with no actual result
        val matchesToDelete = DatabaseService.matches.getMatchesOlderThanOneDayWithoutResult(oneDayAgo)
        val messagesMap = mutableSetOf<Pair<String, String>>()
        val strategyMap = mutableSetOf<Pair<String, String>>()

        matchesToDelete.forEach { matchInfo ->
            DatabaseService.matches.deleteMatchByFixtureId(matchInfo.fixtureId, matchInfo.matchType)

            matchInfo.telegramMessageId?.let { id ->
                messagesMap.add(matchInfo.matchType to id)
            }
            matchInfo.strategyTelegramMessageId?.let { id ->
                strategyMap.add(matchInfo.matchType to id)
            }
        }

        for ((league, msgId) in messagesMap) {
            val remaining = DatabaseService.matches.getMatchesByLeagueAndTelegramMessageId(league, msgId)
            if (remaining.isEmpty()) {
                footballBot.deleteMatchMessages(MatchInfo(
                    fixtureId = "", datetime = "", matchType = league, teams = "", predictedOutcome = null,
                    actualOutcome = null, predictedScore = null, actualScore = null, odds = null,
                    bookmakerName = null, homeWinOdds = null, drawOdds = null, awayWinOdds = null,
                    telegramMessageId = msgId, strategyTelegramMessageId = null, elapsed = null,
                    modelHomeWinProb = null, modelDrawProb = null, modelAwayWinProb = null,
                    modelExpectedHomeGoals = null, modelExpectedAwayGoals = null
                ))
            } else {
                footballBot.updateMatchMessages(remaining.first())
            }
        }

        for ((league, msgId) in strategyMap) {
            val remaining = DatabaseService.matches.getMatchesByLeagueAndStrategyMessageId(league, msgId)
            if (remaining.isEmpty()) {
                footballBot.deleteMatchMessages(MatchInfo(
                    fixtureId = "", datetime = "", matchType = league, teams = "", predictedOutcome = null,
                    actualOutcome = null, predictedScore = null, actualScore = null, odds = null,
                    bookmakerName = null, homeWinOdds = null, drawOdds = null, awayWinOdds = null,
                    telegramMessageId = null, strategyTelegramMessageId = msgId, elapsed = null,
                    modelHomeWinProb = null, modelDrawProb = null, modelAwayWinProb = null,
                    modelExpectedHomeGoals = null, modelExpectedAwayGoals = null
                ))
            } else {
                footballBot.updateMatchMessages(remaining.first())
            }
        }
    }

    suspend fun getFixtureInfo(fixtureId: String): FixtureInfo? {
        val response: HttpResponse = client.get(url) {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
            }
            parameter("id", fixtureId)
        }

        if (response.status == HttpStatusCode.OK) {
            val result = response.body<ApiFootballResponse>()
            val match = result.response.firstOrNull() ?: return null

            val isoDateTime = match.fixture.date
            val parsedDateTime = OffsetDateTime.parse(isoDateTime)
            val formatted = parsedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

            return FixtureInfo(
                statusShort = match.fixture.status?.short,
                datetime = formatted,
                homeTeam = match.teams.home.name,
                awayTeam = match.teams.away.name
            )
        }
        logger.error("Failed to fetch fixture info for $fixtureId. HTTP status: ${'$'}{response.status}")
        return null
    }


    suspend fun getLiveMatchInfo(fixtureId: String): MatchInfo? {
        // Сначала получаем текущую информацию о матче из базы данных
        val existingMatchInfo = DatabaseService.matches.getMatchInfoByFixtureId(fixtureId)
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
                val homeTeam = match.teams.home.name
                val awayTeam = match.teams.away.name

                val homeGoals = match.score?.fulltime?.home ?: 0
                val awayGoals = match.score?.fulltime?.away ?: 0

                // Определяем победителя
                val winner = when {
                    homeGoals > awayGoals -> homeTeam
                    awayGoals > homeGoals -> awayTeam
                    else -> "Draw"
                }

                val statusShort = match.fixture.status?.short
                val elapsed = match.fixture.status?.elapsed ?: 0

                val actualScore = if (statusShort == "FT" || statusShort == "AET" || statusShort == "PEN"){
                    "$homeGoals:$awayGoals"
                } else {
                    "${match.goals?.home ?: 0}:${match.goals?.away ?: 0}"
                }
                val actualOutcome = if (statusShort == "FT" || statusShort == "AET" || statusShort == "PEN") {
                    winner
                } else {
                    null
                }

                // Парсим дату и время матча
                val isoDateTime = match.fixture.date // Оригинальная дата и время в ISO формате
                val parsedDateTime = OffsetDateTime.parse(isoDateTime) // Парсим ISO строку

                // Приводим к нужному формату
                val formatterMatchDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val datetime = parsedDateTime.format(formatterMatchDate) // Форматируем дату и время

                // Возвращаем обновлённый объект
                return existingMatchInfo.copy(
                    actualScore = actualScore,
                    actualOutcome = actualOutcome,
                    elapsed = elapsed,
                    datetime = datetime
                )
            } else {
                logger.warn("No match data found for fixtureId $fixtureId")
                return null
            }
        } else {
            logger.error("Failed to fetch live match info for fixtureId $fixtureId. HTTP status: ${response.status}")
            return null
        }
    }

    suspend fun getOddsForFixture(
        fixtureId: String,
        predictedOutcome: String,
        homeTeam: String,
        awayTeam: String
    ): OddsInfo? {
        val oddsUrl = "https://api-football-v1.p.rapidapi.com/v3/odds"

        for (bookmaker in bookmakers) {
            val response: HttpResponse = client.get(oddsUrl) {
                headers {
                    append("X-RapidAPI-Key", apiKey)
                    append("X-RapidAPI-Host", "api-football-v1.p.rapidapi.com")
                }
                parameter("fixture", fixtureId)
                parameter("bookmaker", bookmaker.id)
            }

            if (response.status == HttpStatusCode.OK) {
                val result = response.body<OddsResponse>()
                val oddsData = result.response.firstOrNull()
                if (oddsData != null) {
                    val bets = oddsData.bookmakers.firstOrNull()?.bets
                    val matchWinnerBet = bets?.find {
                        it.name == "Match Winner"
                    }
                    val oddsMap = matchWinnerBet?.values?.associateBy { it.value }

                    val homeWinOdds = oddsMap?.get("Home")?.odd?.toDoubleOrNull()
                    val drawOdds = oddsMap?.get("Draw")?.odd?.toDoubleOrNull()
                    val awayWinOdds = oddsMap?.get("Away")?.odd?.toDoubleOrNull()

                    // Определяем коэффициент на прогнозируемый исход
                    val oddsValue = when {
                        predictedOutcome.equals(homeTeam, ignoreCase = true) -> homeWinOdds
                        predictedOutcome.equals(awayTeam, ignoreCase = true) -> awayWinOdds
                        predictedOutcome.equals("Draw", ignoreCase = true) -> drawOdds
                        else -> null
                    }

                    if (oddsValue != null) {
                        // Возвращаем все коэффициенты и имя букмекера
                        return OddsInfo(
                            odds = oddsValue,
                            bookmakerName = bookmaker.name,
                            homeWinOdds = homeWinOdds,
                            drawOdds = drawOdds,
                            awayWinOdds = awayWinOdds
                        )
                    }
                }
            } else {
                logger.error("Не удалось получить коэффициенты для fixtureId $fixtureId от букмекера ${bookmaker.name}. HTTP статус: ${response.status}")
            }
            // Задержка между запросами для избежания ограничения по частоте запросов
            delay(1000)
        }

        // Если коэффициенты не найдены ни у одного букмекера
        return null
    }


}
