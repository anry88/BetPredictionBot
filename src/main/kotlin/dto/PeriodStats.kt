package dto

data class PeriodStats(
    val totalMatches: Int,
    val correctPredictions: Int,
    val totalStakes: Double,
    val totalReturns: Double,
    val accuracy: Double,
    val roi: Double,
    // Статистика по стратегии
    val strategyTotalMatches: Int,
    val strategyCorrectPredictions: Int,
    val strategyTotalStakes: Double,
    val strategyTotalReturns: Double,
    val strategyAccuracy: Double,
    val strategyRoi: Double
)
