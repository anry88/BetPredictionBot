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
    val maxXgTotal: Double? = null,
    val minSignedXgDiff: Double? = null
)

val outcomeStrategyConfigs = listOf(
    // Rule A: Away
    OutcomeStrategyConfig(
        outcomeType = OutcomeType.AwayWin,
        minOdds = 2.05,
        maxOdds = 2.30,
        minProb = 0.54,
        maxProb = 0.59,
        minSignedXgDiff = 0.75
    ),
    // Rule B: Draw
    OutcomeStrategyConfig(
        outcomeType = OutcomeType.Draw,
        minOdds = 2.80,
        maxOdds = 5.50,
        minProb = 0.36,
        maxXgDiff = 0.08,
        maxXgTotal = 1.60
    )
)
