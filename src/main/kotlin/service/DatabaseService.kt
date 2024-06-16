import dto.MatchInfo
import io.ktor.utils.io.errors.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MatchInfos : Table() {
    val id = integer("id").autoIncrement()
    val datetime = varchar("datetime", 50)
    val matchType = varchar("matchType", 50)
    val teams = varchar("teams", 100)
    val outcome = varchar("outcome", 50)
    val score = varchar("score", 50)
    val odds = varchar("odds", 50)

    override val primaryKey = PrimaryKey(id)
}
object UserStats : Table() {
    val id = integer("id").autoIncrement()
    val userId = varchar("userId", 50)
    val firstName = varchar("firstName", 50).nullable()
    val lastName = varchar("lastName", 50).nullable()
    val username = varchar("username", 50).nullable()
    val lastActivity = varchar("lastActivity", 50)

    override val primaryKey = PrimaryKey(id)
}

fun initDatabase(dbPath: String) {
    val logger = LoggerFactory.getLogger("DatabaseService")
    val dbFile = File(dbPath)

    logger.info("Database file path: $dbPath")

    if (!dbFile.exists()) {
        try {
            dbFile.createNewFile()
            logger.info("Database file created at: $dbPath")
        } catch (e: IOException) {
            logger.error("Failed to create database file", e)
            throw e
        }
    } else {
        logger.info("Database file already exists at: $dbPath")
    }

    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.createMissingTablesAndColumns(MatchInfos, UserStats)
        logger.info("Database initialized and tables 'MatchInfos' and 'UserStats' ensured.")
    }
}

object DatabaseService {
    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun appendRows(matches: List<MatchInfo>) {
        transaction {
            matches.forEach { match ->
                logger.info("Inserting match: ${match.teams} at ${match.datetime}")
                MatchInfos.insert {
                    it[datetime] = match.datetime
                    it[matchType] = match.matchType
                    it[teams] = match.teams
                    it[outcome] = match.outcome
                    it[score] = match.score
                    it[odds] = match.odds
                }
            }
        }
    }

    fun getUpcomingMatches(): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val tomorrow = now.plusDays(1)
        return transaction {
            MatchInfos.selectAll().mapNotNull {
                val matchDateTime = LocalDateTime.parse(it[MatchInfos.datetime], dateTimeFormatter)
                    .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                if (matchDateTime.isAfter(now) && matchDateTime.isBefore(tomorrow)) {
                    MatchInfo(
                        it[MatchInfos.datetime],
                        it[MatchInfos.matchType],
                        it[MatchInfos.teams],
                        it[MatchInfos.outcome],
                        it[MatchInfos.score],
                        it[MatchInfos.odds]
                    )
                } else {
                    null
                }
            }
        }
    }

    fun matchExists(matchInfo: MatchInfo): Boolean {
        return transaction {
            MatchInfos.select { (MatchInfos.datetime eq matchInfo.datetime) and (MatchInfos.teams eq matchInfo.teams) }
                .count() > 0
        }
    }

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
                logger.info("Added new user: $userId")
            } else {
                UserStats.update({ UserStats.userId eq userId }) {
                    it[UserStats.firstName] = firstName
                    it[UserStats.lastName] = lastName
                    it[UserStats.username] = username
                    it[lastActivity] = now
                }
                logger.info("Updated user activity: $userId")
            }
        }
    }

    fun getUserCount(): Long {
        return transaction {
            UserStats.selectAll().count()
        }
    }
}
