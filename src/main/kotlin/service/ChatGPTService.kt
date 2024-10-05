package service

import `interface`.ChatGPTRequest
import dto.MatchInfo
import `interface`.Message
import org.slf4j.LoggerFactory

object ChatGPTService {
    private val logger = LoggerFactory.getLogger(ChatGPTService::class.java)

    suspend fun getMatchPrediction(matchInfo: MatchInfo): MatchInfo? {
        return try{
            val matchesText = "[Match Start UTC]: [${matchInfo.datetime}] [Match Type]: [${matchInfo.matchType}] [Teams]: [${matchInfo.teams}]"

            val response = HttpChatGPTService.api.getChatGPTRequest(
                ChatGPTRequest(
//                    model = "gpt-4o-2024-05-13", //old version
                    model = "gpt-4o-2024-08-06", //new version
                    messages = listOf(
                        Message(
                            role = "user",
                            content = "Make a prediction for the outcome of these football matches that will take place in the near future, for full time, taking into account all possible factors, expert opinions and bookmakers' forecasts for the matches\n: $matchesText \n" +
                                    "You are a data assistant. Always strictly provide responses in the following format without any text formatting other than square brackets and don't change match start time, match type and teams:\n" +
                                    "\n" +
                                    "[Match Start]: [yyyy-MM-dd HH:mm]\n" +
                                    "[Match Type]: []\n" +
                                    "[Teams]: [Team1 vs. Team2]\n" +
                                    "[Match Outcome]: [Team/Draw]\n" +
                                    "[Score]: [int:int]\n" +
                                    "[Odd for Match Outcome]: [double]\n" +
                                    "\n"
                        )
                    ),
                    max_tokens = 1000,
                    temperature = 1.0
                )
            )

            val predictionsText = response.choices.first().message.content
            return parseSingleMatchInfo(predictionsText, matchInfo.fixtureId)
        } catch (e: Exception) {
            logger.error("Error getting prediction from ChatGPT: ${e.message}")
            null
        }
    }

    private fun parseSingleMatchInfo(text: String, fixtureId: String): MatchInfo? {
        val regex = """\[Match Start\]: \[(.+?)\]\s*\[Match Type\]: \[(.+?)\]\s*\[Teams\]: \[(.+?) vs\. (.+?)\]\s*\[Match Outcome\]: \[(.+?)\]\s*\[Score\]: \[(.+?)\]\s*\[Odd for Match Outcome\]: \[(.+?)\]""".toRegex()
        val match = regex.find(text)

        if (match != null) {
            val datetime = match.groups[1]?.value?.trim() ?: ""
            val matchType = match.groups[2]?.value?.trim() ?: ""
            val teams = "${match.groups[3]?.value?.trim()} vs. ${match.groups[4]?.value?.trim()}"
            val outcome = match.groups[5]?.value?.trim() ?: ""
            val score = match.groups[6]?.value?.trim() ?: ""
            val odds = match.groups[7]?.value?.trim() ?: ""

            return MatchInfo(
                fixtureId = fixtureId,
                datetime = datetime,
                matchType = matchType,
                teams = teams,
                predictedOutcome = outcome,
                predictedScore = score,
                odds = odds,
                actualOutcome = null,
                actualScore = null,
                telegramMessageId = null
            )
        } else {
            logger.error("Failed to parse match info from text: $text")
            return null
        }
    }
}
