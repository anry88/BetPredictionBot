import dto.MatchInfo
import io.ktor.utils.io.errors.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MatchInfos : Table() {
    val id = integer("id").autoIncrement()
    val datetime = varchar("datetime", 50)
    val matchType = varchar("matchType", 50)
    val teams = varchar("teams", 100)
    val predictedOutcome = varchar("predictedOutcome", 50).nullable()
    val actualOutcome = varchar("actualOutcome", 50).nullable()
    val predictedScore = varchar("predictedScore", 50).nullable()
    val actualScore = varchar("actualScore", 50).nullable()
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
    private val dateTimeFormatterForISOOffset = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun getMatchInfo(datetime: String, teams: String): MatchInfo? {
        return transaction {
            MatchInfos.select { (MatchInfos.datetime eq datetime) and (MatchInfos.teams eq teams) }
                .mapNotNull {
                    MatchInfo(
                        it[MatchInfos.datetime],
                        it[MatchInfos.matchType],
                        it[MatchInfos.teams],
                        it[MatchInfos.predictedOutcome],
                        it[MatchInfos.actualOutcome],
                        it[MatchInfos.predictedScore],
                        it[MatchInfos.actualScore],
                        it[MatchInfos.odds]
                    )
                }
                .singleOrNull()
        }
    }

    // Метод для вставки списка матчей
    fun appendRows(matches: List<MatchInfo>) {
        transaction {
            matches.forEach { match ->
                MatchInfos.insert {
                    it[datetime] = match.datetime
                    it[matchType] = match.matchType
                    it[teams] = match.teams
                    it[predictedOutcome] = match.predictedOutcome
                    it[actualOutcome] = match.actualOutcome
                    it[predictedScore] = match.predictedScore
                    it[actualScore] = match.actualScore
                    it[odds] = match.odds ?: ""
                }
                logger.info("Match info inserted for match: ${match.teams} at ${match.datetime}")
            }
        }
    }
    fun updateMatchResult(matchInfo: MatchInfo) {
        transaction {
            val dt = OffsetDateTime.parse(matchInfo.datetime, dateTimeFormatterForISOOffset)
                .format(dateTimeFormatter).toString()
            MatchInfos.update({ (MatchInfos.datetime eq dt) and (MatchInfos.teams eq matchInfo.teams) }) {
                it[actualOutcome] = matchInfo.actualOutcome
                it[actualScore] = matchInfo.actualScore
            }
            logger.info("Match result updated for match: ${matchInfo.teams} at ${matchInfo.datetime}")
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
                        it[MatchInfos.predictedOutcome],
                        it[MatchInfos.actualOutcome],
                        it[MatchInfos.predictedScore],
                        it[MatchInfos.actualScore],
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

    fun getActiveUserCountLast24Hours(): Long {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val last24Hours = now.minusDays(1)

        return transaction {
            UserStats.select { UserStats.lastActivity greaterEq last24Hours.format(dateTimeFormatter) }
                .count()
        }
    }
}
