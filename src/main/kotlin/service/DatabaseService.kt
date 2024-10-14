package service

import dto.LeagueStats
import dto.MatchInfo
import dto.PeriodStats
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
    val strategyTelegramMessageId = varchar("strategyTelegramMessageId", 50).nullable()

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

object LeaguePredictability : Table() {
    val leagueName = varchar("leagueName", 100)
    val roi = double("roi").default(0.0)
    val accuracy = double("accuracy").default(0.0)
    // Новые поля для стратегии
    val strategyRoi = double("strategyRoi").default(0.0)
    val strategyAccuracy = double("strategyAccuracy").default(0.0)

    override val primaryKey = PrimaryKey(leagueName)
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
        SchemaUtils.createMissingTablesAndColumns(UserStats, Leagues, LeaguePredictability)
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

    fun getAllLeagues(): List<String> {
        return listOfLeagues.toList()
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
                    it[leagueTable.datetime] = matchInfo.datetime
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

    fun updateMatchStrategyMessageId(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
            leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                it[leagueTable.strategyTelegramMessageId] = matchInfo.strategyTelegramMessageId
            }
            logger.info("Strategy Telegram message ID updated for league: ${matchInfo.matchType}, match: ${matchInfo.teams} at ${matchInfo.datetime}")
        }
    }


    fun updateMatchDatetime(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)

            leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                it[leagueTable.datetime] = matchInfo.datetime
            }
            logger.info("Datetime updated for league: ${matchInfo.matchType}, match: ${matchInfo.teams} at ${matchInfo.datetime}")
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
                            it[leagueTable.telegramMessageId],
                            it[leagueTable.strategyTelegramMessageId],
                            null
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

    fun getOngoingMatches(): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val threeHoursAgo = now.minusHours(6)
        val actualNow = now.minusHours(3)
        val matchesToUpdate = mutableListOf<MatchInfo>()

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                SchemaUtils.createMissingTablesAndColumns(leagueTable)

                leagueTable.selectAll().mapNotNullTo(matchesToUpdate) {
                    val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                    val isWithinTimeWindow = matchDateTime.isAfter(threeHoursAgo) && matchDateTime.isBefore(actualNow)

                    if (isWithinTimeWindow) {
                        MatchInfo(
                            fixtureId = it[leagueTable.fixtureId],
                            datetime = it[leagueTable.datetime],
                            matchType = it[leagueTable.matchType],
                            teams = it[leagueTable.teams],
                            predictedOutcome = it[leagueTable.predictedOutcome],
                            actualOutcome = it[leagueTable.actualOutcome],
                            predictedScore = it[leagueTable.predictedScore],
                            actualScore = it[leagueTable.actualScore],
                            odds = it[leagueTable.odds],
                            telegramMessageId = it[leagueTable.telegramMessageId],
                            strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                            null
                        )
                    } else {
                        null
                    }
                }
            }
        }
        return matchesToUpdate
    }


    fun getMatchInfoByFixtureId(fixtureId: String): MatchInfo? {
        var matchInfo: MatchInfo? = null
        transaction {
            for (leagueName in listOfLeagues) {
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                val result = leagueTable.select {
                    leagueTable.fixtureId eq fixtureId
                }.mapNotNull {
                    MatchInfo(
                        fixtureId = it[leagueTable.fixtureId],
                        datetime = it[leagueTable.datetime],
                        matchType = it[leagueTable.matchType],
                        teams = it[leagueTable.teams],
                        predictedOutcome = it[leagueTable.predictedOutcome],
                        actualOutcome = it[leagueTable.actualOutcome],
                        predictedScore = it[leagueTable.predictedScore],
                        actualScore = it[leagueTable.actualScore],
                        odds = it[leagueTable.odds],
                        telegramMessageId = it[leagueTable.telegramMessageId],
                        strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                        null
                    )
                }.singleOrNull()
                if (result != null) {
                    matchInfo = result
                    break
                }
            }
        }
        return matchInfo
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
                SchemaUtils.createMissingTablesAndColumns(leagueTable)

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
                        it[leagueTable.telegramMessageId],
                        it[leagueTable.strategyTelegramMessageId],
                        null
                    )
                }
            }
        }

        return matchesToSend
    }
    fun getStatisticsForPeriod(days: Int): PeriodStats {
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
                            it[leagueTable.telegramMessageId],
                            it[leagueTable.strategyTelegramMessageId],
                            null
                        )
                    } else {
                        null
                    }
                }
            }
        }

        var totalMatches = 0
        var correctPredictions = 0
        var totalStakes = 0.0
        var totalReturns = 0.0

        var strategyTotalMatches = 0
        var strategyCorrectPredictions = 0
        var strategyTotalStakes = 0.0
        var strategyTotalReturns = 0.0

        val predictableLeagues = getPredictableLeagues(strategyRoiThreshold = 10.0, strategyAccuracyThreshold = 60.0)

        allMatches.forEach { match ->
            totalMatches += 1
            val oddsValue = match.odds?.toDoubleOrNull() ?: return@forEach
            val stake = 100.0
            totalStakes += stake

            if (match.predictedOutcome?.lowercase() == match.actualOutcome?.lowercase()) {
                correctPredictions += 1
                val profit = (oddsValue * stake) - stake
                totalReturns += profit
            } else {
                totalReturns -= stake
            }

            // Проверяем, соответствует ли матч стратегии
            val teams = match.teams.split(" vs. ")
            if (teams.size == 2) {
                val homeTeam = teams[0].trim()
                val predictedOutcome = match.predictedOutcome
                val isHomeTeamPredicted = predictedOutcome == homeTeam
                val isPredictableLeague = match.matchType in predictableLeagues
                val hasStrategyTelegramMessageId = match.strategyTelegramMessageId != null
                val isOddsInRange = oddsValue in 1.20..2.20
                val isNotDraw = predictedOutcome != "Draw"

                if (isHomeTeamPredicted && hasStrategyTelegramMessageId && isOddsInRange && isNotDraw) {
                    strategyTotalMatches += 1
                    strategyTotalStakes += stake

                    if (match.predictedOutcome?.lowercase() == match.actualOutcome?.lowercase()) {
                        strategyCorrectPredictions += 1
                        val profit = (oddsValue * stake) - stake
                        strategyTotalReturns += profit
                    } else {
                        strategyTotalReturns -= stake
                    }
                }
            }
        }

        val accuracy = if (totalMatches > 0) {
            (correctPredictions.toDouble() / totalMatches) * 100
        } else {
            0.0
        }

        val roi = if (totalStakes > 0) {
            (totalReturns / totalStakes) * 100
        } else {
            0.0
        }

        val strategyAccuracy = if (strategyTotalMatches > 0) {
            (strategyCorrectPredictions.toDouble() / strategyTotalMatches) * 100
        } else {
            0.0
        }

        val strategyRoi = if (strategyTotalStakes > 0) {
            (strategyTotalReturns / strategyTotalStakes) * 100
        } else {
            0.0
        }

        return PeriodStats(
            totalMatches = totalMatches,
            correctPredictions = correctPredictions,
            totalStakes = totalStakes,
            totalReturns = totalReturns,
            accuracy = accuracy,
            roi = roi,
            strategyTotalMatches = strategyTotalMatches,
            strategyCorrectPredictions = strategyCorrectPredictions,
            strategyTotalStakes = strategyTotalStakes,
            strategyTotalReturns = strategyTotalReturns,
            strategyAccuracy = strategyAccuracy,
            strategyRoi = strategyRoi
        )
    }

    fun getAllMatches(): List<MatchInfo> {
        val allMatches = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.selectAll().mapNotNullTo(allMatches) {
                    MatchInfo(
                        fixtureId = it[leagueTable.fixtureId],
                        datetime = it[leagueTable.datetime],
                        matchType = it[leagueTable.matchType],
                        teams = it[leagueTable.teams],
                        predictedOutcome = it[leagueTable.predictedOutcome],
                        actualOutcome = it[leagueTable.actualOutcome],
                        predictedScore = it[leagueTable.predictedScore],
                        actualScore = it[leagueTable.actualScore],
                        odds = it[leagueTable.odds],
                        telegramMessageId = it[leagueTable.telegramMessageId],
                        strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                        elapsed = null
                    )
                }
            }
        }
        return allMatches
    }

    fun updateLeaguePredictability(leagueStatsMap: Map<String, LeagueStats>) {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(LeaguePredictability)
            leagueStatsMap.values.forEach { stats ->
                val updatedRows = LeaguePredictability.update({ LeaguePredictability.leagueName eq stats.leagueName }) {
                    it[roi] = stats.roi
                    it[accuracy] = stats.accuracy
                    it[strategyRoi] = stats.strategyRoi
                    it[strategyAccuracy] = stats.strategyAccuracy
                }

                if (updatedRows == 0) {
                    LeaguePredictability.insert {
                        it[leagueName] = stats.leagueName
                        it[roi] = stats.roi
                        it[accuracy] = stats.accuracy
                        it[strategyRoi] = stats.strategyRoi
                        it[strategyAccuracy] = stats.strategyAccuracy
                    }
                    logger.info("Inserted new league predictability data for league: ${stats.leagueName}")
                } else {
                    logger.info("Updated league predictability data for league: ${stats.leagueName}")
                }
            }
        }
    }

    fun getPredictableLeagues(strategyRoiThreshold: Double, strategyAccuracyThreshold: Double): List<String> {

        return transaction {
            SchemaUtils.createMissingTablesAndColumns(LeaguePredictability)
            LeaguePredictability.select {
                (LeaguePredictability.strategyRoi greaterEq strategyRoiThreshold) and
                        (LeaguePredictability.strategyAccuracy greaterEq strategyAccuracyThreshold)
            }.map { it[LeaguePredictability.leagueName] }
        }
    }
    fun updateMatchOdds(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
            leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                it[odds] = matchInfo.odds
            }
            logger.info("Updated odds for match ${matchInfo.teams} at ${matchInfo.datetime}")
        }
    }

}
