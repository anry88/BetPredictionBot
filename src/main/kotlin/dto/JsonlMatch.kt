@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package dto

import kotlinx.serialization.Serializable

@Serializable
data class JsonlMatch(
    val date: String,
    val matchType: String,
    val teams: String,
    val predictedScore: String?,
    val actualScore: String?,
    val predictedOutcome: String?,
    val actualOutcome: String?,
    val odds: String?,
    val bookmakerName: String?,
    val homeWinOdds: String?,
    val drawOdds: String?,
    val awayWinOdds: String?,
    val modelHomeWinProb: Double? = null,
    val modelDrawProb: Double? = null,
    val modelAwayWinProb: Double? = null,
    val modelExpectedHomeGoals: Double? = null,
    val modelExpectedAwayGoals: Double? = null,
    val calibratedExpectedHomeGoals: Double? = null,
    val calibratedExpectedAwayGoals: Double? = null,
    val calibratedHomeWinProb: Double? = null,
    val calibratedDrawProb: Double? = null,
    val calibratedAwayWinProb: Double? = null,
    val calibrationApplied: Boolean? = null,
    val predictedAt: String? = null,
    val neutralVenue: Boolean = false
)
