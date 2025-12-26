package dto

enum class OutcomeType {
    HomeWin,
    Draw,
    AwayWin
}

data class OutcomeStrategyConfig(
    val outcomeType: OutcomeType,
    val minOdds: Double,
    val maxOdds: Double,
    val minProb: Double,
    val maxProb: Double? = null,
    val maxXgDiff: Double? = null,
    val maxXgTotal: Double? = null
)

val outcomeStrategyConfigs = listOf(
    // Rule A: Away
    OutcomeStrategyConfig(
        outcomeType = OutcomeType.AwayWin,
        minOdds = 1.80,
        maxOdds = 2.50,
        minProb = 0.55,
        maxProb = 0.62
    ),
    // Rule B: Draw
    OutcomeStrategyConfig(
        outcomeType = OutcomeType.Draw,
        minOdds = 2.80,
        maxOdds = 5.50,
        minProb = 0.36,
        maxXgDiff = 0.10,
        maxXgTotal = 1.60
    )
)
