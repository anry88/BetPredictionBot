package service

import dto.LeagueStats
import dto.MatchInfo
import dto.PeriodStats
import dto.outcomeStrategyConfigs
import io.ktor.utils.io.errors.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Statistics(
    val totalMatches: Int,
    val correctPredictions: Int,
    val accuracy: Double,
    val roi: Double,
    val strategyTotalMatches: Int,
    val strategyCorrectPredictions: Int,
    val strategyAccuracy: Double,
    val strategyRoi: Double,
    val homeWinPredictions: Int,
    val homeWinSuccesses: Int,
    val homeWinAccuracy: Double,
    val homeWinRoi: Double,
    val drawPredictions: Int,
    val drawSuccesses: Int,
    val drawAccuracy: Double,
    val drawRoi: Double,
    val awayWinPredictions: Int,
    val awayWinSuccesses: Int,
    val awayWinAccuracy: Double,
    val awayWinRoi: Double
)

data class InviteLink(
    val id: Int,
    val inviteLink: String,
    val maxSubscribers: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val isActive: Boolean
)

data class InviteSubscriber(
    val id: Int,
    val inviteLink: String,
    val userId: String,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val joinedAt: Long
)

data class JoinRequest(
    val id: Long,
    val inviteLinkId: Long,
    val userId: String,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val status: String,
    val createdAt: Long,
    val maxSubscribers: Int,
    val expiresAt: Long
)

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

object InviteLinks : Table("invite_links") {
    val id = integer("id").autoIncrement()
    val inviteLink = varchar("invite_link", 255).uniqueIndex()
    val maxSubscribers = integer("max_subscribers")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val isActive = bool("is_active").default(true)

    override val primaryKey = PrimaryKey(id)
}

object InviteSubscribers : Table("invite_subscribers") {
    val id = integer("id").autoIncrement()
    val inviteLinkId = reference("invite_link_id", InviteLinks.id)
    val userId = varchar("user_id", 255)
    val username = varchar("username", 255).nullable()
    val firstName = varchar("first_name", 255).nullable()
    val lastName = varchar("last_name", 255).nullable()
    val joinedAt = long("joined_at")

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

    // InviteLinks table
    execSql("""
        CREATE TABLE IF NOT EXISTS invite_links (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invite_link TEXT NOT NULL UNIQUE,
            max_subscribers INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            is_active BOOLEAN DEFAULT 1
        );
    """.trimIndent())

    // InviteSubscribers table
    execSql("""
        CREATE TABLE IF NOT EXISTS invite_subscribers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invite_link_id INTEGER NOT NULL,
            user_id TEXT NOT NULL,
            username TEXT,
            first_name TEXT,
            last_name TEXT,
            joined_at INTEGER NOT NULL,
            FOREIGN KEY (invite_link_id) REFERENCES invite_links(id)
        );
    """.trimIndent())

    // JoinRequests table
    execSql("""
        CREATE TABLE IF NOT EXISTS join_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invite_link_id INTEGER NOT NULL,
            user_id TEXT NOT NULL,
            username TEXT,
            first_name TEXT,
            last_name TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at INTEGER NOT NULL,
            FOREIGN KEY (invite_link_id) REFERENCES invite_links(id)
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
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now)
                        && it[leagueTable.actualOutcome] != null && it[leagueTable.predictedOutcome] != null) {
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

            val hasStrategyTelegramMessageId = match.strategyTelegramMessageId != null

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

    fun getDetailedStatisticsForPeriod(days: Int): Statistics {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val startDate = now.minusDays(days.toLong())
        val allMatches = mutableListOf<MatchInfo>()

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.selectAll().mapNotNullTo(allMatches) {
                    val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                        .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now)
                        && it[leagueTable.actualOutcome] != null && it[leagueTable.predictedOutcome] != null) {
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

        var homeWinPredictions = 0
        var homeWinSuccesses = 0
        var homeWinStakes = 0.0
        var homeWinReturns = 0.0

        var drawPredictions = 0
        var drawSuccesses = 0
        var drawStakes = 0.0
        var drawReturns = 0.0

        var awayWinPredictions = 0
        var awayWinSuccesses = 0
        var awayWinStakes = 0.0
        var awayWinReturns = 0.0

        allMatches.forEach { match ->
            val stake = 100.0
            val teams = match.teams.split(" vs. ")
            if (teams.size != 2) return@forEach

            val homeTeam = teams[0].trim()
            val awayTeam = teams[1].trim()
            val predictedOutcome = match.predictedOutcome
            val actualOutcome = match.actualOutcome

            if (predictedOutcome == null || actualOutcome == null) return@forEach

            // Общая статистика
            totalMatches++
            totalStakes += stake

            if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                correctPredictions++
                val oddsValue = when (predictedOutcome) {
                    homeTeam -> match.homeWinOdds?.toDoubleOrNull()
                    "Draw" -> match.drawOdds?.toDoubleOrNull()
                    awayTeam -> match.awayWinOdds?.toDoubleOrNull()
                    else -> null
                } ?: return@forEach

                val profit = (oddsValue * stake) - stake
                totalReturns += profit
            } else {
                totalReturns -= stake
            }

            // Статистика по типам исходов
            when (predictedOutcome) {
                homeTeam -> {
                    homeWinPredictions++
                    homeWinStakes += stake
                    if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                        homeWinSuccesses++
                        val oddsValue = match.homeWinOdds?.toDoubleOrNull() ?: return@forEach
                        val profit = (oddsValue * stake) - stake
                        homeWinReturns += profit
                    } else {
                        homeWinReturns -= stake
                    }
                }
                "Draw" -> {
                    drawPredictions++
                    drawStakes += stake
                    if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                        drawSuccesses++
                        val oddsValue = match.drawOdds?.toDoubleOrNull() ?: return@forEach
                        val profit = (oddsValue * stake) - stake
                        drawReturns += profit
                    } else {
                        drawReturns -= stake
                    }
                }
                awayTeam -> {
                    awayWinPredictions++
                    awayWinStakes += stake
                    if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                        awayWinSuccesses++
                        val oddsValue = match.awayWinOdds?.toDoubleOrNull() ?: return@forEach
                        val profit = (oddsValue * stake) - stake
                        awayWinReturns += profit
                    } else {
                        awayWinReturns -= stake
                    }
                }
            }

            // Проверяем соответствие матча стратегии
            val isStrategyMatch = outcomeStrategyConfigs.any { config ->
                StrategyService.isMatchFitsStrategy(match, config)
            }

            // Статистика по стратегии
            if (isStrategyMatch) {
                strategyTotalMatches++
                strategyTotalStakes += stake

                if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                    strategyCorrectPredictions++
                    val oddsValue = when (predictedOutcome) {
                        homeTeam -> match.homeWinOdds?.toDoubleOrNull()
                        "Draw" -> match.drawOdds?.toDoubleOrNull()
                        awayTeam -> match.awayWinOdds?.toDoubleOrNull()
                        else -> null
                    } ?: return@forEach

                    val profit = (oddsValue * stake) - stake
                    strategyTotalReturns += profit
                } else {
                    strategyTotalReturns -= stake
                }
            }
        }

        return Statistics(
            totalMatches = totalMatches,
            correctPredictions = correctPredictions,
            accuracy = if (totalMatches > 0) (correctPredictions.toDouble() / totalMatches) * 100 else 0.0,
            roi = if (totalStakes > 0) (totalReturns / totalStakes) * 100 else 0.0,
            strategyTotalMatches = strategyTotalMatches,
            strategyCorrectPredictions = strategyCorrectPredictions,
            strategyAccuracy = if (strategyTotalMatches > 0) (strategyCorrectPredictions.toDouble() / strategyTotalMatches) * 100 else 0.0,
            strategyRoi = if (strategyTotalStakes > 0) (strategyTotalReturns / strategyTotalStakes) * 100 else 0.0,
            homeWinPredictions = homeWinPredictions,
            homeWinSuccesses = homeWinSuccesses,
            homeWinAccuracy = if (homeWinPredictions > 0) (homeWinSuccesses.toDouble() / homeWinPredictions) * 100 else 0.0,
            homeWinRoi = if (homeWinStakes > 0) (homeWinReturns / homeWinStakes) * 100 else 0.0,
            drawPredictions = drawPredictions,
            drawSuccesses = drawSuccesses,
            drawAccuracy = if (drawPredictions > 0) (drawSuccesses.toDouble() / drawPredictions) * 100 else 0.0,
            drawRoi = if (drawStakes > 0) (drawReturns / drawStakes) * 100 else 0.0,
            awayWinPredictions = awayWinPredictions,
            awayWinSuccesses = awayWinSuccesses,
            awayWinAccuracy = if (awayWinPredictions > 0) (awayWinSuccesses.toDouble() / awayWinPredictions) * 100 else 0.0,
            awayWinRoi = if (awayWinStakes > 0) (awayWinReturns / awayWinStakes) * 100 else 0.0
        )
    }

    fun getAllMatchesForLastTwoYears(): List<MatchInfo> {
        val allMatches = mutableListOf<MatchInfo>()
        val twoYearsAgo = LocalDateTime.now().minusYears(2) // Дата два года назад

        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)

                leagueTable.selectAll().mapNotNullTo(allMatches) { row ->
                    val matchDateTime = LocalDateTime.parse(row[leagueTable.datetime], dateTimeFormatter)

                    if (matchDateTime.isAfter(twoYearsAgo)) { // Оставляем только матчи моложе двух лет
                        MatchInfo(
                            fixtureId = row[leagueTable.fixtureId],
                            datetime = row[leagueTable.datetime],
                            matchType = row[leagueTable.matchType],
                            teams = row[leagueTable.teams],
                            predictedOutcome = row[leagueTable.predictedOutcome],
                            actualOutcome = row[leagueTable.actualOutcome],
                            predictedScore = row[leagueTable.predictedScore],
                            actualScore = row[leagueTable.actualScore],
                            odds = row[leagueTable.odds],
                            bookmakerName = row[leagueTable.bookmakerName],
                            homeWinOdds = row[leagueTable.homeWinOdds],
                            drawOdds = row[leagueTable.drawOdds],
                            awayWinOdds = row[leagueTable.awayWinOdds],
                            telegramMessageId = row[leagueTable.telegramMessageId],
                            strategyTelegramMessageId = row[leagueTable.strategyTelegramMessageId],
                            elapsed = null,
                            modelHomeWinProb = row[leagueTable.modelHomeWinProb],
                            modelDrawProb = row[leagueTable.modelDrawProb],
                            modelAwayWinProb = row[leagueTable.modelAwayWinProb],
                            modelExpectedHomeGoals = row[leagueTable.modelExpectedHomeGoals],
                            modelExpectedAwayGoals = row[leagueTable.modelExpectedAwayGoals]
                        )
                    } else {
                        null
                    }
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

    fun getLastMatchesForLeague(leagueName: String, days: Int): List<MatchInfo> {
        val matches = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            val fromDate = LocalDateTime.now().minusDays(days.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            leagueTable.select { (leagueTable.actualOutcome.isNotNull()) and (leagueTable.datetime greaterEq fromDate) }
                .orderBy(leagueTable.datetime, SortOrder.DESC)
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

    fun createInviteLink(inviteLink: String, maxSubscribers: Int, days: Int): Long {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val currentTime = System.currentTimeMillis() / 1000
        val expiresAt = currentTime + (days * 24 * 60 * 60)

        val sql = """
            INSERT INTO invite_links (invite_link, max_subscribers, created_at, expires_at, is_active)
            VALUES (?, ?, ?, ?, 1)
        """

        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)
        statement.setInt(2, maxSubscribers)
        statement.setLong(3, currentTime)
        statement.setLong(4, expiresAt)

        val result = statement.executeUpdate()
        val generatedId = statement.generatedKeys.getLong(1)

        statement.close()
        connection.close()

        return if (result > 0) generatedId else -1
    }

    fun addJoinRequest(inviteLinkId: Long, userId: String, username: String?, firstName: String?, lastName: String?): Boolean {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val currentTime = System.currentTimeMillis() / 1000

        val sql = """
            INSERT INTO join_requests (invite_link_id, user_id, username, first_name, last_name, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """

        val statement = connection.prepareStatement(sql)
        statement.setLong(1, inviteLinkId)
        statement.setString(2, userId)
        statement.setString(3, username)
        statement.setString(4, firstName)
        statement.setString(5, lastName)
        statement.setLong(6, currentTime)

        val result = statement.executeUpdate() > 0

        statement.close()
        connection.close()

        return result
    }

    fun approveJoinRequest(inviteLinkId: Long, userId: String): Boolean {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val currentTime = System.currentTimeMillis() / 1000

        // Начинаем транзакцию
        connection.autoCommit = false

        try {
            // Получаем время истечения ссылки
            val getExpirySql = "SELECT expires_at FROM invite_links WHERE id = ?"
            val getExpiryStmt = connection.prepareStatement(getExpirySql)
            getExpiryStmt.setLong(1, inviteLinkId)
            val expiryResult = getExpiryStmt.executeQuery()
            
            if (!expiryResult.next()) {
                return false
            }
            
            val expiresAt = expiryResult.getLong("expires_at")

            // Обновляем статус заявки
            val updateRequestSql = """
                UPDATE join_requests 
                SET status = 'approved' 
                WHERE invite_link_id = ? AND user_id = ? AND status = 'pending'
            """
            val updateRequestStmt = connection.prepareStatement(updateRequestSql)
            updateRequestStmt.setLong(1, inviteLinkId)
            updateRequestStmt.setString(2, userId)
            updateRequestStmt.executeUpdate()

            // Добавляем пользователя в подписчики
            val insertSubscriberSql = """
                INSERT INTO invite_subscribers (invite_link_id, user_id, username, first_name, last_name, joined_at)
                SELECT invite_link_id, user_id, username, first_name, last_name, ? 
                FROM join_requests 
                WHERE invite_link_id = ? AND user_id = ?
            """
            val insertSubscriberStmt = connection.prepareStatement(insertSubscriberSql)
            insertSubscriberStmt.setLong(1, currentTime)
            insertSubscriberStmt.setLong(2, inviteLinkId)
            insertSubscriberStmt.setString(3, userId)
            insertSubscriberStmt.executeUpdate()

            // Подтверждаем транзакцию
            connection.commit()
            return true
        } catch (e: Exception) {
            // В случае ошибки откатываем транзакцию
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
            connection.close()
        }
    }

    fun cleanupExpiredSubscribers(): List<InviteSubscriber> {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val currentTime = System.currentTimeMillis() / 1000
        val expiredSubscribers = mutableListOf<InviteSubscriber>()

        try {
            // Находим всех подписчиков, чьи ссылки истекли
            val sql = """
                SELECT s.*, l.invite_link
                FROM invite_subscribers s
                JOIN invite_links l ON s.invite_link_id = l.id
                WHERE l.expires_at < ?
            """
            
            val statement = connection.prepareStatement(sql)
            statement.setLong(1, currentTime)
            
            val resultSet = statement.executeQuery()
            while (resultSet.next()) {
                expiredSubscribers.add(
                    InviteSubscriber(
                        id = resultSet.getInt("id"),
                        inviteLink = resultSet.getString("invite_link"),
                        userId = resultSet.getString("user_id"),
                        username = resultSet.getString("username"),
                        firstName = resultSet.getString("first_name"),
                        lastName = resultSet.getString("last_name"),
                        joinedAt = resultSet.getLong("joined_at")
                    )
                )
            }

            // Удаляем истекших подписчиков
            if (expiredSubscribers.isNotEmpty()) {
                val deleteSql = """
                    DELETE FROM invite_subscribers 
                    WHERE invite_link_id IN (
                        SELECT id FROM invite_links WHERE expires_at < ?
                    )
                """
                val deleteStmt = connection.prepareStatement(deleteSql)
                deleteStmt.setLong(1, currentTime)
                deleteStmt.executeUpdate()
            }

            return expiredSubscribers
        } finally {
            connection.close()
        }
    }

    fun getPendingJoinRequests(): List<JoinRequest> {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val requests = mutableListOf<JoinRequest>()

        val sql = """
            SELECT jr.*, il.max_subscribers, il.expires_at
            FROM join_requests jr
            JOIN invite_links il ON jr.invite_link_id = il.id
            WHERE jr.status = 'pending'
            AND il.is_active = 1
            AND il.expires_at > ?
        """

        val statement = connection.prepareStatement(sql)
        statement.setLong(1, System.currentTimeMillis() / 1000)

        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            requests.add(
                JoinRequest(
                    id = resultSet.getLong("id"),
                    inviteLinkId = resultSet.getLong("invite_link_id"),
                    userId = resultSet.getString("user_id"),
                    username = resultSet.getString("username"),
                    firstName = resultSet.getString("first_name"),
                    lastName = resultSet.getString("last_name"),
                    status = resultSet.getString("status"),
                    createdAt = resultSet.getLong("created_at"),
                    maxSubscribers = resultSet.getInt("max_subscribers"),
                    expiresAt = resultSet.getLong("expires_at")
                )
            )
        }

        resultSet.close()
        statement.close()
        connection.close()

        return requests
    }

    fun getSubscriberCount(inviteLinkId: Long): Int {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)

        val sql = "SELECT COUNT(*) FROM invite_subscribers WHERE invite_link_id = ?"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, inviteLinkId)

        val resultSet = statement.executeQuery()
        val count = resultSet.getInt(1)

        resultSet.close()
        statement.close()
        connection.close()

        return count
    }

    fun getMaxSubscribersForLink(inviteLinkId: Long): Int {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)

        val sql = "SELECT max_subscribers FROM invite_links WHERE id = ?"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, inviteLinkId)

        val resultSet = statement.executeQuery()
        val maxSubscribers = if (resultSet.next()) resultSet.getInt(1) else 0

        resultSet.close()
        statement.close()
        connection.close()

        return maxSubscribers
    }

    fun getExpiredInviteLinks(): List<InviteLink> {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val currentTime = System.currentTimeMillis() / 1000
        val links = mutableListOf<InviteLink>()

        val sql = """
            SELECT id, invite_link, max_subscribers, created_at, expires_at, is_active
            FROM invite_links
            WHERE expires_at < ? AND is_active = 1
        """

        val statement = connection.prepareStatement(sql)
        statement.setLong(1, currentTime)

        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            links.add(
                InviteLink(
                    id = resultSet.getInt("id"),
                    inviteLink = resultSet.getString("invite_link"),
                    maxSubscribers = resultSet.getInt("max_subscribers"),
                    createdAt = resultSet.getLong("created_at"),
                    expiresAt = resultSet.getLong("expires_at"),
                    isActive = resultSet.getBoolean("is_active")
                )
            )
        }

        resultSet.close()
        statement.close()
        connection.close()

        return links
    }

    fun getSubscribersForInviteLink(inviteLink: String): List<InviteSubscriber> {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val subscribers = mutableListOf<InviteSubscriber>()

        val sql = """
            SELECT s.id, s.user_id, s.username, s.first_name, s.last_name, s.joined_at
            FROM invite_subscribers s
            JOIN invite_links l ON s.invite_link_id = l.id
            WHERE l.invite_link = ?
        """

        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)

        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            subscribers.add(
                InviteSubscriber(
                    id = resultSet.getInt("id"),
                    inviteLink = inviteLink,
                    userId = resultSet.getString("user_id"),
                    username = resultSet.getString("username"),
                    firstName = resultSet.getString("first_name"),
                    lastName = resultSet.getString("last_name"),
                    joinedAt = resultSet.getLong("joined_at")
                )
            )
        }

        resultSet.close()
        statement.close()
        connection.close()

        return subscribers
    }

    fun removeInviteSubscriber(inviteLink: String, userId: String): Boolean {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)

        val sql = """
            DELETE FROM invite_subscribers
            WHERE invite_link_id IN (SELECT id FROM invite_links WHERE invite_link = ?)
            AND user_id = ?
        """

        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)
        statement.setString(2, userId)

        val result = statement.executeUpdate() > 0

        statement.close()
        connection.close()

        return result
    }

    fun removeInviteLink(inviteLink: String): Boolean {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)

        val sql = "DELETE FROM invite_links WHERE invite_link = ?"
        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)

        val result = statement.executeUpdate() > 0

        statement.close()
        connection.close()

        return result
    }

    private fun getConnection(): Connection {
        return DriverManager.getConnection("jdbc:sqlite:predictions.db")
    }

    private fun closeResources(connection: Connection?, statement: PreparedStatement?, resultSet: ResultSet?) {
        try {
            resultSet?.close()
            statement?.close()
            connection?.close()
        } catch (e: Exception) {
            logger.error("Error closing database resources", e)
        }
    }

    fun getInviteLinkId(inviteLink: String): Long? {
        var connection: Connection? = null
        var statement: PreparedStatement? = null
        var resultSet: ResultSet? = null

        try {
            connection = getConnection()
            val query = "SELECT id FROM invite_links WHERE invite_link = ? AND is_active = 1"
            statement = connection.prepareStatement(query)
            statement.setString(1, inviteLink)
            resultSet = statement.executeQuery()

            return if (resultSet.next()) {
                resultSet.getLong("id")
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Error getting invite link ID", e)
            return null
        } finally {
            closeResources(connection, statement, resultSet)
        }
    }

    fun removeUserFromChannel(userId: Long, channelId: Long) {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)

        val sql = """
            DELETE FROM invite_subscribers 
            WHERE user_id = ? AND invite_link_id IN (
                SELECT id FROM invite_links WHERE chat_id = ?
            )
        """

        val statement = connection.prepareStatement(sql)
        statement.setString(1, userId.toString())
        statement.setString(2, channelId.toString())

        statement.executeUpdate()

        statement.close()
        connection.close()
    }

    fun deactivateInviteLink(linkId: Int) {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)

        val sql = """
            UPDATE invite_links 
            SET is_active = 0 
            WHERE id = ?
        """

        val statement = connection.prepareStatement(sql)
        statement.setInt(1, linkId)
        statement.executeUpdate()

        statement.close()
        connection.close()
    }

}
