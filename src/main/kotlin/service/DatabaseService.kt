import MatchInfos.nullable
import dto.MatchInfo
import io.ktor.utils.io.errors.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
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
    val telegramMessageId = varchar("telegramMessageId", 50).nullable()

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

open class LeagueTable(tableName: String) : Table(tableName) {
    val id = integer("id").autoIncrement()
    val datetime = varchar("datetime", 50)
    val matchType = varchar("matchType", 50)
    val teams = varchar("teams", 100)
    val predictedOutcome = varchar("predictedOutcome", 50).nullable()
    val actualOutcome = varchar("actualOutcome", 50).nullable()
    val predictedScore = varchar("predictedScore", 50).nullable()
    val actualScore = varchar("actualScore", 50).nullable()
    val odds = varchar("odds", 50).nullable()
    val telegramMessageId = varchar("telegramMessageId", 50).nullable()

    override val primaryKey = PrimaryKey(id)
}
object LeagueTableFactory {
    private val tables = mutableMapOf<String, LeagueTable>()

    fun getTableForLeague(leagueName: String): LeagueTable {

        return tables.getOrPut(leagueName) {
            LeagueTable(leagueName.replace(" ", "_").toLowerCase())
        }
    }
}

fun initDatabase(dbPath: String) {
    val logger = LoggerFactory.getLogger("DatabaseService")
    val dbFile = File(dbPath)
    var listOfLeagues: ArrayList<String>

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
        SchemaUtils.createMissingTablesAndColumns(UserStats)
        logger.info("Database initialized and tables 'UserStats' ensured.")
    }
}


object DatabaseService {
    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)
    val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateTimeFormatterForISOOffset = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private val listOfLeagues = mutableSetOf<String>()

    fun getMatchInfo(datetime: String, teams: String, leagueName: String): MatchInfo? {
        val dt = OffsetDateTime.parse(datetime, dateTimeFormatterForISOOffset)
            .format(dateTimeFormatter).toString()
        return transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

            try {
                leagueTable.select {
                    (leagueTable.datetime eq dt) and
                            (leagueTable.teams eq teams)
                }.mapNotNull {
                    MatchInfo(
                        it[leagueTable.datetime],
                        it[leagueTable.matchType],
                        it[leagueTable.teams],
                        it[leagueTable.predictedOutcome],
                        it[leagueTable.actualOutcome],
                        it[leagueTable.predictedScore],
                        it[leagueTable.actualScore],
                        it[leagueTable.odds],
                        it[leagueTable.telegramMessageId]
                    )
                }.singleOrNull()
            } catch (e: ExposedSQLException) {
                if (e.message?.contains("no such table") == true) {
                    // Таблица не существует, создаем её и возвращаем null, так как запись не может существовать
                    SchemaUtils.createMissingTablesAndColumns(leagueTable)
                    logger.warn("Table for league $leagueName did not exist. Created new table.")
                    return@transaction null
                } else {
                    throw e
                }
            }
        }
    }


    // Метод для вставки списка матчей
    fun appendRows(matches: List<MatchInfo>) {
        transaction {
            matches.forEach { match ->
                val leagueTable = LeagueTableFactory.getTableForLeague(match.matchType)

                // Добавляем лигу в список, если её там еще нет
                if (!listOfLeagues.contains(match.matchType)) {
                    listOfLeagues.add(match.matchType)
                }

                SchemaUtils.createMissingTablesAndColumns(leagueTable)
                leagueTable.insert {
                    it[leagueTable.datetime] = match.datetime
                    it[leagueTable.matchType] = match.matchType
                    it[leagueTable.teams] = match.teams
                    it[leagueTable.predictedOutcome] = match.predictedOutcome
                    it[leagueTable.actualOutcome] = match.actualOutcome
                    it[leagueTable.predictedScore] = match.predictedScore
                    it[leagueTable.actualScore] = match.actualScore
                    it[leagueTable.odds] = match.odds ?: ""
                    it[leagueTable.telegramMessageId] = match.telegramMessageId
                }
                logger.info("Match info inserted for league: ${match.matchType}, match: ${match.teams} at ${match.datetime}")
            }
        }
    }

    fun updateMatchResult(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)

            try {
                leagueTable.update({ (leagueTable.datetime eq matchInfo.datetime) and (leagueTable.teams eq matchInfo.teams) }) {
                    it[leagueTable.actualOutcome] = matchInfo.actualOutcome
                    it[leagueTable.actualScore] = matchInfo.actualScore
                }
                logger.info("Match result updated for league: ${matchInfo.matchType}, match: ${matchInfo.teams} at ${matchInfo.datetime}")
            } catch (e: ExposedSQLException) {
                if (e.message?.contains("no such table") == true) {
                    // Таблица не существует, создаем её и повторяем попытку обновления
                    SchemaUtils.createMissingTablesAndColumns(leagueTable)
                    logger.warn("Table for league ${matchInfo.matchType} did not exist. Created new table.")
                    leagueTable.update({ (leagueTable.datetime eq matchInfo.datetime) and (leagueTable.teams eq matchInfo.teams) }) {
                        it[leagueTable.actualOutcome] = matchInfo.actualOutcome
                        it[leagueTable.actualScore] = matchInfo.actualScore
                    }
                    logger.info("Match result updated for league: ${matchInfo.matchType}, match: ${matchInfo.teams} at ${matchInfo.datetime} after table creation.")
                } else {
                    throw e
                }
            }
        }
    }

    fun updateMatchMessageId(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)

            leagueTable.update({ (leagueTable.datetime eq matchInfo.datetime) and (leagueTable.teams eq matchInfo.teams) }) {
                it[leagueTable.telegramMessageId] = matchInfo.telegramMessageId
            }
            logger.info("Telegram message ID updated for league: ${matchInfo.matchType}, match: ${matchInfo.teams} at ${matchInfo.datetime}")
        }
    }

    fun getUpcomingMatches(): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val tomorrow = now.plusDays(1)
        val allUpcomingMatches = mutableListOf<MatchInfo>()

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.selectAll().mapNotNullTo(allUpcomingMatches) {
                    val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                        .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                    if (matchDateTime.isAfter(now) && matchDateTime.isBefore(tomorrow)) {
                        MatchInfo(
                            it[leagueTable.datetime],
                            it[leagueTable.matchType],
                            it[leagueTable.teams],
                            it[leagueTable.predictedOutcome],
                            it[leagueTable.actualOutcome],
                            it[leagueTable.predictedScore],
                            it[leagueTable.actualScore],
                            it[leagueTable.odds],
                            it[leagueTable.telegramMessageId]
                        )
                    } else {
                        null
                    }
                }
            }
        }

        return allUpcomingMatches
    }

    fun matchExists(matchInfo: MatchInfo): Boolean {
        return transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)

            try {
                leagueTable.select {
                    (leagueTable.datetime eq matchInfo.datetime) and
                            (leagueTable.teams eq matchInfo.teams)
                }.count() > 0
            } catch (e: ExposedSQLException) {
                if (e.message?.contains("no such table") == true) {
                    // Таблица не существует, создаем её и возвращаем false, так как матч не может существовать
                    SchemaUtils.createMissingTablesAndColumns(leagueTable)
                    logger.warn("Table for league ${matchInfo.matchType} did not exist. Created new table.")
                    return@transaction false
                } else {
                    throw e
                }
            }
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

    fun getCorrectPredictionsLast24Hours(): Pair<Double, Pair<Int, Int>> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val last24Hours = now.minusDays(1)
        val allMatches = mutableListOf<MatchInfo>()

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.selectAll().mapNotNullTo(allMatches) {
                    val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                        .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                    if (matchDateTime.isAfter(last24Hours) && matchDateTime.isBefore(now)) {
                        MatchInfo(
                            it[leagueTable.datetime],
                            it[leagueTable.matchType],
                            it[leagueTable.teams],
                            it[leagueTable.predictedOutcome],
                            it[leagueTable.actualOutcome],
                            it[leagueTable.predictedScore],
                            it[leagueTable.actualScore],
                            it[leagueTable.odds],
                            it[leagueTable.telegramMessageId]
                        )
                    } else {
                        null
                    }
                }
            }
        }

        val totalMatches = allMatches.size
        val correctPredictions = allMatches.count { it.predictedOutcome == it.actualOutcome }

        val accuracy = if (totalMatches > 0) {
            (correctPredictions.toDouble() / totalMatches) * 100
        } else {
            0.0
        }

        return Pair(accuracy, Pair(correctPredictions, totalMatches))
    }
}
