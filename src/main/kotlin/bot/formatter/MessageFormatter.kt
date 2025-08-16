package bot.formatter

import dto.MatchInfo
import dto.LeagueConfig
import dto.outcomeStrategyConfigs
import service.StrategyService
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MessageFormatter {

    private const val PREMIUM_HEADER = "\uD83D\uDD25 PREMIUM PICK \uD83D\uDD25"
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun timeUntil(datetime: String, zone: ZoneId): String {
        return try {
            val matchTime = LocalDateTime.parse(datetime, dateTimeFormatter)
            val now = LocalDateTime.now(zone)
            val duration = Duration.between(now, matchTime)
            if (duration.isNegative) {
                "started"
            } else {
                val days = duration.toDays()
                val hours = duration.toHours() % 24
                val minutes = duration.toMinutes() % 60
                val parts = mutableListOf<String>()
                if (days > 0) parts.add("${days}d")
                if (hours > 0 || days > 0) parts.add("${hours}h")
                parts.add("${minutes}m")
                "in ${parts.joinToString(" ")}"
            }
        } catch (e: Exception) {
            ""
        }
    }

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
        val timeLeft = timeUntil(matchInfo.datetime, ZoneId.of("UTC"))
        val isPremium = outcomeStrategyConfigs.any { StrategyService.isMatchFitsStrategy(matchInfo, it) }
        return if (isPremium) {
            """${PREMIUM_HEADER}
${matchInfo.datetime} UTC (${timeLeft})
${matchInfo.teams}
$tags""".trimIndent()
        } else {
            val testData = if (includeTestData) "\n${formatTestData(matchInfo)}" else ""
            """${matchInfo.datetime} UTC (${timeLeft})
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}
Predicted score: ${matchInfo.predictedScore}$testData
$tags""".trimIndent()
        }
    }

    fun formatMainLiveMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val testData = if (includeTestData) "\n${formatTestData(matchInfo)}" else ""
        return """
${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}
Predicted score: ${matchInfo.predictedScore}
Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'$testData
$tags #Live""".trimIndent()
    }

    fun formatMainCompletedMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val isPredictionCorrect = matchInfo.predictedOutcome?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val testData = if (includeTestData) "\n${formatTestData(matchInfo)}" else ""
        val isPremium = outcomeStrategyConfigs.any { StrategyService.isMatchFitsStrategy(matchInfo, it) }
        return if (isPremium) {
            """$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}$testData
$tags""".trimIndent()
        } else {
            """${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}$emoji
Predicted score: ${matchInfo.predictedScore}
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}$testData
$tags""".trimIndent()
        }
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
        val timeLeft = timeUntil(matchInfo.datetime, ZoneId.of("UTC"))
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC (${timeLeft})
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome} (${"%.2f".format(probability)}%)
Predicted score: ${matchInfo.predictedScore}
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
""".trimIndent()
    }

    fun formatPremiumLiveMatch(matchInfo: MatchInfo): String {
        val probability = predictedOutcomeProbability(matchInfo)
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome} (${"%.2f".format(probability)}%)
Predicted score: ${matchInfo.predictedScore}
Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
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
Predicted outcome: ${matchInfo.predictedOutcome}$emoji (${"%.2f".format(probability)}%)
Predicted score: ${matchInfo.predictedScore}
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
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
        val timeLeft = timeUntil(matchInfo.datetime, ZoneId.of(timezone))
        val currentLine = matchInfo.elapsed?.let { "\nCurrent: ${matchInfo.actualScore} ${it}'" } ?: ""
        return """
${premiumHeader}${matchInfo.datetime} $timezone (${timeLeft})
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}
Predicted score: ${matchInfo.predictedScore}$currentLine
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
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
Predicted outcome: ${matchInfo.predictedOutcome}$emoji
Predicted score: ${matchInfo.predictedScore}
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
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

        val (drawHighProb, drawBalancedProb) = if (outcomeType == "Draw") {
            val homeProb = matchInfo.modelHomeWinProb ?: 0.0
            val drawProb = matchInfo.modelDrawProb ?: 0.0
            val awayProb = matchInfo.modelAwayWinProb ?: 0.0
            val xgDiff = (matchInfo.modelExpectedHomeGoals ?: 0.0) - (matchInfo.modelExpectedAwayGoals ?: 0.0)

            val highProb = drawProb > minProb
            val balanced = homeProb <= 0.4 && drawProb <= 0.4 && awayProb <= 0.4 &&
                kotlin.math.abs(xgDiff) <= 0.1
            highProb to balanced
        } else false to false

        val drawHighProfit = drawHighProb && odds > (config?.minOdds ?: 0.0)
        val drawBalancedProfit = drawBalancedProb && odds > 3.1
        val drawFallbackProfit = !drawHighProb && !drawBalancedProb &&
            odds > kotlin.math.max(3.1, config?.minOdds ?: 0.0)

        val leagueCheck = if (league?.premiumSelection == true) "✅" else "❌"

        val profitCheck = if (outcomeType == "Draw") {
            when {
                drawHighProfit -> "✅"
                drawBalancedProfit -> "✅"
                drawFallbackProfit -> "✅"
                else -> "❌"
            }
        } else {
            if (odds > (config?.minOdds ?: 0.0)) "✅" else "❌"
        }

        val dataEnough =
            (matchInfo.homeMatchesLastYear ?: 0) > 5 && (matchInfo.awayMatchesLastYear ?: 0) > 5
        val dataCheck = if (dataEnough) "✅" else "❌"

        val probabilityLine = when {
            probability == 0.0 -> "- Probability ❌ no data available"
            drawBalancedProb -> "- Probability < 40%, diff xG < 0.1 ✅"
            drawHighProb -> "- Probability >= ${(minProb * 100).toInt()}% ✅"
            probability >= minProb -> "- Probability >= ${(minProb * 100).toInt()}% ✅"
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
