package bot.formatter

import dto.MatchInfo
import dto.LeagueConfig
import dto.outcomeStrategyConfigs
import service.StrategyService

object MessageFormatter {

    private const val PREMIUM_HEADER = "\uD83D\uDD25 PREMIUM PICK \uD83D\uDD25"

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

    // --- Main channel ---
    fun formatMainUpcomingMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val testData = if (includeTestData) "\n${formatTestData(matchInfo)}" else ""
        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$testData
$tags""".trimIndent()
    }

    fun formatMainLiveMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val testData = if (includeTestData) "\n${formatTestData(matchInfo)}" else ""
        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}
Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'$testData
$tags #Live""".trimIndent()
    }

    fun formatMainCompletedMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val isPredictionCorrect = matchInfo.predictedOutcome?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val testData = if (includeTestData) "\n${formatTestData(matchInfo)}" else ""
        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$emoji
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}$testData
$tags""".trimIndent()
    }

    // --- Premium channel ---
    private fun predictedOutcomeProbability(matchInfo: MatchInfo): Double {
        val teams = matchInfo.teams.split(" vs. ")
        val homeTeam = teams.getOrNull(0)?.trim()
        val awayTeam = teams.getOrNull(1)?.trim()
        return when (matchInfo.predictedOutcome) {
            homeTeam -> matchInfo.modelHomeWinProb ?: 0.0
            "Draw" -> matchInfo.modelDrawProb ?: 0.0
            awayTeam -> matchInfo.modelAwayWinProb ?: 0.0
            else -> 0.0
        } * 100
    }

    fun formatPremiumUpcomingMatch(matchInfo: MatchInfo): String {
        val probability = predictedOutcomeProbability(matchInfo)
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore} (${"%.2f".format(probability)}%)
Odds: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
""".trimIndent()
    }

    fun formatPremiumLiveMatch(matchInfo: MatchInfo): String {
        val probability = predictedOutcomeProbability(matchInfo)
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore} (${"%.2f".format(probability)}%)
Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'
Odds: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
#Live
""".trimIndent()
    }

    fun formatPremiumCompletedMatch(matchInfo: MatchInfo): String {
        val probability = predictedOutcomeProbability(matchInfo)
        val isPredictionCorrect = matchInfo.predictedOutcome?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$emoji (${"%.2f".format(probability)}%)
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
Odds: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
""".trimIndent()
    }

    // --- Direct messages ---
    fun formatDirectUpcomingMatch(matchInfo: MatchInfo, league: LeagueConfig?, timezone: String = "UTC"): String {
        val analysis = buildPredictionAnalysis(matchInfo, league)
        val testData = formatTestData(matchInfo)
        val premiumHeader = if (outcomeStrategyConfigs.any { StrategyService.isMatchFitsStrategy(matchInfo, it) }) {
            "$PREMIUM_HEADER\n"
        } else {
            ""
        }
        return """
${premiumHeader}${matchInfo.datetime} $timezone
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}
$testData
$analysis""".trimIndent()
    }

    fun formatDirectCompletedMatch(matchInfo: MatchInfo, league: LeagueConfig?, timezone: String = "UTC"): String {
        val analysis = buildPredictionAnalysis(matchInfo, league)
        val testData = formatTestData(matchInfo)
        val isPredictionCorrect = matchInfo.predictedOutcome?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val premiumHeader = if (outcomeStrategyConfigs.any { StrategyService.isMatchFitsStrategy(matchInfo, it) }) {
            "$PREMIUM_HEADER\n"
        } else {
            ""
        }
        return """
${premiumHeader}${matchInfo.datetime} $timezone
${matchInfo.teams}
Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$emoji
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
$testData
$analysis""".trimIndent()
    }

    private fun buildPredictionAnalysis(matchInfo: MatchInfo, league: LeagueConfig?): String {
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

        val (drawHighProb, drawBalanced) = if (outcomeType == "Draw") {
            val homeProb = matchInfo.modelHomeWinProb ?: 0.0
            val drawProb = matchInfo.modelDrawProb ?: 0.0
            val awayProb = matchInfo.modelAwayWinProb ?: 0.0
            val xgDiff = (matchInfo.modelExpectedHomeGoals ?: 0.0) - (matchInfo.modelExpectedAwayGoals ?: 0.0)

            val highProb = drawProb > minProb && odds > (config?.minOdds ?: 0.0)
            val balanced = homeProb <= 0.4 && drawProb <= 0.4 && awayProb <= 0.4 &&
                odds > 3.1 && kotlin.math.abs(xgDiff) <= 0.1
            highProb to balanced
        } else false to false

        val leagueCheck = if (league?.premiumSelection == true) "✅" else "❌"

        val profitCheck = if (outcomeType == "Draw") {
            when {
                drawHighProb -> "✅"
                drawBalanced -> "✅"
                else -> "❌"
            }
        } else {
            if (odds >= (config?.minOdds ?: 0.0)) "✅" else "❌"
        }

        val dataEnough =
            (matchInfo.homeMatchesLastYear ?: 0) > 5 && (matchInfo.awayMatchesLastYear ?: 0) > 5
        val dataCheck = if (dataEnough) "✅" else "❌"

        val probabilityLine = when {
            probability == 0.0 -> "- Probability ❌ no data available"
            drawBalanced -> "- Probability < 40%, diff xG < 0.1 ✅"
            drawHighProb -> "- Probability >= ${(minProb * 100).toInt()}% ✅"
            else -> "- Probability >= ${(minProb * 100).toInt()}% ❌"
        }

        return """
            Prediction Analysis:
            $probabilityLine
            - League predictable $leagueCheck
            - Profitability $profitCheck
            - Enough data $dataCheck
        """.trimIndent()
    }
}
