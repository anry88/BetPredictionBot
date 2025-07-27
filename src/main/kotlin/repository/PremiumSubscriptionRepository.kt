package repository

import java.sql.Connection
import java.sql.DriverManager

import repository.SubscriptionType

class PremiumSubscriptionRepository {
    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun addOrUpdateSubscription(userId: String, type: SubscriptionType, months: Int): Long {
        val connection = getConnection()
        val currentTime = System.currentTimeMillis() / 1000
        val select = connection.prepareStatement("SELECT expires_at FROM premium_subscriptions WHERE user_id = ? AND type = ?")
        select.setString(1, userId)
        select.setString(2, type.name)
        val rs = select.executeQuery()
        val existing = if (rs.next()) rs.getLong("expires_at") else null
        rs.close()
        select.close()

        val baseInstant = if (existing != null && existing > currentTime) existing else currentTime
        val baseDateTime = java.time.Instant.ofEpochSecond(baseInstant)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDateTime()
        val newExpiry = baseDateTime.plusMonths(months.toLong())
            .toEpochSecond(java.time.ZoneOffset.UTC)
        val stmt = connection.prepareStatement(
            "INSERT INTO premium_subscriptions (user_id, type, expires_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(user_id, type) DO UPDATE SET expires_at = ?"
        )
        stmt.setString(1, userId)
        stmt.setString(2, type.name)
        stmt.setLong(3, newExpiry)
        stmt.setLong(4, newExpiry)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
        return newExpiry
    }

    fun getSubscription(userId: String, type: SubscriptionType): PremiumSubscription? {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "SELECT user_id, type, expires_at FROM premium_subscriptions WHERE user_id = ? AND type = ?"
        )
        stmt.setString(1, userId)
        stmt.setString(2, type.name)
        val rs = stmt.executeQuery()
        val sub = if (rs.next()) PremiumSubscription(rs.getString("user_id"), SubscriptionType.valueOf(rs.getString("type")), rs.getLong("expires_at")) else null
        rs.close()
        stmt.close()
        connection.close()
        return sub
    }

    fun isActive(userId: String, type: SubscriptionType): Boolean {
        val expiry = getSubscription(userId, type)?.expiresAt ?: return false
        return expiry > System.currentTimeMillis() / 1000
    }
}
