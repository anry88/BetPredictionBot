@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package dto

import kotlinx.serialization.Serializable

@Serializable
data class LeagueConfig(
    val leagueId: Int,
    val season: Int,
    val description: String,
    val premiumSelection: Boolean = false
) {
    fun matchesApiIdentity(leagueId: Int, season: Int): Boolean =
        this.leagueId == leagueId && this.season == season
}
