package service

import dto.MatchInfo
import io.ktor.utils.io.errors.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private object UserStats : Table() {
    private val id = integer("id").autoIncrement()
    val userId = varchar("userId", 50)
    val firstName = varchar("firstName", 50).nullable()
    val lastName = varchar("lastName", 50).nullable()
    val username = varchar("username", 50).nullable()
    val lastActivity = varchar("lastActivity", 50)

    override val primaryKey = PrimaryKey(id)
}
object Leagues : Table() {
    val name = varchar("name", 100).uniqueIndex()
    override val primaryKey = PrimaryKey(name)
}

open class LeagueTable(tableName: String) : Table(tableName) {
    private val id = integer("id").autoIncrement()
    val fixtureId = varchar("fixtureId", 50).uniqueIndex()
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
            LeagueTable(leagueName.replace(" ", "_").lowercase())
        }
    }
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
        SchemaUtils.createMissingTablesAndColumns(UserStats, Leagues)
        logger.info("Database initialized and tables 'UserStats' ensured.")
    }

}


object DatabaseService {
    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val listOfLeagues = mutableSetOf<String>()
    private fun loadLeagues() {
        transaction {
            Leagues.selectAll().forEach {
                listOfLeagues.add(it[Leagues.name])
            }
        }
    }

    init {
        loadLeagues()
    }

    fun getMatchInfo(fixtureId: String, leagueName: String): MatchInfo? {
        return transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

            try {
                leagueTable.select {
                    leagueTable.fixtureId eq fixtureId
                }.mapNotNull {
                    MatchInfo(
                        it[leagueTable.fixtureId],
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

                // Добавляем лигу в базу данных, если её там еще нет
                if (!listOfLeagues.contains(match.matchType)) {
                    Leagues.insertIgnore {
                        it[name] = match.matchType
                    }
                    listOfLeagues.add(match.matchType)
                }

                SchemaUtils.createMissingTablesAndColumns(leagueTable)
                leagueTable.insert {
                    it[leagueTable.fixtureId] = match.fixtureId
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
                leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                    it[leagueTable.actualOutcome] = matchInfo.actualOutcome
                    it[leagueTable.actualScore] = matchInfo.actualScore
                }
                logger.info("Match result updated for league: ${matchInfo.matchType}, match: ${matchInfo.teams} at ${matchInfo.datetime}")
            } catch (e: ExposedSQLException) {
                if (e.message?.contains("no such table") == true) {
                    // Таблица не существует, создаем её и повторяем попытку обновления
                    SchemaUtils.createMissingTablesAndColumns(leagueTable)
                    logger.warn("Table for league ${matchInfo.matchType} did not exist. Created new table.")
                    leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
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

            leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
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
                            it[leagueTable.fixtureId],
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
            // Создаем таблицу, если она не существует
            SchemaUtils.createMissingTablesAndColumns(leagueTable)
            leagueTable.select {
                leagueTable.fixtureId eq matchInfo.fixtureId
            }.count() > 0
        }
    }

    fun updateMatchPredictions(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
            leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                it[predictedOutcome] = matchInfo.predictedOutcome
                it[predictedScore] = matchInfo.predictedScore
                it[odds] = matchInfo.odds
            }
            logger.info("Updated predictions for match ${matchInfo.teams} at ${matchInfo.datetime}")
        }
    }

    fun deleteMatchByFixtureId(fixtureId: String, leagueName: String) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            leagueTable.deleteWhere { leagueTable.fixtureId eq fixtureId }
            logger.info("Deleted match with fixtureId $fixtureId from league $leagueName")
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

    fun getMatchesWithoutMessageIdForNext5Hours(): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val fiveHoursLater = now.plusHours(2)
        val matchesToSend = mutableListOf<MatchInfo>()

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.select {
                    (leagueTable.datetime greaterEq now.format(dateTimeFormatter)) and
                            (leagueTable.datetime lessEq fiveHoursLater.format(dateTimeFormatter)) and
                            (leagueTable.telegramMessageId.isNull())
                }.mapNotNullTo(matchesToSend) {
                    MatchInfo(
                        it[leagueTable.fixtureId],
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
                }
            }
        }

        return matchesToSend
    }
    fun getCorrectPredictionsForPeriod(days: Int): Pair<Double, Pair<Int, Int>> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val startDate = now.minusDays(days.toLong())
        val allMatches = mutableListOf<MatchInfo>()

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.selectAll().mapNotNullTo(allMatches) {
                    val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                        .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now) && it[leagueTable.actualOutcome] != null) {
                        MatchInfo(
                            it[leagueTable.fixtureId],
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
        val correctPredictions = allMatches.count { it.predictedOutcome?.lowercase() == it.actualOutcome?.lowercase() }

        val accuracy = if (totalMatches > 0) {
            (correctPredictions.toDouble() / totalMatches) * 100
        } else {
            0.0
        }

        return Pair(accuracy, Pair(correctPredictions, totalMatches))
    }

}
