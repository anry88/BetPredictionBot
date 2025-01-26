// In a new file or an existing one, e.g., dto/OutcomeStrategyConfig.kt
package dto

data class OutcomeStrategyConfig(
    val outcomeType: String,            // "HomeWin", "Draw", or "AwayWin"
    val roiThreshold: Double,
    val accuracyThreshold: Double,
    val minOdds: Double,
    val maxOdds: Double,
    val homeWinModelProb: Double,
    val drawModelProb: Double,
    val homeAwayModelProb: Double
)
// In FootballBot.kt or a configuration file
val outcomeStrategyConfigs = listOf(
    OutcomeStrategyConfig(
        outcomeType = "HomeWin",
        roiThreshold = 5.0,
        accuracyThreshold = 60.0,
        minOdds = 1.20,
        maxOdds = 2.20,
        homeWinModelProb = 0.7,
        drawModelProb = 0.0,
        homeAwayModelProb = 0.0
    ),
    OutcomeStrategyConfig(
        outcomeType = "Draw",
        roiThreshold = 20.0,
        accuracyThreshold = 30.0,
        minOdds = 2.50,
        maxOdds = 5.50,
        homeWinModelProb = 0.45,
        drawModelProb = 0.3,
        homeAwayModelProb = 0.45
    ),
    OutcomeStrategyConfig(
        outcomeType = "AwayWin",
        roiThreshold = 10.0,
        accuracyThreshold = 60.0,
        minOdds = 1.30,
        maxOdds = 2.50,
        homeWinModelProb = 0.0,
        drawModelProb = 0.0,
        homeAwayModelProb = 0.6
    )
)
