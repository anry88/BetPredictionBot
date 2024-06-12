package dto

data class MatchInfo(
    val datetime: String,
    val matchType: String,
    val teams: String,
    val outcome: String,
    val score: String,
    val odds: String
)
