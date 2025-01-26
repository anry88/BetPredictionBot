package service

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
import org.slf4j.LoggerFactory

object HttpLocalModelService {
    private val logger = LoggerFactory.getLogger(HttpLocalModelService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
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
        val expectedAwayGoals: Double
    )

    /**
     * Функция обращается к локальной модели:
     * - Если всё ок, возвращает MatchInfo с заполненными predictedOutcome и predictedScore
     * - Если ошибка, возвращает null
     */
    suspend fun getModelPrediction(
        homeTeam: String,
        awayTeam: String,
        fixtureId: String,
        matchInfo: MatchInfo
    ): MatchInfo? {
        val url = "http://localhost:7007/predict"
        return try {
            val response: HttpResponse = client.get(url) {
                parameter("home", homeTeam)
                parameter("away", awayTeam)
            }
            if (response.status == HttpStatusCode.OK) {
                val data = response.body<LocalModelResponse>()

                // Заполняем новые поля в matchInfo
                matchInfo.modelHomeWinProb = data.homeWin
                matchInfo.modelDrawProb = data.draw
                matchInfo.modelAwayWinProb = data.awayWin
                matchInfo.modelExpectedHomeGoals = data.expectedHomeGoals
                matchInfo.modelExpectedAwayGoals = data.expectedAwayGoals

                // Округляем ожидаемые голы:
                val roundedHomeGoals = kotlin.math.round(data.expectedHomeGoals).toInt()
                val roundedAwayGoals = kotlin.math.round(data.expectedAwayGoals).toInt()

                val outcome = when {
                    roundedHomeGoals > roundedAwayGoals -> homeTeam
                    roundedHomeGoals < roundedAwayGoals -> awayTeam
                    else -> "Draw"
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
