package service

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import repository.MatchRepository
import repository.InviteRepository
import repository.UserStatsRepository
import repository.PremiumSubscriptionRepository
import repository.CommandUsageRepository
import repository.UserSettingsRepository
import repository.ScheduledJobRepository
import repository.PaymentRepository
import repository.RefundRequestRepository
import repository.MatchPollRepository
import java.io.File
import io.ktor.utils.io.errors.*

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
    transaction { runManualMigration() }
    logger.info("Database initialized (manual migration done).")
}

fun execSql(sql: String) {
    transaction {
        exec(sql)
    }
}

private fun runManualMigration() {
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

    execSql("""
        CREATE TABLE IF NOT EXISTS Leagues (
            name TEXT NOT NULL UNIQUE,
            PRIMARY KEY(name)
        );
    """.trimIndent())

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

    execSql("""
        CREATE TABLE IF NOT EXISTS invite_links (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invite_link TEXT NOT NULL UNIQUE,
            max_subscribers INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            is_active BOOLEAN DEFAULT 1,
            owner_id TEXT
        );
    """.trimIndent())
    addColumnIfNotExists("invite_links", "owner_id", "TEXT")

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

    execSql("""
        CREATE TABLE IF NOT EXISTS premium_subscriptions (
            user_id TEXT NOT NULL,
            type TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            PRIMARY KEY (user_id, type)
        );
    """.trimIndent())
    addColumnIfNotExists("premium_subscriptions", "type", "TEXT NOT NULL DEFAULT 'BOT'")

    execSql("""
        CREATE TABLE IF NOT EXISTS command_usage (
            user_id TEXT NOT NULL,
            command TEXT NOT NULL,
            month TEXT NOT NULL,
            count INTEGER NOT NULL,
            PRIMARY KEY (user_id, command, month)
        );
    """.trimIndent())

    execSql("""
        CREATE TABLE IF NOT EXISTS user_settings (
            user_id TEXT PRIMARY KEY,
            timezone TEXT DEFAULT 'UTC'
        );
    """.trimIndent())

    execSql("""
        CREATE TABLE IF NOT EXISTS scheduled_jobs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            command TEXT NOT NULL,
            params TEXT,
            next_run INTEGER NOT NULL,
            interval_seconds INTEGER NOT NULL
        );
    """.trimIndent())

    execSql("""
        CREATE TABLE IF NOT EXISTS match_polls (
            fixture_id TEXT PRIMARY KEY,
            poll_message_id TEXT,
            poll_id TEXT,
            poll_date TEXT,
            teams TEXT,
            closed INTEGER DEFAULT 0
        );
    """.trimIndent())

    addColumnIfNotExists("match_polls", "teams", "TEXT")

    execSql("""
        CREATE TABLE IF NOT EXISTS payments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            telegram_payment_charge_id TEXT NOT NULL,
            provider_payment_charge_id TEXT,
            payload TEXT,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        );
    """.trimIndent())

    execSql("""
        CREATE TABLE IF NOT EXISTS refund_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            payment_id INTEGER NOT NULL,
            reason TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at INTEGER NOT NULL,
            admin_comment TEXT,
            user_comment TEXT,
            FOREIGN KEY(payment_id) REFERENCES payments(id)
        );
    """.trimIndent())

    addColumnIfNotExists("refund_requests", "user_comment", "TEXT")
}

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
            modelExpectedAwayGoals REAL,
            homeMatchesLastYear INTEGER,
            awayMatchesLastYear INTEGER
        );
    """.trimIndent())
}

fun addColumnIfNotExists(tableName: String, columnName: String, columnDefinition: String) {
    val newTableName = tableName.replace(" ", "_").replace("-","_").lowercase()
    val cols = mutableListOf<String>()
    transaction {
        val stmt = this.connection.prepareStatement("PRAGMA table_info('$newTableName')", false)
        try {
            val rs = stmt.executeQuery()
            try {
                while (rs.next()) {
                    cols += rs.getString("name")
                }
            } finally {
                rs.close()
            }
        } finally {
            stmt.closeIfPossible()
        }
    }
    if (!cols.contains(columnName)) {
        execSql("ALTER TABLE $newTableName ADD COLUMN $columnName $columnDefinition;")
    }
}

fun addMissingColumnsForLeague(tableName: String) {
    addColumnIfNotExists(tableName, "modelHomeWinProb", "DOUBLE")
    addColumnIfNotExists(tableName, "modelDrawProb", "DOUBLE")
    addColumnIfNotExists(tableName, "modelAwayWinProb", "DOUBLE")
    addColumnIfNotExists(tableName, "modelExpectedHomeGoals", "DOUBLE")
    addColumnIfNotExists(tableName, "modelExpectedAwayGoals", "DOUBLE")
    addColumnIfNotExists(tableName, "homeMatchesLastYear", "INTEGER")
    addColumnIfNotExists(tableName, "awayMatchesLastYear", "INTEGER")
}

object DatabaseService {
    val matches = MatchRepository()
    val invites = InviteRepository()
    val users = UserStatsRepository()
    val subscriptions = PremiumSubscriptionRepository()
    val commandUsage = CommandUsageRepository()
    val userSettings = UserSettingsRepository()
    val jobs = ScheduledJobRepository()
    val payments = PaymentRepository()
    val refunds = RefundRequestRepository()
    val polls = MatchPollRepository()
}
