package repository

data class Statistics(
    val totalMatches: Int,
    val correctPredictions: Int,
    val accuracy: Double,
    val roi: Double,
    val strategyTotalMatches: Int,
    val strategyCorrectPredictions: Int,
    val strategyAccuracy: Double,
    val strategyRoi: Double,
    val homeWinPredictions: Int,
    val homeWinSuccesses: Int,
    val homeWinAccuracy: Double,
    val homeWinRoi: Double,
    val drawPredictions: Int,
    val drawSuccesses: Int,
    val drawAccuracy: Double,
    val drawRoi: Double,
    val awayWinPredictions: Int,
    val awayWinSuccesses: Int,
    val awayWinAccuracy: Double,
    val awayWinRoi: Double
)

data class InviteLink(
    val id: Int,
    val inviteLink: String,
    val maxSubscribers: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val isActive: Boolean
)

data class InviteSubscriber(
    val id: Int,
    val inviteLink: String,
    val userId: String,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val joinedAt: Long
)

data class JoinRequest(
    val id: Long,
    val inviteLinkId: Long,
    val userId: String,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val status: String,
    val createdAt: Long,
    val maxSubscribers: Int,
    val expiresAt: Long
)

enum class SubscriptionType { BOT, CHANNEL }

enum class SubscriptionPlan(val type: SubscriptionType, val months: Int, val label: String) {
    BOT_1(SubscriptionType.BOT, 1, "Bot 1 month"),
    BOT_3(SubscriptionType.BOT, 3, "Bot 3 months"),
    CHANNEL_1(SubscriptionType.CHANNEL, 1, "Premium channel 1 month"),
    CHANNEL_3(SubscriptionType.CHANNEL, 3, "Premium channel 3 months");

    val callbackData: String get() = "buy_${type.name.lowercase()}_${months}"
}

data class PremiumSubscription(
    val userId: String,
    val type: SubscriptionType,
    val expiresAt: Long
)
