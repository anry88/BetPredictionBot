package bot.formatter

import dto.MatchInfo
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class MessageFormatterTest {
    @BeforeTest
    fun setupConfig() {
        File("config.properties").writeText("test=true")
    }

    @AfterTest
    fun cleanupConfig() {
        File("config.properties").delete()
    }

    @Test
    fun premiumCompletedMatchIncludesPrediction() {
        val matchInfo = MatchInfo(
            fixtureId = "1",
            datetime = "2025-05-15 18:00",
            matchType = "Test League",
            teams = "Home vs. Away",
            predictedOutcome = "Away",
            actualOutcome = "Away",
            predictedScore = "0-1",
            actualScore = "0-1",
            odds = "1.80",
            bookmakerName = null,
            homeWinOdds = "3.0",
            drawOdds = "3.2",
            awayWinOdds = "1.80",
            telegramMessageId = null,
            strategyTelegramMessageId = null,
            elapsed = null,
            modelHomeWinProb = 0.1,
            modelDrawProb = 0.1,
            modelAwayWinProb = 0.7,
            modelExpectedHomeGoals = 1.0,
            modelExpectedAwayGoals = 1.2,
            homeMatchesLastYear = 10,
            awayMatchesLastYear = 10
        )

        val result = MessageFormatter.formatMainCompletedMatch(matchInfo, "#tag", includeTestData = false)

        assertTrue(result.contains("Predicted outcome:"))
        assertTrue(result.contains("Predicted score:"))
    }

    @Test
    fun calibratedExpectedGoalsShownInTestMode() {
        val matchInfo = MatchInfo(
            fixtureId = "2",
            datetime = "2025-05-15 20:00",
            matchType = "Test League",
            teams = "Home vs. Away",
            predictedOutcome = "Draw",
            actualOutcome = null,
            predictedScore = "1-1",
            actualScore = null,
            odds = "2.00",
            bookmakerName = null,
            homeWinOdds = "2.5",
            drawOdds = "3.0",
            awayWinOdds = "2.8",
            telegramMessageId = null,
            strategyTelegramMessageId = null,
            elapsed = null,
            modelHomeWinProb = 0.4,
            modelDrawProb = 0.35,
            modelAwayWinProb = 0.25,
            modelExpectedHomeGoals = 1.8,
            modelExpectedAwayGoals = 1.1,
            calibratedExpectedHomeGoals = 1.6,
            calibratedExpectedAwayGoals = 1.0,
            calibratedHomeWinProb = 0.45,
            calibratedDrawProb = 0.3,
            calibratedAwayWinProb = 0.25,
            calibrationApplied = true,
            homeMatchesLastYear = 12,
            awayMatchesLastYear = 12
        )

        val result = MessageFormatter.formatMainUpcomingMatch(matchInfo, "#tag", includeTestData = true)

        assertTrue(result.contains("Calibrated Expected Goals: 1.60 : 1.00"))
    }

    @Test
    fun directUpcomingMatchShowsAwayXgEdgeCheck() {
        val matchInfo = MatchInfo(
            fixtureId = "3",
            datetime = "2026-04-05 20:00",
            matchType = "Test League",
            teams = "Home vs. Away",
            predictedOutcome = "Away",
            actualOutcome = null,
            predictedScore = "0:1",
            actualScore = null,
            odds = "2.12",
            bookmakerName = null,
            homeWinOdds = "3.5",
            drawOdds = "3.3",
            awayWinOdds = "2.12",
            telegramMessageId = null,
            strategyTelegramMessageId = null,
            elapsed = null,
            modelHomeWinProb = 0.18,
            modelDrawProb = 0.24,
            modelAwayWinProb = 0.58,
            modelExpectedHomeGoals = 0.62,
            modelExpectedAwayGoals = 1.42,
            homeMatchesLastYear = 10,
            awayMatchesLastYear = 10
        )

        val result = MessageFormatter.formatDirectUpcomingMatch(matchInfo, league = null)

        assertTrue(result.contains("- xG edge ✅"))
    }
}
