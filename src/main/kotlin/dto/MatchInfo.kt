package dto

data class MatchInfo(
    val fixtureId: String?,
    val datetime: String,
    val matchType: String,
    val teams: String,
    var predictedOutcome: String?,
    var actualOutcome: String?,
    var predictedScore: String?,
    var actualScore: String?,
    var odds: String?,
    var telegramMessageId: String?
)
