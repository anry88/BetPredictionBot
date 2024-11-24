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
    val awayWinOdds: String?
)

