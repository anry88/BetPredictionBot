package service

import Config
import dto.MatchInfo
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

@Suppress("PLUGIN_IS_NOT_ENABLED")
object HttpLocalModelService {
    private val logger = LoggerFactory.getLogger(HttpLocalModelService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Ответ от локальной модели
     * Пример:
     * {
     *   "homeWin": 0.7416700536013794,
     *   "draw": 0.1618230820710137,
     *   "awayWin": 0.09650686432760686,
     *   "expectedHomeGoals": 2.40840710083566,
     *   "expectedAwayGoals": 0.7432932244088393
     * }
     */
    @Serializable
    data class LocalModelResponse(
        val homeWin: Double,
        val draw: Double,
        val awayWin: Double,
        val expectedHomeGoals: Double,
        val expectedAwayGoals: Double,
        val calibratedExpectedHomeGoals: Double? = null,
        val calibratedExpectedAwayGoals: Double? = null,
        val calibratedHomeWin: Double? = null,
        val calibratedDraw: Double? = null,
        val calibratedAwayWin: Double? = null,
        val calibrationApplied: Boolean? = null,
        val homeMatchesLastYear: Int? = null,
        val awayMatchesLastYear: Int? = null
    )

    /**
     * Функция обращается к локальной модели:
     * - Если всё ок, возвращает MatchInfo с заполненными predictedOutcome и predictedScore
     * - Если ошибка, возвращает null
     */
    suspend fun getModelPrediction(
        homeTeam: String,
        awayTeam: String,
        matchInfo: MatchInfo
    ): MatchInfo? {
        val url = "${Config.getLocalModelBaseUrl()}/predict"
        return try {
            val response: HttpResponse = client.get(url) {
                parameter("home", homeTeam)
                parameter("away", awayTeam)
                parameter("league", matchInfo.matchType)
            }
            if (response.status == HttpStatusCode.OK) {
                val data = response.body<LocalModelResponse>()
                val predictedAt = Instant.now().toString()

                // Заполняем новые поля в matchInfo
                matchInfo.modelHomeWinProb = data.homeWin
                matchInfo.modelDrawProb = data.draw
                matchInfo.modelAwayWinProb = data.awayWin
                matchInfo.modelExpectedHomeGoals = data.expectedHomeGoals
                matchInfo.modelExpectedAwayGoals = data.expectedAwayGoals
                matchInfo.calibratedExpectedHomeGoals = data.calibratedExpectedHomeGoals
                matchInfo.calibratedExpectedAwayGoals = data.calibratedExpectedAwayGoals
                matchInfo.calibratedHomeWinProb = data.calibratedHomeWin
                matchInfo.calibratedDrawProb = data.calibratedDraw
                matchInfo.calibratedAwayWinProb = data.calibratedAwayWin
                matchInfo.calibrationApplied = data.calibrationApplied
                matchInfo.predictedAt = predictedAt

                if (data.homeMatchesLastYear != null) {
                    matchInfo.homeMatchesLastYear = data.homeMatchesLastYear
                }
                if (data.awayMatchesLastYear != null) {
                    matchInfo.awayMatchesLastYear = data.awayMatchesLastYear
                }

                val outcome = listOf(
                    homeTeam to data.homeWin,
                    "Draw" to data.draw,
                    awayTeam to data.awayWin
                ).maxByOrNull { it.second }!!.first

                var roundedHomeGoals = kotlin.math.round(data.expectedHomeGoals).toInt().coerceAtLeast(0)
                var roundedAwayGoals = kotlin.math.round(data.expectedAwayGoals).toInt().coerceAtLeast(0)

                when (outcome) {
                    homeTeam -> {
                        if (roundedHomeGoals <= roundedAwayGoals) {
                            roundedHomeGoals = roundedAwayGoals + 1
                        }
                    }
                    awayTeam -> {
                        if (roundedAwayGoals <= roundedHomeGoals) {
                            roundedAwayGoals = roundedHomeGoals + 1
                        }
                    }
                    "Draw" -> {
                        val goals = maxOf(roundedHomeGoals, roundedAwayGoals)
                        roundedHomeGoals = goals
                        roundedAwayGoals = goals
                    }
                }

                val score = "$roundedHomeGoals:$roundedAwayGoals"

                matchInfo.predictedOutcome = outcome
                matchInfo.predictedScore = score

                matchInfo // возвращаем обновлённый
            } else {
                logger.error("Local model returned non-OK status: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error calling local model: ${e.message}")
            null
        }
    }

}
