package repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object UserStats : Table() {
    private val id = integer("id").autoIncrement()
    val userId = varchar("userId", 50)
    val firstName = varchar("firstName", 50).nullable()
    val lastName = varchar("lastName", 50).nullable()
    val username = varchar("username", 50).nullable()
    val lastActivity = varchar("lastActivity", 50)

    override val primaryKey = PrimaryKey(id)
}

class UserStatsRepository {
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun addUserActivity(userId: String, firstName: String?, lastName: String?, username: String?) {
        val now = LocalDateTime.now(ZoneId.of("UTC+3")).format(dateTimeFormatter)
        transaction {
            val existingUser = UserStats.select { UserStats.userId eq userId }.singleOrNull()
            if (existingUser == null) {
                UserStats.insert {
                    it[UserStats.userId] = userId
                    it[UserStats.firstName] = firstName
                    it[UserStats.lastName] = lastName
                    it[UserStats.username] = username
                    it[lastActivity] = now
                }
            } else {
                UserStats.update({ UserStats.userId eq userId }) {
                    it[UserStats.firstName] = firstName
                    it[UserStats.lastName] = lastName
                    it[UserStats.username] = username
                    it[lastActivity] = now
                }
            }
        }
    }

    fun getUserCount(): Long = transaction { UserStats.selectAll().count() }

    fun getActiveUserCountLast24Hours(): Long {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val last24Hours = now.minusDays(1)
        return transaction {
            UserStats.select { UserStats.lastActivity greaterEq last24Hours.format(dateTimeFormatter) }.count()
        }
    }
}
