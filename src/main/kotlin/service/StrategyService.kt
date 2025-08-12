package service

import dto.MatchInfo
import dto.OutcomeStrategyConfig
import dto.LeagueConfig
import Config
import kotlinx.serialization.json.Json

object StrategyService {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val leaguesConfig: List<LeagueConfig> = loadLeaguesConfig()

    private fun loadLeaguesConfig(): List<LeagueConfig> {
        val leaguesJson = javaClass.getResource("/leagues.json")?.readText()
            ?: throw IllegalStateException("leagues.json not found")
        return json.decodeFromString(leaguesJson)
    }

    fun isMatchFitsStrategy(match: MatchInfo, config: OutcomeStrategyConfig): Boolean {
        val teams = match.teams.split(" vs. ")
        if (teams.size != 2) return false

        val homeTeam = teams[0].trim()
        val awayTeam = teams[1].trim()
        val predictedOutcome = match.predictedOutcome ?: return false

        val homeCount = match.homeMatchesLastTwoYears ?: 0
        val awayCount = match.awayMatchesLastTwoYears ?: 0
        if (homeCount <= 5 || awayCount <= 5) return false

        // Если у матча есть modelHomeWinProb != null, значит прогноз от модели
        val isFromLocalModel = match.modelHomeWinProb != null
        val isPremiumSelection = if (Config.getProperty("test")?.toBoolean() == true) true else leaguesConfig.any { it.description == match.matchType && it.premiumSelection }

        // Берём «привычные» odds (из поля match.odds)
        val oddsValue = match.odds?.toDoubleOrNull() ?: 0.0

        if (isPremiumSelection && isFromLocalModel) {
            when (config.outcomeType) {
                "HomeWin" -> {
                    if (predictedOutcome == homeTeam &&
                        (match.modelHomeWinProb ?: 0.0) > config.homeWinModelProb &&
                        oddsValue > config.minOdds
                    ) return true
                }

                "Draw" -> {
                    if (predictedOutcome == "Draw" && oddsValue > config.minOdds) {
                        // Проверяем основное условие - высокая вероятность ничьей
                        if ((match.modelDrawProb ?: 0.0) > config.drawModelProb) return true
                        
                        // Проверяем альтернативное условие - равные шансы всех исходов
                        val homeProb = match.modelHomeWinProb ?: 0.0
                        val drawProb = match.modelDrawProb ?: 0.0
                        val awayProb = match.modelAwayWinProb ?: 0.0
                        val expectedHomeGoals = match.modelExpectedHomeGoals ?: 0.0
                        val expectedAwayGoals = match.modelExpectedAwayGoals ?: 0.0
                        
                        return homeProb in 0.0..0.4 &&
                               drawProb in 0.0..0.4 &&
                               awayProb in 0.0..0.4 &&
                               oddsValue > 3.1 &&
                               kotlin.math.abs(expectedHomeGoals - expectedAwayGoals) <= 0.1
                    }
                }

                "AwayWin" -> {
                    if (predictedOutcome == awayTeam &&
                        (match.modelAwayWinProb ?: 0.0) > config.awayWinModelProb &&
                        oddsValue > config.minOdds
                    ) return true
                }
            }
        }
        return false
    }
} 