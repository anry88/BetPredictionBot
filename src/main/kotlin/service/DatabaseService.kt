package service

import dto.LeagueStats
import dto.MatchInfo
import dto.OutcomeStrategyConfig
import dto.PeriodStats
import io.ktor.utils.io.errors.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.ResultSet
import java.time.LocalDate
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
    val bookmakerName = varchar("bookmakerName", 50).nullable()
    val homeWinOdds = varchar("homeWinOdds", 50).nullable()
    val drawOdds = varchar("drawOdds", 50).nullable()
    val awayWinOdds = varchar("awayWinOdds", 50).nullable()
    val telegramMessageId = varchar("telegramMessageId", 50).nullable()
    val strategyTelegramMessageId = varchar("strategyTelegramMessageId", 50).nullable()
    val modelHomeWinProb = double("modelHomeWinProb").nullable()
    val modelDrawProb = double("modelDrawProb").nullable()
    val modelAwayWinProb = double("modelAwayWinProb").nullable()
    val modelExpectedHomeGoals = double("modelExpectedHomeGoals").nullable()
    val modelExpectedAwayGoals = double("modelExpectedAwayGoals").nullable()

    override val primaryKey = PrimaryKey(id)
}
object LeagueTableFactory {
    private val tables = mutableMapOf<String, LeagueTable>()

    fun getTableForLeague(leagueName: String): LeagueTable {

        return tables.getOrPut(leagueName) {
            LeagueTable(leagueName.replace(" ", "_").replace("-", "_").lowercase())
        }
    }
}

object LeaguePredictability : Table() {
    val leagueName = varchar("leagueName", 100)
    val roi = double("roi").default(0.0)
    val accuracy = double("accuracy").default(0.0)
    val strategyRoi = double("strategyRoi").default(0.0)
    val strategyAccuracy = double("strategyAccuracy").default(0.0)
    // Новые столбцы для детальной статистики
    val homeWinPredictions = integer("homeWinPredictions").default(0)
    val homeWinSuccesses = integer("homeWinSuccesses").default(0)
    val homeWinAccuracy = double("homeWinAccuracy").default(0.0)
    val homeWinRoi = double("homeWinRoi").default(0.0)
    val drawPredictions = integer("drawPredictions").default(0)
    val drawSuccesses = integer("drawSuccesses").default(0)
    val drawAccuracy = double("drawAccuracy").default(0.0)
    val drawRoi = double("drawRoi").default(0.0)
    val awayWinPredictions = integer("awayWinPredictions").default(0)
    val awayWinSuccesses = integer("awayWinSuccesses").default(0)
    val awayWinAccuracy = double("awayWinAccuracy").default(0.0)
    val awayWinRoi = double("awayWinRoi").default(0.0)

    override val primaryKey = PrimaryKey(leagueName)
}

//fun initDatabase(dbPath: String) {
//    val logger = LoggerFactory.getLogger("DatabaseService")
//    val dbFile = File(dbPath)
//
//    logger.info("Database file path: $dbPath")
//
//    if (!dbFile.exists()) {
//        try {
//            dbFile.createNewFile()
//            logger.info("Database file created at: $dbPath")
//        } catch (e: IOException) {
//            logger.error("Failed to create database file", e)
//            throw e
//        }
//    } else {
//        logger.info("Database file already exists at: $dbPath")
//    }
//
//    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
//    transaction {
//        SchemaUtils.createMissingTablesAndColumns(UserStats, Leagues, LeaguePredictability)
//        logger.info("Database initialized and tables 'UserStats' ensured.")
//    }
//
//}

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

    // Подключаемся к SQLite
    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")

    // Запускаем ручную миграцию
    transaction {
        runManualMigration()
    }

    logger.info("Database initialized (manual migration done).")
}
fun execSql(sql: String) {
    transaction {
        exec(sql)
    }
}
/**
 * Здесь вручную создаём таблицы/столбцы.
 * Если нужно, делаем ALTER TABLE, проверяем столбцы и т.д.
 */
private fun runManualMigration() {
    // userStats
    execSql("""
        CREATE TABLE IF NOT EXISTS UserStats (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            userId TEXT NOT NULL,
            firstName TEXT,
            lastName TEXT,
            username TEXT,
            lastActivity TEXT NOT NULL
        );
    """.trimIndent())

    // leagues
    execSql("""
        CREATE TABLE IF NOT EXISTS Leagues (
            name TEXT NOT NULL UNIQUE,
            PRIMARY KEY(name)
        );
    """.trimIndent())

    // leaguePredictability
    execSql("""
        CREATE TABLE IF NOT EXISTS LeaguePredictability (
            leagueName TEXT NOT NULL,
            roi REAL DEFAULT 0.0,
            accuracy REAL DEFAULT 0.0,
            strategyRoi REAL DEFAULT 0.0,
            strategyAccuracy REAL DEFAULT 0.0,

            homeWinPredictions INTEGER DEFAULT 0,
            homeWinSuccesses INTEGER DEFAULT 0,
            homeWinAccuracy REAL DEFAULT 0.0,
            homeWinRoi REAL DEFAULT 0.0,

            drawPredictions INTEGER DEFAULT 0,
            drawSuccesses INTEGER DEFAULT 0,
            drawAccuracy REAL DEFAULT 0.0,
            drawRoi REAL DEFAULT 0.0,

            awayWinPredictions INTEGER DEFAULT 0,
            awayWinSuccesses INTEGER DEFAULT 0,
            awayWinAccuracy REAL DEFAULT 0.0,
            awayWinRoi REAL DEFAULT 0.0,

            PRIMARY KEY(leagueName)
        );
    """.trimIndent())

    // и так далее для остальных таблиц, которые у вас есть «общие» (если нужно).

    // Для LeagueTable (одна таблица на лигу) — можно создать по необходимости,
    // но часто у вас их динамически много. Если нужно создать «пачку» таких таблиц
    // вручную, делайте аналогично (CREATE TABLE IF NOT EXISTS ...).

    // Пример одного LeagueTable (хотя у вас их может быть много)
    // "leagueName" берём из логики, или делаем это позже при добавлении новой лиги:

    // createLeagueTableIfNeeded("england_premier_league") // пример
    // createLeagueTableIfNeeded("spain_la_liga") // пример
}

/**
 * Пример, как вручную создать таблицу лиги (leagueTable).
 * Вы можете вызывать это при добавлении новой лиги (или когда впервые записываете матч).
 */
fun createLeagueTableIfNeeded(tableName: String) {
    execSql("""
        CREATE TABLE IF NOT EXISTS ${tableName.replace(" ", "_").replace("-", "_").lowercase()} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fixtureId TEXT NOT NULL UNIQUE,
            datetime TEXT NOT NULL,
            matchType TEXT NOT NULL,
            teams TEXT NOT NULL,
            predictedOutcome TEXT,
            actualOutcome TEXT,
            predictedScore TEXT,
            actualScore TEXT,
            odds TEXT,
            bookmakerName TEXT,
            homeWinOdds TEXT,
            drawOdds TEXT,
            awayWinOdds TEXT,
            telegramMessageId TEXT,
            strategyTelegramMessageId TEXT,
            modelHomeWinProb REAL,
            modelDrawProb REAL,
            modelAwayWinProb REAL,
            modelExpectedHomeGoals REAL,
            modelExpectedAwayGoals REAL
        );
    """.trimIndent())

    // Если нужно «добавить» новые столбцы — проверяем, нет ли их.
    // Можно сделать helper: addColumnIfNotExists(tableName, "newColumn", "REAL")
}

fun <T : Any> execAndMap(sql: String, transform: (ResultSet) -> T): T? {
    return transaction {
        exec(sql) { rs ->
            transform(rs)
        }
    }
}


/**
 * Проверяем, есть ли столбец columnName в таблице tableName.
 * Если нет, делаем ALTER TABLE ... ADD COLUMN
 */
fun addColumnIfNotExists(tableName: String, columnName: String, columnDefinition: String) {
    // Пример:
    val newTableName = tableName.replace(" ", "_").replace("-","_").lowercase()

    val cols = mutableListOf<String>()

    transaction {
        val stmt = this.connection.prepareStatement("PRAGMA table_info('$newTableName')", false)
        try {
            val rs = stmt.executeQuery()
            while (rs.next()) {
                cols += rs.getString("name")
            }
            cols
        } finally {
            stmt.closeIfPossible()
        }
    }


    if (!cols.contains(columnName)) {
        execSql("ALTER TABLE $newTableName ADD COLUMN $columnName $columnDefinition;")
    }
}

/**
 * Пример использования:
 */
fun addMissingColumnsForLeague(tableName: String) {
    addColumnIfNotExists(tableName, "modelHomeWinProb", "DOUBLE")
    addColumnIfNotExists(tableName, "modelDrawProb", "DOUBLE")
    addColumnIfNotExists(tableName, "modelAwayWinProb", "DOUBLE")
    addColumnIfNotExists(tableName, "modelExpectedHomeGoals", "DOUBLE")
    addColumnIfNotExists(tableName, "modelExpectedAwayGoals", "DOUBLE")
}

object DatabaseService {
    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

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
                createLeagueTableIfNeeded(match.matchType)
                addMissingColumnsForLeague(match.matchType)

//                SchemaUtils.createMissingTablesAndColumns(leagueTable)
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
//                    SchemaUtils.createMissingTablesAndColumns(leagueTable)
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
                            it[leagueTable.bookmakerName],
                            it[leagueTable.homeWinOdds],
                            it[leagueTable.drawOdds],
                            it[leagueTable.awayWinOdds],
                            it[leagueTable.telegramMessageId],
                            it[leagueTable.strategyTelegramMessageId],
                            null,
                            it[leagueTable.modelHomeWinProb],
                            it[leagueTable.modelDrawProb],
                            it[leagueTable.modelAwayWinProb],
                            it[leagueTable.modelExpectedHomeGoals],
                            it[leagueTable.modelExpectedAwayGoals]
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
//            SchemaUtils.createMissingTablesAndColumns(leagueTable)
            createLeagueTableIfNeeded(matchInfo.matchType)
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
                it[modelHomeWinProb] = matchInfo.modelHomeWinProb
                it[modelDrawProb] = matchInfo.modelDrawProb
                it[modelAwayWinProb] = matchInfo.modelAwayWinProb
                it[modelExpectedHomeGoals] = matchInfo.modelExpectedHomeGoals
                it[modelExpectedAwayGoals] = matchInfo.modelExpectedAwayGoals
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
                addMissingColumnsForLeague(leagueName)
//                SchemaUtils.createMissingTablesAndColumns(leagueTable)

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
                            bookmakerName = it[leagueTable.bookmakerName],
                            homeWinOdds = it[leagueTable.homeWinOdds],
                            drawOdds = it[leagueTable.drawOdds],
                            awayWinOdds = it[leagueTable.awayWinOdds],
                            telegramMessageId = it[leagueTable.telegramMessageId],
                            strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                            null,
                            modelHomeWinProb = it[leagueTable.modelHomeWinProb],
                            modelDrawProb = it[leagueTable.modelDrawProb],
                            modelAwayWinProb = it[leagueTable.modelAwayWinProb],
                            modelExpectedHomeGoals = it[leagueTable.modelExpectedHomeGoals],
                            modelExpectedAwayGoals = it[leagueTable.modelExpectedAwayGoals]
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
                        bookmakerName = it[leagueTable.bookmakerName],
                        homeWinOdds = it[leagueTable.homeWinOdds],
                        drawOdds = it[leagueTable.drawOdds],
                        awayWinOdds = it[leagueTable.awayWinOdds],
                        telegramMessageId = it[leagueTable.telegramMessageId],
                        strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                        null,
                        modelHomeWinProb = it[leagueTable.modelHomeWinProb],
                        modelDrawProb = it[leagueTable.modelDrawProb],
                        modelAwayWinProb = it[leagueTable.modelAwayWinProb],
                        modelExpectedHomeGoals = it[leagueTable.modelExpectedHomeGoals],
                        modelExpectedAwayGoals = it[leagueTable.modelExpectedAwayGoals]
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
//                SchemaUtils.createMissingTablesAndColumns(leagueTable)
                addMissingColumnsForLeague(leagueName)
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
                        it[leagueTable.bookmakerName],
                        it[leagueTable.homeWinOdds],
                        it[leagueTable.drawOdds],
                        it[leagueTable.awayWinOdds],
                        it[leagueTable.telegramMessageId],
                        it[leagueTable.strategyTelegramMessageId],
                        null,
                        it[leagueTable.modelHomeWinProb],
                        it[leagueTable.modelDrawProb],
                        it[leagueTable.modelAwayWinProb],
                        it[leagueTable.modelExpectedHomeGoals],
                        it[leagueTable.modelExpectedAwayGoals]
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
                            fixtureId = it[leagueTable.fixtureId],
                            datetime = it[leagueTable.datetime],
                            matchType = it[leagueTable.matchType],
                            teams = it[leagueTable.teams],
                            predictedOutcome = it[leagueTable.predictedOutcome],
                            actualOutcome = it[leagueTable.actualOutcome],
                            predictedScore = it[leagueTable.predictedScore],
                            actualScore = it[leagueTable.actualScore],
                            odds = it[leagueTable.odds],
                            bookmakerName = it[leagueTable.bookmakerName],
                            homeWinOdds = it[leagueTable.homeWinOdds],
                            drawOdds = it[leagueTable.drawOdds],
                            awayWinOdds = it[leagueTable.awayWinOdds],
                            telegramMessageId = it[leagueTable.telegramMessageId],
                            strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                            elapsed = null,
                            modelHomeWinProb =  it[leagueTable.modelHomeWinProb],
                            modelDrawProb = it[leagueTable.modelDrawProb],
                            modelAwayWinProb = it[leagueTable.modelAwayWinProb],
                            modelExpectedHomeGoals = it[leagueTable.modelExpectedHomeGoals],
                            modelExpectedAwayGoals = it[leagueTable.modelExpectedAwayGoals]
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

        // Updated function call with correct parameter names and outcomeType
        val predictableLeagues = getPredictableLeagues(
            outcomeType = "HomeWin",
            roiThreshold = 10.0,
            accuracyThreshold = 60.0
        )

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

            // Checking if the match fits the strategy
            val teams = match.teams.split(" vs. ")
            if (teams.size == 2) {
                val homeTeam = teams[0].trim()
                val predictedOutcome = match.predictedOutcome
                val isHomeTeamPredicted = predictedOutcome == homeTeam
                val isPredictableLeague = match.matchType in predictableLeagues
                val hasStrategyTelegramMessageId = match.strategyTelegramMessageId != null
                val isOddsInRange = oddsValue in 1.20..2.20
                val isNotDraw = predictedOutcome != "Draw"

                if (hasStrategyTelegramMessageId) {
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
                        bookmakerName = it[leagueTable.bookmakerName],
                        homeWinOdds = it[leagueTable.homeWinOdds],
                        drawOdds = it[leagueTable.drawOdds],
                        awayWinOdds = it[leagueTable.awayWinOdds],
                        telegramMessageId = it[leagueTable.telegramMessageId],
                        strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                        null,
                        modelHomeWinProb = it[leagueTable.modelHomeWinProb],
                        modelDrawProb = it[leagueTable.modelDrawProb],
                        modelAwayWinProb = it[leagueTable.modelAwayWinProb],
                        modelExpectedHomeGoals = it[leagueTable.modelExpectedHomeGoals],
                        modelExpectedAwayGoals = it[leagueTable.modelExpectedAwayGoals]
                    )
                }
            }
        }
        return allMatches
    }

    fun updateLeaguePredictability(leagueStatsMap: Map<String, LeagueStats>) {
        transaction {
//            SchemaUtils.createMissingTablesAndColumns(LeaguePredictability)
            leagueStatsMap.values.forEach { stats ->
                val updatedRows = LeaguePredictability.update({ LeaguePredictability.leagueName eq stats.leagueName }) {
                    it[roi] = stats.roi
                    it[accuracy] = stats.accuracy
                    it[strategyRoi] = stats.strategyRoi
                    it[strategyAccuracy] = stats.strategyAccuracy
                    // Новые поля
                    it[homeWinPredictions] = stats.homeWinPredictions
                    it[homeWinSuccesses] = stats.homeWinSuccesses
                    it[homeWinAccuracy] = stats.homeWinAccuracy
                    it[homeWinRoi] = stats.homeWinRoi
                    it[drawPredictions] = stats.drawPredictions
                    it[drawSuccesses] = stats.drawSuccesses
                    it[drawAccuracy] = stats.drawAccuracy
                    it[drawRoi] = stats.drawRoi
                    it[awayWinPredictions] = stats.awayWinPredictions
                    it[awayWinSuccesses] = stats.awayWinSuccesses
                    it[awayWinAccuracy] = stats.awayWinAccuracy
                    it[awayWinRoi] = stats.awayWinRoi
                }

                if (updatedRows == 0) {
                    LeaguePredictability.insert {
                        it[leagueName] = stats.leagueName
                        it[roi] = stats.roi
                        it[accuracy] = stats.accuracy
                        it[strategyRoi] = stats.strategyRoi
                        it[strategyAccuracy] = stats.strategyAccuracy
                        // Новые поля
                        it[homeWinPredictions] = stats.homeWinPredictions
                        it[homeWinSuccesses] = stats.homeWinSuccesses
                        it[homeWinAccuracy] = stats.homeWinAccuracy
                        it[homeWinRoi] = stats.homeWinRoi
                        it[drawPredictions] = stats.drawPredictions
                        it[drawSuccesses] = stats.drawSuccesses
                        it[drawAccuracy] = stats.drawAccuracy
                        it[drawRoi] = stats.drawRoi
                        it[awayWinPredictions] = stats.awayWinPredictions
                        it[awayWinSuccesses] = stats.awayWinSuccesses
                        it[awayWinAccuracy] = stats.awayWinAccuracy
                        it[awayWinRoi] = stats.awayWinRoi
                    }
                    logger.info("Inserted new league predictability data for league: ${stats.leagueName}")
                } else {
                    logger.info("Updated league predictability data for league: ${stats.leagueName}")
                }
            }
        }
    }


    // In DatabaseService.kt
    fun getPredictableLeagues(
        outcomeType: String,
        roiThreshold: Double,
        accuracyThreshold: Double
    ): List<String> {
        return transaction {
//            SchemaUtils.createMissingTablesAndColumns(LeaguePredictability)
            when (outcomeType) {
                "HomeWin" -> {
                    LeaguePredictability.select {
                        (LeaguePredictability.homeWinRoi greaterEq roiThreshold) and
                                (LeaguePredictability.homeWinAccuracy greaterEq accuracyThreshold)
                    }.map { it[LeaguePredictability.leagueName] }
                }
                "Draw" -> {
                    LeaguePredictability.select {
                        (LeaguePredictability.drawRoi greaterEq roiThreshold) and
                                (LeaguePredictability.drawAccuracy greaterEq accuracyThreshold)
                    }.map { it[LeaguePredictability.leagueName] }
                }
                "AwayWin" -> {
                    LeaguePredictability.select {
                        (LeaguePredictability.awayWinRoi greaterEq roiThreshold) and
                                (LeaguePredictability.awayWinAccuracy greaterEq accuracyThreshold)
                    }.map { it[LeaguePredictability.leagueName] }
                }
                else -> emptyList()
            }
        }
    }

    fun isLeagueFitsStrategy(strategyRoiThreshold: Double, strategyAccuracyThreshold: Double): List<String> {
        return transaction {
//            SchemaUtils.createMissingTablesAndColumns(LeaguePredictability)
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
                it[bookmakerName] = matchInfo.bookmakerName
                it[homeWinOdds] = matchInfo.homeWinOdds
                it[drawOdds] = matchInfo.drawOdds
                it[awayWinOdds] = matchInfo.awayWinOdds
            }
            logger.info("Updated odds for match ${matchInfo.teams} at ${matchInfo.datetime}")
        }
    }

    fun getMatchesOlderThanOneDayWithoutResult(cutoffDate: LocalDate): List<MatchInfo> {
        val matchesToDelete = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                leagueTable.select {
                    (leagueTable.datetime less cutoffDate.format(dateFormatter)) and
                            (leagueTable.actualOutcome.isNull())
                }.mapNotNullTo(matchesToDelete) {
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
                        bookmakerName = it[leagueTable.bookmakerName],
                        homeWinOdds = it[leagueTable.homeWinOdds],
                        drawOdds = it[leagueTable.drawOdds],
                        awayWinOdds = it[leagueTable.awayWinOdds],
                        telegramMessageId = it[leagueTable.telegramMessageId],
                        strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                        null,
                        modelHomeWinProb = it[leagueTable.modelHomeWinProb],
                        modelDrawProb = it[leagueTable.modelDrawProb],
                        modelAwayWinProb = it[leagueTable.modelAwayWinProb],
                        modelExpectedHomeGoals = it[leagueTable.modelExpectedHomeGoals],
                        modelExpectedAwayGoals = it[leagueTable.modelExpectedAwayGoals]
                    )
                }
            }
        }
        return matchesToDelete
    }

    fun getLastNMatchesForLeague(leagueName: String, n: Int): List<MatchInfo> {
        val matches = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            leagueTable.select { leagueTable.actualOutcome.isNotNull() }
                .orderBy(leagueTable.datetime, SortOrder.DESC)
                .limit(n)
                .mapNotNullTo(matches) {
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
                        bookmakerName = it[leagueTable.bookmakerName],
                        homeWinOdds = it[leagueTable.homeWinOdds],
                        drawOdds = it[leagueTable.drawOdds],
                        awayWinOdds = it[leagueTable.awayWinOdds],
                        telegramMessageId = it[leagueTable.telegramMessageId],
                        strategyTelegramMessageId = it[leagueTable.strategyTelegramMessageId],
                        elapsed = null,
                        modelHomeWinProb = it[leagueTable.modelHomeWinProb],
                        modelDrawProb = it[leagueTable.modelDrawProb],
                        modelAwayWinProb = it[leagueTable.modelAwayWinProb],
                        modelExpectedHomeGoals = it[leagueTable.modelExpectedHomeGoals],
                        modelExpectedAwayGoals = it[leagueTable.modelExpectedAwayGoals]
                    )
                }
        }
        return matches
    }

    fun getLeaguePredictabilityData(): List<LeagueStats> {
        return transaction {
            LeaguePredictability.selectAll().map {
                LeagueStats(
                    leagueName = it[LeaguePredictability.leagueName],
                    roi = it[LeaguePredictability.roi],
                    accuracy = it[LeaguePredictability.accuracy],
                    strategyRoi = it[LeaguePredictability.strategyRoi],
                    strategyAccuracy = it[LeaguePredictability.strategyAccuracy],
                    homeWinPredictions = it[LeaguePredictability.homeWinPredictions],
                    homeWinSuccesses = it[LeaguePredictability.homeWinSuccesses],
                    homeWinAccuracy = it[LeaguePredictability.homeWinAccuracy],
                    homeWinRoi = it[LeaguePredictability.homeWinRoi],
                    drawPredictions = it[LeaguePredictability.drawPredictions],
                    drawSuccesses = it[LeaguePredictability.drawSuccesses],
                    drawAccuracy = it[LeaguePredictability.drawAccuracy],
                    drawRoi = it[LeaguePredictability.drawRoi],
                    awayWinPredictions = it[LeaguePredictability.awayWinPredictions],
                    awayWinSuccesses = it[LeaguePredictability.awayWinSuccesses],
                    awayWinAccuracy = it[LeaguePredictability.awayWinAccuracy],
                    awayWinRoi = it[LeaguePredictability.awayWinRoi]
                )
            }
        }
    }

}
