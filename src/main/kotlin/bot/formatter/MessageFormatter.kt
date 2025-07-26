package bot.formatter

import dto.MatchInfo
import dto.LeagueConfig
import dto.outcomeStrategyConfigs

object MessageFormatter {
    private const val CONSTANT_TEST_DATA = """
Probabilities: 60% - 25% - 15%
Expected Goals: 1.5 : 1.0
Odds: 1.8 - 3.4 - 4.5
"""

    fun formatRegularMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val testData = if (includeTestData) {
            """
Probabilities: ${matchInfo.modelHomeWinProb?.let { "%.2f%%".format(it * 100) } ?: "0%"} - ${matchInfo.modelDrawProb?.let { "%.2f%%".format(it * 100) } ?: "0%"} - ${matchInfo.modelAwayWinProb?.let { "%.2f%%".format(it * 100) } ?: "0%"}
Expected Goals: ${matchInfo.modelExpectedHomeGoals?.let { "%.2f".format(it) } ?: 0} : ${matchInfo.modelExpectedAwayGoals?.let { "%.2f".format(it) } ?: 0}
Odds: ${matchInfo.homeWinOdds ?: 0} - ${matchInfo.drawOdds ?: 0} - ${matchInfo.awayWinOdds ?: 0}
""".trimIndent()
        } else ""

        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}${if (testData.isNotEmpty()) "\n$testData" else ""}
$tags""".trimIndent()
    }

    fun formatUpcomingMatch(matchInfo: MatchInfo, league: LeagueConfig?, tags: String): String {
        val checklist = buildChecklist(matchInfo, league)
        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}
$CONSTANT_TEST_DATA
$checklist
$tags""".trimIndent()
    }

    private fun buildChecklist(matchInfo: MatchInfo, league: LeagueConfig?): String {
        val teams = matchInfo.teams.split(" vs. ")
        val homeTeam = teams.getOrNull(0)?.trim()
        val awayTeam = teams.getOrNull(1)?.trim()
        val outcomeType = when (matchInfo.predictedOutcome) {
            homeTeam -> "HomeWin"
            "Draw" -> "Draw"
            awayTeam -> "AwayWin"
            else -> ""
        }
        val config = outcomeStrategyConfigs.firstOrNull { it.outcomeType == outcomeType }
        val probability = when (outcomeType) {
            "HomeWin" -> matchInfo.modelHomeWinProb ?: 0.0
            "Draw" -> matchInfo.modelDrawProb ?: 0.0
            "AwayWin" -> matchInfo.modelAwayWinProb ?: 0.0
            else -> 0.0
        }
        val minProb = when (outcomeType) {
            "HomeWin" -> config?.homeWinModelProb ?: 0.0
            "Draw" -> config?.drawModelProb ?: 0.0
            "AwayWin" -> config?.awayWinModelProb ?: 0.0
            else -> 1.0
        }
        val probCheck = if (probability >= minProb) "✅" else "❌"
        val leagueCheck = if (league?.premiumSelection == true) "✅" else "❌"
        val odds = when (outcomeType) {
            "HomeWin" -> matchInfo.homeWinOdds?.toDoubleOrNull() ?: 0.0
            "Draw" -> matchInfo.drawOdds?.toDoubleOrNull() ?: 0.0
            "AwayWin" -> matchInfo.awayWinOdds?.toDoubleOrNull() ?: 0.0
            else -> matchInfo.odds?.toDoubleOrNull() ?: 0.0
        }
        val minOdds = config?.minOdds ?: 0.0
        val profitCheck = if (odds >= minOdds) "✅" else "❌"

        return """Checklist:
- Probability >= ${(minProb * 100).toInt()}% $probCheck
- League predictable $leagueCheck
- Profitability $profitCheck""".trimIndent()
    }
}
