package repository

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import service.initDatabase
import service.execSql

class PremiumSubscriptionRepositoryTest {
    private val repo = PremiumSubscriptionRepository()

    @BeforeTest
    fun setup() {
        initDatabase("predictions.db")
        execSql("DELETE FROM premium_subscriptions")
    }

    @Test
    fun revokeSubscriptionShortensExpiry() {
        val user = "user1"
        val type = SubscriptionType.BOT
        val expiry = repo.addOrUpdateSubscription(user, type, 3)
        repo.revokeSubscription(user, type, 1)
        val sub = repo.getSubscription(user, type)
        val expected = java.time.Instant.ofEpochSecond(expiry)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDateTime()
            .minusMonths(1)
            .toEpochSecond(java.time.ZoneOffset.UTC)
        assertEquals(expected, sub?.expiresAt)
    }

    @Test
    fun revokeSubscriptionDeletesWhenExpired() {
        val user = "user2"
        val type = SubscriptionType.BOT
        repo.addOrUpdateSubscription(user, type, 1)
        repo.revokeSubscription(user, type, 2)
        val sub = repo.getSubscription(user, type)
        assertNull(sub)
    }
}
