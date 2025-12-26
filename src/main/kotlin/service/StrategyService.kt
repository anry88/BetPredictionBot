package service

import Config
import dto.LeagueConfig
import dto.MatchInfo
import dto.OutcomeStrategyConfig
import dto.OutcomeType
import kotlinx.serialization.json.Json
import kotlin.math.abs

object StrategyService {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val leaguesConfig: List<LeagueConfig> = loadLeaguesConfig()

    private fun loadLeaguesConfig(): List<LeagueConfig> {
        val leaguesJson = javaClass.getResource("/leagues.json")?.readText()
            ?: throw IllegalStateException("leagues.json not found")
        return json.decodeFromString(leaguesJson)
    }

    fun getModelPreferredOutcome(match: MatchInfo): OutcomeType? {
        val homeProb = match.modelHomeWinProb ?: return null
        val drawProb = match.modelDrawProb ?: return null
        val awayProb = match.modelAwayWinProb ?: return null

        return when {
            homeProb >= drawProb && homeProb >= awayProb -> OutcomeType.HomeWin
            drawProb >= homeProb && drawProb >= awayProb -> OutcomeType.Draw
            else -> OutcomeType.AwayWin
        }
    }

    fun getOutcomeProbability(match: MatchInfo, outcomeType: OutcomeType): Double? {
        return when (outcomeType) {
            OutcomeType.HomeWin -> match.modelHomeWinProb
            OutcomeType.Draw -> match.modelDrawProb
            OutcomeType.AwayWin -> match.modelAwayWinProb
        }
    }

    fun getOutcomeOdds(match: MatchInfo, outcomeType: OutcomeType): Double? {
        return when (outcomeType) {
            OutcomeType.HomeWin -> match.homeWinOdds?.toDoubleOrNull()
            OutcomeType.Draw -> match.drawOdds?.toDoubleOrNull()
            OutcomeType.AwayWin -> match.awayWinOdds?.toDoubleOrNull()
        }
    }

    fun isMatchFitsStrategy(match: MatchInfo, config: OutcomeStrategyConfig): Boolean {
        val teams = match.teams.split(" vs. ")
        if (teams.size != 2) return false

        val homeCount = match.homeMatchesLastYear ?: 0
        val awayCount = match.awayMatchesLastYear ?: 0
        if (homeCount <= 5 || awayCount <= 5) return false

        val preferredOutcome = getModelPreferredOutcome(match) ?: return false
        if (preferredOutcome != config.outcomeType) return false

        val isFromLocalModel = match.modelHomeWinProb != null
        val isPremiumSelection =
            if (Config.getProperty("test")?.toBoolean() == true) true else leaguesConfig.any { it.description == match.matchType && it.premiumSelection }
        if (!isPremiumSelection || !isFromLocalModel) return false

        val oddsValue = getOutcomeOdds(match, config.outcomeType) ?: return false
        if (oddsValue < config.minOdds || oddsValue > config.maxOdds) return false

        val probability = getOutcomeProbability(match, config.outcomeType) ?: return false
        if (probability < config.minProb) return false
        config.maxProb?.let { if (probability > it) return false }

        if (config.outcomeType == OutcomeType.Draw) {
            val expectedHomeGoals = match.modelExpectedHomeGoals ?: return false
            val expectedAwayGoals = match.modelExpectedAwayGoals ?: return false
            val xgDiff = abs(expectedHomeGoals - expectedAwayGoals)
            val xgTotal = expectedHomeGoals + expectedAwayGoals
            config.maxXgDiff?.let { if (xgDiff > it) return false }
            config.maxXgTotal?.let { if (xgTotal > it) return false }
        }

        return true
    }
}
