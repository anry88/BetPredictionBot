// In a new file or an existing one, e.g., dto/OutcomeStrategyConfig.kt
package dto

data class OutcomeStrategyConfig(
    val outcomeType: String,            // "HomeWin", "Draw", or "AwayWin"
    val roiThreshold: Double,
    val accuracyThreshold: Double,
    val minOdds: Double,
    val maxOdds: Double
)
// In FootballBot.kt or a configuration file
val outcomeStrategyConfigs = listOf(
    OutcomeStrategyConfig(
        outcomeType = "HomeWin",
        roiThreshold = 5.0,
        accuracyThreshold = 60.0,
//        minOdds = 1.20,
//        maxOdds = 2.20
        minOdds = 1.00,
        maxOdds = 10.20
    ),
    OutcomeStrategyConfig(
        outcomeType = "Draw",
        roiThreshold = 20.0,
        accuracyThreshold = 30.0,
        minOdds = 2.50,
        maxOdds = 4.50
    ),
    OutcomeStrategyConfig(
        outcomeType = "AwayWin",
        roiThreshold = 10.0,
        accuracyThreshold = 60.0,
        minOdds = 1.30,
        maxOdds = 2.50
    )
)
