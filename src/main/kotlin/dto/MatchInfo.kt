package dto

data class MatchInfo(
    val fixtureId: String,
    var datetime: String,
    val matchType: String,
    var teams: String,
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
    var modelHomeWinProb: Double? ,
    var modelDrawProb: Double?,
    var modelAwayWinProb: Double?,
    var modelExpectedHomeGoals: Double?,
    var modelExpectedAwayGoals: Double?,
    var calibratedExpectedHomeGoals: Double? = null,
    var calibratedExpectedAwayGoals: Double? = null,
    var calibratedHomeWinProb: Double? = null,
    var calibratedDrawProb: Double? = null,
    var calibratedAwayWinProb: Double? = null,
    var calibrationApplied: Boolean? = null,
    var homeMatchesLastYear: Int? = null,
    var awayMatchesLastYear: Int? = null,
    var predictedAt: String? = null
)
