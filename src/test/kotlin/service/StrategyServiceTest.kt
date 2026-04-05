package service

import dto.MatchInfo
import dto.OutcomeType
import dto.outcomeStrategyConfigs
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrategyServiceTest {
    @BeforeTest
    fun setupConfig() {
        File("config.properties").writeText("test=true")
    }

    @AfterTest
    fun cleanupConfig() {
        File("config.properties").delete()
    }

    @Test
    fun awayStrategyAcceptsMatchWithStrongSignedXgEdge() {
        val config = outcomeStrategyConfigs.first { it.outcomeType == OutcomeType.AwayWin }
        val match = baseMatch(
            awayWinOdds = "2.12",
            modelHomeWinProb = 0.18,
            modelDrawProb = 0.24,
            modelAwayWinProb = 0.58,
            modelExpectedHomeGoals = 0.62,
            modelExpectedAwayGoals = 1.42
        )

        assertTrue(StrategyService.isMatchFitsStrategy(match, config))
    }

    @Test
    fun awayStrategyRejectsMatchWithoutRequiredSignedXgEdge() {
        val config = outcomeStrategyConfigs.first { it.outcomeType == OutcomeType.AwayWin }
        val match = baseMatch(
            awayWinOdds = "2.12",
            modelHomeWinProb = 0.18,
            modelDrawProb = 0.24,
            modelAwayWinProb = 0.58,
            modelExpectedHomeGoals = 0.66,
            modelExpectedAwayGoals = 1.40
        )

        assertFalse(StrategyService.isMatchFitsStrategy(match, config))
    }

    @Test
    fun drawStrategyRejectsMatchWhenXgDiffExceedsTightenedThreshold() {
        val config = outcomeStrategyConfigs.first { it.outcomeType == OutcomeType.Draw }
        val match = baseMatch(
            drawOdds = "3.30",
            modelHomeWinProb = 0.28,
            modelDrawProb = 0.38,
            modelAwayWinProb = 0.34,
            modelExpectedHomeGoals = 0.75,
            modelExpectedAwayGoals = 0.84
        )

        assertFalse(StrategyService.isMatchFitsStrategy(match, config))
    }

    private fun baseMatch(
        awayWinOdds: String = "2.40",
        drawOdds: String = "3.20",
        modelHomeWinProb: Double,
        modelDrawProb: Double,
        modelAwayWinProb: Double,
        modelExpectedHomeGoals: Double,
        modelExpectedAwayGoals: Double
    ) = MatchInfo(
        fixtureId = "1",
        datetime = "2026-04-05 18:00",
        matchType = "Any League",
        teams = "Home vs. Away",
        predictedOutcome = null,
        actualOutcome = null,
        predictedScore = null,
        actualScore = null,
        odds = null,
        bookmakerName = null,
        homeWinOdds = "3.50",
        drawOdds = drawOdds,
        awayWinOdds = awayWinOdds,
        telegramMessageId = null,
        strategyTelegramMessageId = null,
        elapsed = null,
        modelHomeWinProb = modelHomeWinProb,
        modelDrawProb = modelDrawProb,
        modelAwayWinProb = modelAwayWinProb,
        modelExpectedHomeGoals = modelExpectedHomeGoals,
        modelExpectedAwayGoals = modelExpectedAwayGoals,
        homeMatchesLastYear = 10,
        awayMatchesLastYear = 10
    )
}
