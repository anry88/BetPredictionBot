package dto

data class OddsInfo(
    val odds: Double,         // Коэффициент на прогнозируемый исход
    val bookmakerName: String,
    val homeWinOdds: Double?, // Коэффициент на победу хозяев
    val drawOdds: Double?,    // Коэффициент на ничью
    val awayWinOdds: Double?  // Коэффициент на победу гостей
)