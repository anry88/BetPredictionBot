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
}
