package dto

data class LeagueStats(
    val leagueName: String,
    var totalMatches: Int = 0,
    var successfulPredictions: Int = 0,
    var totalStakes: Double = 0.0,
    var totalReturns: Double = 0.0,
    var roi: Double = 0.0,
    var accuracy: Double = 0.0,
    // Новые поля для детальной статистики
    var homeWinPredictions: Int = 0,
    var homeWinSuccesses: Int = 0,
    var homeWinAccuracy: Double = 0.0,
    var homeWinStakes: Double = 0.0,
    var homeWinReturns: Double = 0.0,
    var homeWinRoi: Double = 0.0,
    var drawPredictions: Int = 0,
    var drawSuccesses: Int = 0,
    var drawAccuracy: Double = 0.0,
    var drawStakes: Double = 0.0,
    var drawReturns: Double = 0.0,
    var drawRoi: Double = 0.0,
    var awayWinPredictions: Int = 0,
    var awayWinSuccesses: Int = 0,
    var awayWinAccuracy: Double = 0.0,
    var awayWinStakes: Double = 0.0,
    var awayWinReturns: Double = 0.0,
    var awayWinRoi: Double = 0.0,
    // Поля для стратегии
    var strategyTotalMatches: Int = 0,
    var strategySuccessfulPredictions: Int = 0,
    var strategyTotalStakes: Double = 0.0,
    var strategyTotalReturns: Double = 0.0,
    var strategyRoi: Double = 0.0,
    var strategyAccuracy: Double = 0.0
)
