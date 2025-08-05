package repository

import dto.MatchInfo
import dto.LeagueStats
import dto.PeriodStats
import dto.outcomeStrategyConfigs
import repository.Statistics
import service.createLeagueTableIfNeeded
import service.addMissingColumnsForLeague
import service.StrategyService
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    fun getTableForLeague(leagueName: String): LeagueTable =
        tables.getOrPut(leagueName) { LeagueTable(leagueName.replace(" ", "_").replace("-", "_").lowercase()) }
}

object LeaguePredictability : Table() {
    val leagueName = varchar("leagueName", 100)
    val roi = double("roi").default(0.0)
    val accuracy = double("accuracy").default(0.0)
    val strategyRoi = double("strategyRoi").default(0.0)
    val strategyAccuracy = double("strategyAccuracy").default(0.0)
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

class MatchRepository {
    private val logger = LoggerFactory.getLogger(MatchRepository::class.java)
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val listOfLeagues = mutableSetOf<String>()

    private fun loadLeagues() {
        transaction {
            Leagues.selectAll().forEach { listOfLeagues.add(it[Leagues.name]) }
        }
    }

    init {
        loadLeagues()
    }

    fun getAllLeagues(): List<String> = listOfLeagues.toList()

    fun appendRows(matches: List<MatchInfo>) {
        transaction {
            matches.forEach { match ->
                val leagueTable = LeagueTableFactory.getTableForLeague(match.matchType)
                if (!listOfLeagues.contains(match.matchType)) {
                    Leagues.insertIgnore { it[name] = match.matchType }
                    listOfLeagues.add(match.matchType)
                }
                createLeagueTableIfNeeded(match.matchType)
                addMissingColumnsForLeague(match.matchType)
                leagueTable.insert {
                    it[fixtureId] = match.fixtureId
                    it[datetime] = match.datetime
                    it[matchType] = match.matchType
                    it[teams] = match.teams
                    it[predictedOutcome] = match.predictedOutcome
                    it[actualOutcome] = match.actualOutcome
                    it[predictedScore] = match.predictedScore
                    it[actualScore] = match.actualScore
                    it[odds] = match.odds ?: ""
                    it[telegramMessageId] = match.telegramMessageId
                }
            }
        }
    }

    fun updateMatchResult(matchInfo: MatchInfo) {
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
            try {
                leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                    it[datetime] = matchInfo.datetime
                    it[actualOutcome] = matchInfo.actualOutcome
                    it[actualScore] = matchInfo.actualScore
                }
            } catch (e: ExposedSQLException) {
                if (e.message?.contains("no such table") == true) {
                    logger.warn("Table for league ${matchInfo.matchType} did not exist. Created new table.")
                    leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
                        it[actualOutcome] = matchInfo.actualOutcome
                        it[actualScore] = matchInfo.actualScore
                    }
                } else throw e
            }
        }
    }

    fun updateMatchMessageId(matchInfo: MatchInfo) = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
        leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
            it[telegramMessageId] = matchInfo.telegramMessageId
        }
    }

    fun updateMatchStrategyMessageId(matchInfo: MatchInfo) = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
        leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
            it[strategyTelegramMessageId] = matchInfo.strategyTelegramMessageId
        }
    }

    fun updateMatchDatetime(matchInfo: MatchInfo) = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
        leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
            it[datetime] = matchInfo.datetime
        }
    }

    fun updateMatchTeams(matchInfo: MatchInfo) = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
        leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
            it[teams] = matchInfo.teams
        }
    }

    fun updateMatchPredictions(matchInfo: MatchInfo) = transaction {
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
    }

    fun updateMatchOdds(matchInfo: MatchInfo) = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
        leagueTable.update({ leagueTable.fixtureId eq matchInfo.fixtureId }) {
            it[bookmakerName] = matchInfo.bookmakerName
            it[homeWinOdds] = matchInfo.homeWinOdds
            it[drawOdds] = matchInfo.drawOdds
            it[awayWinOdds] = matchInfo.awayWinOdds
            it[odds] = matchInfo.odds
        }
    }

    fun deleteMatchByFixtureId(fixtureId: String, leagueName: String) = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
        leagueTable.deleteWhere { leagueTable.fixtureId eq fixtureId }
    }

    fun matchExists(matchInfo: MatchInfo): Boolean = transaction {
        val leagueTable = LeagueTableFactory.getTableForLeague(matchInfo.matchType)
        createLeagueTableIfNeeded(matchInfo.matchType)
        leagueTable.select { leagueTable.fixtureId eq matchInfo.fixtureId }.count() > 0
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
                        mapRowToMatchInfo(it, leagueTable)
                    } else null
                }
            }
        }
        return allUpcomingMatches
    }

    fun getUpcomingMatchesForLeague(leagueName: String): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val tomorrow = now.plusDays(1)
        val matches = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            leagueTable.selectAll().mapNotNullTo(matches) {
                val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                    .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                if (matchDateTime.isAfter(now) && matchDateTime.isBefore(tomorrow)) {
                    mapRowToMatchInfo(it, leagueTable)
                } else null
            }
        }
        return matches
    }

    fun getMatchesWithoutMessageIdForNext8Hours(): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val eightHoursLater = now.plusHours(5)
        val matchesToSend = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                addMissingColumnsForLeague(leagueName)
                leagueTable.select {
                    (leagueTable.datetime greaterEq now.format(dateTimeFormatter)) and
                            (leagueTable.datetime lessEq eightHoursLater.format(dateTimeFormatter)) and
                            (leagueTable.telegramMessageId.isNull())
                }.mapNotNullTo(matchesToSend) { mapRowToMatchInfo(it, leagueTable) }
            }
        }
        return matchesToSend
    }

    fun getLeagueMatchesWithoutMessageIdForNext20Hours(leagueName: String): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val twentyHoursLater = now.plusHours(17)
        val matchesToSend = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            addMissingColumnsForLeague(leagueName)
            leagueTable.select {
                (leagueTable.datetime greaterEq now.format(dateTimeFormatter)) and
                        (leagueTable.datetime lessEq twentyHoursLater.format(dateTimeFormatter)) and
                        (leagueTable.telegramMessageId.isNull())
            }.mapNotNullTo(matchesToSend) { mapRowToMatchInfo(it, leagueTable) }
        }
        return matchesToSend
    }

    fun getMatchesByLeagueAndTelegramMessageId(leagueName: String, messageId: String): List<MatchInfo> {
        val matches = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            addMissingColumnsForLeague(leagueName)
            leagueTable.select { leagueTable.telegramMessageId eq messageId }
                .mapNotNullTo(matches) { mapRowToMatchInfo(it, leagueTable) }
        }
        return matches
    }

    fun getMatchesByLeagueAndStrategyMessageId(leagueName: String, messageId: String): List<MatchInfo> {
        val matches = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            addMissingColumnsForLeague(leagueName)
            leagueTable.select { leagueTable.strategyTelegramMessageId eq messageId }
                .mapNotNullTo(matches) { mapRowToMatchInfo(it, leagueTable) }
        }
        return matches
    }

    fun getOngoingMatches(): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val threeHoursAgo = now.minusHours(7)
        val actualNow = now.minusHours(3)
        val matchesToUpdate = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                addMissingColumnsForLeague(leagueName)
                leagueTable.selectAll().mapNotNullTo(matchesToUpdate) {
                    val matchDateTime = LocalDateTime.parse(it[leagueTable.datetime], dateTimeFormatter)
                    val isWithinTimeWindow = matchDateTime.isAfter(threeHoursAgo) && matchDateTime.isBefore(actualNow)
                    if (isWithinTimeWindow) mapRowToMatchInfo(it, leagueTable) else null
                }
            }
        }
        return matchesToUpdate
    }

    fun getMatchesFromLastDaysWithoutResult(days: Int): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val startDate = now.minusDays(days.toLong())
        val matchesToUpdate = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                addMissingColumnsForLeague(leagueName)
                leagueTable.selectAll().mapNotNullTo(matchesToUpdate) { row ->
                    val matchDateTime = LocalDateTime.parse(row[leagueTable.datetime], dateTimeFormatter)
                    val isWithinRange = matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now) && row[leagueTable.actualOutcome] == null
                    if (isWithinRange) mapRowToMatchInfo(row, leagueTable) else null
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
                val result = leagueTable.select { leagueTable.fixtureId eq fixtureId }
                    .mapNotNull { mapRowToMatchInfo(it, leagueTable) }
                    .singleOrNull()
                if (result != null) { matchInfo = result; break }
            }
        }
        return matchInfo
    }

    fun getMatchesOlderThanOneDayWithoutResult(date: LocalDate): List<MatchInfo> {
        val matches = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                leagueTable.select {
                    (leagueTable.datetime lessEq date.format(dateFormatter)) and
                            leagueTable.actualOutcome.isNull()
                }.mapNotNullTo(matches) { mapRowToMatchInfo(it, leagueTable) }
            }
        }
        return matches
    }

    fun getLastMatches(days: Int): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val startDate = now.minusDays(days.toLong())
        val matches = mutableListOf<MatchInfo>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                leagueTable.selectAll().mapNotNullTo(matches) { row ->
                    val matchDateTime = LocalDateTime.parse(row[leagueTable.datetime], dateTimeFormatter)
                        .atZone(ZoneId.of("UTC"))
                        .withZoneSameInstant(ZoneId.of("UTC+3"))
                        .toLocalDateTime()
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now) &&
                        row[leagueTable.predictedOutcome] != null && row[leagueTable.actualOutcome] != null
                    ) {
                        mapRowToMatchInfo(row, leagueTable)
                    } else null
                }
            }
        }
        return matches
    }

    fun getLastMatchesForLeague(leagueName: String, days: Int): List<MatchInfo> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val startDate = now.minusDays(days.toLong())
        val matches = mutableListOf<MatchInfo>()
        transaction {
            val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
            leagueTable.selectAll().mapNotNullTo(matches) { row ->
                val matchDateTime = LocalDateTime.parse(row[leagueTable.datetime], dateTimeFormatter)
                    .atZone(ZoneId.of("UTC"))
                    .withZoneSameInstant(ZoneId.of("UTC+3"))
                    .toLocalDateTime()
                if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now) &&
                    row[leagueTable.predictedOutcome] != null && row[leagueTable.actualOutcome] != null
                ) {
                    mapRowToMatchInfo(row, leagueTable)
                } else null
            }
        }
        return matches
    }

    fun updateLeaguePredictability(leagueStatsMap: Map<String, LeagueStats>) {
        transaction {
            leagueStatsMap.forEach { (leagueName, stats) ->
                LeaguePredictability.insertIgnore { it[LeaguePredictability.leagueName] = leagueName }
                LeaguePredictability.update({ LeaguePredictability.leagueName eq leagueName }) {
                    it[roi] = stats.roi
                    it[accuracy] = stats.accuracy
                    it[strategyRoi] = stats.strategyRoi
                    it[strategyAccuracy] = stats.strategyAccuracy
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
            }
        }
    }

    fun getLeaguePredictabilityData(): List<LeagueStats> = transaction {
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
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now) &&
                        it[leagueTable.actualOutcome] != null && it[leagueTable.predictedOutcome] != null) {
                        mapRowToMatchInfo(it, leagueTable)
                    } else null
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
            } else totalReturns -= stake
            val hasStrategyTelegramMessageId = match.strategyTelegramMessageId != null
            if (hasStrategyTelegramMessageId) {
                strategyTotalMatches += 1
                strategyTotalStakes += stake
                if (match.predictedOutcome?.lowercase() == match.actualOutcome?.lowercase()) {
                    strategyCorrectPredictions += 1
                    val profit = (oddsValue * stake) - stake
                    strategyTotalReturns += profit
                } else strategyTotalReturns -= stake
            }
        }
        val accuracy = if (totalMatches > 0) (correctPredictions.toDouble() / totalMatches) * 100 else 0.0
        val roi = if (totalStakes > 0) (totalReturns / totalStakes) * 100 else 0.0
        val strategyAccuracy = if (strategyTotalMatches > 0) (strategyCorrectPredictions.toDouble() / strategyTotalMatches) * 100 else 0.0
        val strategyRoi = if (strategyTotalStakes > 0) (strategyTotalReturns / strategyTotalStakes) * 100 else 0.0
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

    fun getTopPremiumRoiMatchesForPeriod(days: Int, limit: Int): List<Pair<MatchInfo, Double>> {
        val now = LocalDateTime.now(ZoneId.of("UTC+3"))
        val startDate = now.minusDays(days.toLong())
        val matchesWithRoi = mutableListOf<Pair<MatchInfo, Double>>()
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                leagueTable.selectAll().forEach { row ->
                    val matchDateTime = LocalDateTime.parse(row[leagueTable.datetime], dateTimeFormatter)
                        .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC+3")).toLocalDateTime()
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now)) {
                        val predicted = row[leagueTable.predictedOutcome]
                        val actual = row[leagueTable.actualOutcome]
                        val oddsStr = row[leagueTable.odds]
                        val strategyMessageId = row[leagueTable.strategyTelegramMessageId]
                        if (predicted != null && actual != null && oddsStr != null && strategyMessageId != null) {
                            val match = mapRowToMatchInfo(row, leagueTable)
                            val oddsVal = oddsStr.toDoubleOrNull()
                            if (oddsVal != null) {
                                val roi = if (predicted.equals(actual, true)) (oddsVal - 1) * 100 else -100.0
                                matchesWithRoi.add(match to roi)
                            }
                        }
                    }
                }
            }
        }
        return matchesWithRoi.sortedByDescending { it.second }.take(limit)
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
                    if (matchDateTime.isAfter(startDate) && matchDateTime.isBefore(now) &&
                        it[leagueTable.actualOutcome] != null && it[leagueTable.predictedOutcome] != null) {
                        mapRowToMatchInfo(it, leagueTable)
                    } else null
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
            totalMatches++
            val homeTeam = match.teams.split(" vs. ")[0]
            val awayTeam = match.teams.split(" vs. ")[1]
            val predictedOutcome = match.predictedOutcome ?: return@forEach
            val actualOutcome = match.actualOutcome ?: return@forEach
            val stake = 100.0
            totalStakes += stake
            when (predictedOutcome) {
                homeTeam -> {
                    homeWinPredictions++
                    homeWinStakes += stake
                    if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                        homeWinSuccesses++
                        val oddsValue = match.homeWinOdds?.toDoubleOrNull() ?: return@forEach
                        val profit = (oddsValue * stake) - stake
                        homeWinReturns += profit
                    } else homeWinReturns -= stake
                }
                "Draw" -> {
                    drawPredictions++
                    drawStakes += stake
                    if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                        drawSuccesses++
                        val oddsValue = match.drawOdds?.toDoubleOrNull() ?: return@forEach
                        val profit = (oddsValue * stake) - stake
                        drawReturns += profit
                    } else drawReturns -= stake
                }
                awayTeam -> {
                    awayWinPredictions++
                    awayWinStakes += stake
                    if (predictedOutcome.lowercase() == actualOutcome.lowercase()) {
                        awayWinSuccesses++
                        val oddsValue = match.awayWinOdds?.toDoubleOrNull() ?: return@forEach
                        val profit = (oddsValue * stake) - stake
                        awayWinReturns += profit
                    } else awayWinReturns -= stake
                }
            }
            val isStrategyMatch = outcomeStrategyConfigs.any { config -> StrategyService.isMatchFitsStrategy(match, config) }
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
                } else strategyTotalReturns -= stake
            }
        }
        return Statistics(
            totalMatches,
            correctPredictions,
            if (totalMatches > 0) (correctPredictions.toDouble() / totalMatches) * 100 else 0.0,
            if (totalStakes > 0) (totalReturns / totalStakes) * 100 else 0.0,
            strategyTotalMatches,
            strategyCorrectPredictions,
            if (strategyTotalMatches > 0) (strategyCorrectPredictions.toDouble() / strategyTotalMatches) * 100 else 0.0,
            if (strategyTotalStakes > 0) (strategyTotalReturns / strategyTotalStakes) * 100 else 0.0,
            homeWinPredictions,
            homeWinSuccesses,
            if (homeWinPredictions > 0) (homeWinSuccesses.toDouble() / homeWinPredictions) * 100 else 0.0,
            if (homeWinStakes > 0) (homeWinReturns / homeWinStakes) * 100 else 0.0,
            drawPredictions,
            drawSuccesses,
            if (drawPredictions > 0) (drawSuccesses.toDouble() / drawPredictions) * 100 else 0.0,
            if (drawStakes > 0) (drawReturns / drawStakes) * 100 else 0.0,
            awayWinPredictions,
            awayWinSuccesses,
            if (awayWinPredictions > 0) (awayWinSuccesses.toDouble() / awayWinPredictions) * 100 else 0.0,
            if (awayWinStakes > 0) (awayWinReturns / awayWinStakes) * 100 else 0.0
        )
    }

    fun getAllMatchesForLastTwoYears(): List<MatchInfo> {
        val allMatches = mutableListOf<MatchInfo>()
        val twoYearsAgo = LocalDateTime.now().minusYears(2)
        transaction {
            listOfLeagues.forEach { leagueName ->
                val leagueTable = LeagueTableFactory.getTableForLeague(leagueName)
                leagueTable.selectAll().mapNotNullTo(allMatches) { row ->
                    val matchDateTime = LocalDateTime.parse(row[leagueTable.datetime], dateTimeFormatter)
                    if (matchDateTime.isAfter(twoYearsAgo)) mapRowToMatchInfo(row, leagueTable) else null
                }
            }
        }
        return allMatches
    }

    private fun mapRowToMatchInfo(row: ResultRow, leagueTable: LeagueTable): MatchInfo =
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
}
