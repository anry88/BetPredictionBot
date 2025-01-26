package dto

data class MatchInfo(
    val fixtureId: String,
    val datetime: String,
    val matchType: String,
    val teams: String,
    var predictedOutcome: String?,
    var actualOutcome: String?,
    var predictedScore: String?,
    var actualScore: String?,
    var odds: String?,  // Существующее поле для текущего функционала
    var bookmakerName: String?,  // Новое поле для имени букмекера
    var homeWinOdds: String?,    // Новое поле для коэффициента на победу хозяев
    var drawOdds: String?,       // Новое поле для коэффициента на ничью
    var awayWinOdds: String?,    // Новое поле для коэффициента на победу гостей
    var telegramMessageId: String?,
    var strategyTelegramMessageId: String?,
    var elapsed: Int?,
    var modelHomeWinProb: Double? = null,
    var modelDrawProb: Double? = null,
    var modelAwayWinProb: Double? = null,
    var modelExpectedHomeGoals: Double? = null,
    var modelExpectedAwayGoals: Double? = null
)
