package dto

data class LeagueStats(
    val leagueName: String,
    var totalMatches: Int = 0,
    var successfulPredictions: Int = 0,
    var totalStakes: Double = 0.0,
    var totalReturns: Double = 0.0,
    var roi: Double = 0.0,
    var accuracy: Double = 0.0,
    // Новые поля для матчей, соответствующих стратегии
    var strategyTotalMatches: Int = 0,
    var strategySuccessfulPredictions: Int = 0,
    var strategyTotalStakes: Double = 0.0,
    var strategyTotalReturns: Double = 0.0,
    var strategyRoi: Double = 0.0,
    var strategyAccuracy: Double = 0.0
)

