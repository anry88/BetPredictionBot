package bot.formatter

import dto.MatchInfo
import dto.LeagueConfig
import dto.OutcomeType
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

    private fun formatTestData(matchInfo: MatchInfo, includeCalibrated: Boolean): String {
        val homeProb = matchInfo.modelHomeWinProb?.times(100)?.let { "%.2f%%".format(it) } ?: "0%"
        val drawProb = matchInfo.modelDrawProb?.times(100)?.let { "%.2f%%".format(it) } ?: "0%"
        val awayProb = matchInfo.modelAwayWinProb?.times(100)?.let { "%.2f%%".format(it) } ?: "0%"

        val calibrationLine = if (includeCalibrated) {
            val calibratedHomeProb = matchInfo.calibratedHomeWinProb?.times(100)?.let { "%.2f%%".format(it) }
            val calibratedDrawProb = matchInfo.calibratedDrawProb?.times(100)?.let { "%.2f%%".format(it) }
            val calibratedAwayProb = matchInfo.calibratedAwayWinProb?.times(100)?.let { "%.2f%%".format(it) }
            if (calibratedHomeProb != null && calibratedDrawProb != null && calibratedAwayProb != null) {
                val applied = matchInfo.calibrationApplied ?: false
                "\nCalibrated: $calibratedHomeProb - $calibratedDrawProb - $calibratedAwayProb (applied: $applied)"
            } else ""
        } else ""

        val homeXg = matchInfo.modelExpectedHomeGoals?.let { "%.2f".format(it) } ?: "0"
        val awayXg = matchInfo.modelExpectedAwayGoals?.let { "%.2f".format(it) } ?: "0"
        val calibratedXgLine = if (includeCalibrated) {
            val calibratedHomeXg = matchInfo.calibratedExpectedHomeGoals?.let { "%.2f".format(it) }
            val calibratedAwayXg = matchInfo.calibratedExpectedAwayGoals?.let { "%.2f".format(it) }
            if (calibratedHomeXg != null && calibratedAwayXg != null) {
                "\nCalibrated Expected Goals: $calibratedHomeXg : $calibratedAwayXg"
            } else ""
        } else ""

        val homeOdds = matchInfo.homeWinOdds ?: "0"
        val drawOdds = matchInfo.drawOdds ?: "0"
        val awayOdds = matchInfo.awayWinOdds ?: "0"

        return """
Probabilities: $homeProb - $drawProb - $awayProb
Expected Goals: $homeXg : $awayXg$calibratedXgLine
Odds: $homeOdds - $drawOdds - $awayOdds
${calibrationLine.trimStart()}
""".trimIndent()
    }

    private fun resolveOutcomeLabel(matchInfo: MatchInfo, outcomeType: OutcomeType): String? {
        val teams = matchInfo.teams.split(" vs. ")
        val homeTeam = teams.getOrNull(0)?.trim()
        val awayTeam = teams.getOrNull(1)?.trim()
        return when (outcomeType) {
            OutcomeType.HomeWin -> homeTeam
            OutcomeType.Draw -> "Draw"
            OutcomeType.AwayWin -> awayTeam
        }
    }

    private fun strategyOutcomeType(matchInfo: MatchInfo): OutcomeType? {
        return StrategyService.getModelPreferredOutcome(matchInfo)
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
            val testData = if (includeTestData) "\n${formatTestData(matchInfo, includeCalibrated = true)}" else ""
            """${matchInfo.datetime} UTC (${timeLeft})
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}
Predicted score: ${matchInfo.predictedScore}$testData
$tags""".trimIndent()
        }
    }

    fun formatMainLiveMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val testData = if (includeTestData) "\n${formatTestData(matchInfo, includeCalibrated = true)}" else ""
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
        val testData = if (includeTestData) "\n${formatTestData(matchInfo, includeCalibrated = true)}" else ""
        val isPremium = outcomeStrategyConfigs.any { StrategyService.isMatchFitsStrategy(matchInfo, it) }
        return if (isPremium) {
            """$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}$emoji
Predicted score: ${matchInfo.predictedScore}
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
        val outcomeType = strategyOutcomeType(matchInfo) ?: return 0.0
        return (StrategyService.getOutcomeProbability(matchInfo, outcomeType) ?: 0.0) * 100
    }

    fun formatPremiumUpcomingMatch(matchInfo: MatchInfo): String {
        val outcomeType = strategyOutcomeType(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
        val probability = predictedOutcomeProbability(matchInfo)
        val timeLeft = timeUntil(matchInfo.datetime, ZoneId.of("UTC"))
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC (${timeLeft})
${matchInfo.teams}
Predicted outcome: $outcomeLabel (${"%.2f".format(probability)}%)
Predicted score: ${matchInfo.predictedScore}
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
""".trimIndent()
    }

    fun formatPremiumLiveMatch(matchInfo: MatchInfo): String {
        val outcomeType = strategyOutcomeType(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
        val probability = predictedOutcomeProbability(matchInfo)
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: $outcomeLabel (${"%.2f".format(probability)}%)
Predicted score: ${matchInfo.predictedScore}
Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
#Live
""".trimIndent()
    }

    fun formatPremiumCompletedMatch(matchInfo: MatchInfo): String {
        val outcomeType = strategyOutcomeType(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
        val probability = predictedOutcomeProbability(matchInfo)
        val isPredictionCorrect = outcomeLabel?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        return """
$PREMIUM_HEADER
${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: $outcomeLabel$emoji (${"%.2f".format(probability)}%)
Predicted score: ${matchInfo.predictedScore}
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
""".trimIndent()
    }

    // --- Direct messages ---
    fun formatDirectUpcomingMatch(matchInfo: MatchInfo, league: LeagueConfig?, timezone: String = "UTC"): String {
        val analysis = buildPredictionAnalysis(matchInfo, league)
        val testData = formatTestData(matchInfo, includeCalibrated = false)
        val outcomeType = strategyOutcomeType(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
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
Predicted outcome: $outcomeLabel
Predicted score: ${matchInfo.predictedScore}$currentLine
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
$testData
$analysis""".trimIndent()
    }

    fun formatDirectCompletedMatch(matchInfo: MatchInfo, league: LeagueConfig?, timezone: String = "UTC"): String {
        val analysis = buildPredictionAnalysis(matchInfo, league)
        val testData = formatTestData(matchInfo, includeCalibrated = false)
        val outcomeType = strategyOutcomeType(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
        val isPredictionCorrect = outcomeLabel?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val premiumHeader = if (outcomeStrategyConfigs.any { StrategyService.isMatchFitsStrategy(matchInfo, it) }) {
            "$PREMIUM_HEADER\n"
        } else {
            ""
        }
        return """
${premiumHeader}${matchInfo.datetime} $timezone
${matchInfo.teams}
Predicted outcome: $outcomeLabel$emoji
Predicted score: ${matchInfo.predictedScore}
Odds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
$testData
$analysis""".trimIndent()
    }

    private fun buildPredictionAnalysis(matchInfo: MatchInfo, league: LeagueConfig?): String {
        val outcomeType = strategyOutcomeType(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome ?: "Unknown"
        if (outcomeType == OutcomeType.HomeWin) {
            return """
            Prediction Analysis:
            - Outcome: $outcomeLabel ❌ (home win is not eligible for premium selection)
        """.trimIndent()
        }
        if (outcomeType == null) {
            return """
            Prediction Analysis:
            - Outcome: $outcomeLabel ❌ (unable to evaluate premium selection)
        """.trimIndent()
        }
        val config = outcomeStrategyConfigs.firstOrNull { it.outcomeType == outcomeType }
        val probability = StrategyService.getOutcomeProbability(matchInfo, outcomeType) ?: 0.0
        val odds = StrategyService.getOutcomeOdds(matchInfo, outcomeType) ?: 0.0

        val leagueCheck = if (league?.premiumSelection == true) "✅" else "❌"
        val dataEnough = (matchInfo.homeMatchesLastYear ?: 0) > 5 && (matchInfo.awayMatchesLastYear ?: 0) > 5
        val dataCheck = if (dataEnough) "✅" else "❌"

        val outcomeLine = "- Outcome: $outcomeLabel ✅"
        val probabilityLine = when {
            probability == 0.0 -> "- Probability ❌ no data available"
            config == null -> "- Probability ✅ model-based"
            probability < config.minProb -> "- Probability >= ${(config.minProb * 100).toInt()}% ❌"
            config.maxProb != null && probability > config.maxProb -> "- Probability <= ${(config.maxProb * 100).toInt()}% ❌"
            else -> "- Probability within target range ✅"
        }

        val oddsCheck = if (config != null && odds in config.minOdds..config.maxOdds) "✅" else "❌"

        val xgCheck = if (outcomeType == OutcomeType.Draw && config != null) {
            val expectedHomeGoals = matchInfo.modelExpectedHomeGoals
            val expectedAwayGoals = matchInfo.modelExpectedAwayGoals
            if (expectedHomeGoals != null && expectedAwayGoals != null) {
                val diff = kotlin.math.abs(expectedHomeGoals - expectedAwayGoals)
                val total = expectedHomeGoals + expectedAwayGoals
                val diffOk = config.maxXgDiff?.let { diff <= it } ?: true
                val totalOk = config.maxXgTotal?.let { total <= it } ?: true
                if (diffOk && totalOk) "✅" else "❌"
            } else {
                "❌"
            }
        } else null

        val xgLine = if (xgCheck != null) "- xG alignment $xgCheck" else ""

        return """
            Prediction Analysis:
            $outcomeLine
            $probabilityLine
            - League predictable $leagueCheck
            - Odds within range $oddsCheck
            - Enough data $dataCheck
            $xgLine
        """.trimIndent()
    }
}
