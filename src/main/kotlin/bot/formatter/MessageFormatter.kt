package bot.formatter

import dto.MatchInfo
import dto.LeagueConfig
import dto.outcomeStrategyConfigs

object MessageFormatter {

    private fun formatTestData(matchInfo: MatchInfo): String {
        val homeProb = matchInfo.modelHomeWinProb?.times(100)?.let { "%.2f%%".format(it) } ?: "0%"
        val drawProb = matchInfo.modelDrawProb?.times(100)?.let { "%.2f%%".format(it) } ?: "0%"
        val awayProb = matchInfo.modelAwayWinProb?.times(100)?.let { "%.2f%%".format(it) } ?: "0%"

        val homeXg = matchInfo.modelExpectedHomeGoals?.let { "%.2f".format(it) } ?: "0"
        val awayXg = matchInfo.modelExpectedAwayGoals?.let { "%.2f".format(it) } ?: "0"

        val homeOdds = matchInfo.homeWinOdds ?: "0"
        val drawOdds = matchInfo.drawOdds ?: "0"
        val awayOdds = matchInfo.awayWinOdds ?: "0"

        return """
Probabilities: $homeProb - $drawProb - $awayProb
Expected Goals: $homeXg : $awayXg
Odds: $homeOdds - $drawOdds - $awayOdds
""".trimIndent()
    }

    fun formatRegularMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val testData = if (includeTestData) formatTestData(matchInfo) else ""

        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}${if (testData.isNotEmpty()) "\n$testData" else ""}
$tags""".trimIndent()
    }

    fun formatUpcomingMatch(matchInfo: MatchInfo, league: LeagueConfig?, tags: String): String {
        val analysis = buildMatchAnalysis(matchInfo, league)
        val testData = formatTestData(matchInfo)
        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}
$testData
$analysis
$tags""".trimIndent()
    }

    private fun buildMatchAnalysis(matchInfo: MatchInfo, league: LeagueConfig?): String {
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
        val odds = when (outcomeType) {
            "HomeWin" -> matchInfo.homeWinOdds?.toDoubleOrNull() ?: 0.0
            "Draw" -> matchInfo.drawOdds?.toDoubleOrNull() ?: 0.0
            "AwayWin" -> matchInfo.awayWinOdds?.toDoubleOrNull() ?: 0.0
            else -> matchInfo.odds?.toDoubleOrNull() ?: 0.0
        }
        val drawAlt = if (outcomeType == "Draw") {
            val homeProb = matchInfo.modelHomeWinProb ?: 0.0
            val drawProb = matchInfo.modelDrawProb ?: 0.0
            val awayProb = matchInfo.modelAwayWinProb ?: 0.0
            val xgDiff = (matchInfo.modelExpectedHomeGoals ?: 0.0) - (matchInfo.modelExpectedAwayGoals ?: 0.0)
            probability <= 0.4 && homeProb <= 0.4 && awayProb <= 0.4 && odds > 3.1 && kotlin.math.abs(xgDiff) <= 0.1
        } else false
        val probCheck = if (probability >= minProb || drawAlt) "✅" else "❌"
        val leagueCheck = if (league?.premiumSelection == true) "✅" else "❌"
        val minOdds = config?.minOdds ?: 0.0
        val profitCheck = if (odds >= minOdds) "✅" else "❌"

        return """Match Analysis:
- Probability >= ${(minProb * 100).toInt()}% $probCheck
- League predictable $leagueCheck
- Profitability $profitCheck""".trimIndent()
    }
}
