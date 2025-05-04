package service

import dto.MatchInfo
import dto.OutcomeStrategyConfig

object StrategyService {
    fun isMatchFitsStrategy(match: MatchInfo, config: OutcomeStrategyConfig): Boolean {
        val teams = match.teams.split(" vs. ")
        if (teams.size != 2) return false

        val homeTeam = teams[0].trim()
        val awayTeam = teams[1].trim()
        val predictedOutcome = match.predictedOutcome ?: return false

        // Если у матча есть modelHomeWinProb != null, значит прогноз от модели
        val isFromLocalModel = match.modelHomeWinProb != null

        // Берём «привычные» odds (из поля match.odds)
        val oddsValue = match.odds?.toDoubleOrNull() ?: 0.0

        if (isFromLocalModel) {
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