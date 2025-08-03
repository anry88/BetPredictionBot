package repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object UserSettings : Table("user_settings") {
    val userId = varchar("user_id", 50)
    val timezone = varchar("timezone", 50).default("UTC")
    override val primaryKey = PrimaryKey(userId)
}

class UserSettingsRepository {
    fun setTimezone(userId: String, timezone: String) = transaction {
        val existing = UserSettings.select { UserSettings.userId eq userId }.singleOrNull()
        if (existing == null) {
            UserSettings.insert {
                it[UserSettings.userId] = userId
                it[UserSettings.timezone] = timezone
            }
        } else {
            UserSettings.update({ UserSettings.userId eq userId }) {
                it[UserSettings.timezone] = timezone
            }
        }
    }

    fun getTimezone(userId: String): String? = transaction {
        UserSettings.select { UserSettings.userId eq userId }
            .map { it[UserSettings.timezone] }
            .singleOrNull()
    }
}
