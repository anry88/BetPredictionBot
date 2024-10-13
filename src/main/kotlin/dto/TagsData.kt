@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package dto

import kotlinx.serialization.Serializable

@Serializable
data class TagsData(
    val leagues: Map<String, String>,
    val teams: Map<String, String>
)