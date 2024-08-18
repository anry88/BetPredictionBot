package dto

data class MatchInfo(
    val datetime: String,
    val matchType: String,
    val teams: String,
    val predictedOutcome: String?,  // Прогнозируемый результат
    val actualOutcome: String?,     // Фактический результат
    val predictedScore: String?,    // Прогнозируемый счет
    val actualScore: String?,       // Фактический счет
    val odds: String?,               // Коэффициенты
    val telegramMessageId: String?
)
