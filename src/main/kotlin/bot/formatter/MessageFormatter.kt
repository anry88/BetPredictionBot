package bot.formatter

import dto.MatchInfo
import dto.OutcomeType
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

    private fun parsePredictedScore(score: String?): Pair<Int, Int>? {
        val parts = score?.split(":")?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val home = parts[0].toIntOrNull() ?: return null
        val away = parts[1].toIntOrNull() ?: return null
        return home to away
    }

    private fun outcomeTypeFromPrediction(matchInfo: MatchInfo): OutcomeType? {
        val score = parsePredictedScore(matchInfo.predictedScore)
        if (score != null) {
            val (home, away) = score
            return when {
                home > away -> OutcomeType.HomeWin
                away > home -> OutcomeType.AwayWin
                else -> OutcomeType.Draw
            }
        }

        val predictedOutcome = matchInfo.predictedOutcome?.trim() ?: return null
        val teams = matchInfo.teams.split(" vs. ")
        val homeTeam = teams.getOrNull(0)?.trim()
        val awayTeam = teams.getOrNull(1)?.trim()

        return when {
            predictedOutcome.equals("Draw", ignoreCase = true) -> OutcomeType.Draw
            homeTeam != null && predictedOutcome.equals(homeTeam, ignoreCase = true) -> OutcomeType.HomeWin
            awayTeam != null && predictedOutcome.equals(awayTeam, ignoreCase = true) -> OutcomeType.AwayWin
            else -> null
        }
    }

    private fun strategyOutcomeType(matchInfo: MatchInfo): OutcomeType? {
        return StrategyService.getModelPreferredOutcome(matchInfo)
    }

    // --- Main channel ---
    fun formatMainUpcomingMatch(matchInfo: MatchInfo, tags: String, includeTestData: Boolean): String {
        val timeLeft = timeUntil(matchInfo.datetime, ZoneId.of("UTC"))
        val testData = if (includeTestData) "\n${formatTestData(matchInfo, includeCalibrated = true)}" else ""
        return """${matchInfo.datetime} UTC (${timeLeft})
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}
Predicted score: ${matchInfo.predictedScore}$testData
$tags""".trimIndent()
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
        return """${matchInfo.datetime} UTC
${matchInfo.teams}
Predicted outcome: ${matchInfo.predictedOutcome}$emoji
Predicted score: ${matchInfo.predictedScore}
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}$testData
$tags""".trimIndent()
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
    fun formatDirectUpcomingMatch(matchInfo: MatchInfo, timezone: String = "UTC"): String {
        val testData = formatTestData(matchInfo, includeCalibrated = false).trimEnd()
        val outcomeType = outcomeTypeFromPrediction(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
        val predictedScore = matchInfo.predictedScore
        val timeLeft = timeUntil(matchInfo.datetime, ZoneId.of(timezone))
        val currentLine = matchInfo.elapsed?.let { "\nCurrent: ${matchInfo.actualScore} ${it}'" } ?: ""
        val oddsLine = if (!matchInfo.odds.isNullOrBlank()) "\nOdds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})" else ""
        return """
${matchInfo.datetime} $timezone (${timeLeft})
${matchInfo.teams}
Predicted outcome: $outcomeLabel
Predicted score: ${predictedScore}$currentLine$oddsLine
$testData""".trimIndent()
    }

    fun formatDirectCompletedMatch(matchInfo: MatchInfo, timezone: String = "UTC"): String {
        val testData = formatTestData(matchInfo, includeCalibrated = false).trimEnd()
        val outcomeType = outcomeTypeFromPrediction(matchInfo)
        val outcomeLabel = outcomeType?.let { resolveOutcomeLabel(matchInfo, it) } ?: matchInfo.predictedOutcome
        val predictedScore = matchInfo.predictedScore
        val isPredictionCorrect = outcomeLabel?.equals(matchInfo.actualOutcome, ignoreCase = true) == true
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val oddsLine = if (!matchInfo.odds.isNullOrBlank()) "\nOdds for outcome: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})" else ""
        return """
${matchInfo.datetime} $timezone
${matchInfo.teams}
Predicted outcome: $outcomeLabel$emoji
Predicted score: ${predictedScore}$oddsLine
Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
$testData""".trimIndent()
    }


}
