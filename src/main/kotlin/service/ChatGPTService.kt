import dto.MatchInfo
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import service.HttpChatGPTService

object ChatGPTService {
    private val logger = LoggerFactory.getLogger(ChatGPTService::class.java)

    suspend fun getMatchPredictionsWithRetry(matchesText: String, retries: Int = 3, delayMillis: Long = 1000): List<MatchInfo> {
        repeat(retries) { attempt ->
            val matchInfoList = getMatchPredictionsFromChatGPT(matchesText)
            if (matchInfoList.isNotEmpty()) {
                val newMatches = matchInfoList.filterNot { DatabaseService.matchExists(it) }
                if (newMatches.isNotEmpty()) {
                    logger.info("Successfully retrieved match predictions on attempt ${attempt + 1}")
                    return newMatches
                } else {
                    logger.info("All matches are duplicates on attempt ${attempt + 1}, no retry needed")
                    return emptyList()
                }
            } else {
                logger.warn("Attempt ${attempt + 1} failed to retrieve match predictions")
                delay(delayMillis)
            }
        }
        logger.error("Failed to retrieve match predictions after $retries attempts")
        return emptyList()
    }

    private suspend fun getMatchPredictionsFromChatGPT(matchesText: String): List<MatchInfo> {
        val response = HttpChatGPTService.api.getChatGPTRequest(
            ChatGPTRequest(
                model = "gpt-4o",
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

        return parseMatchInfo(predictionsText)
    }

    private fun parseMatchInfo(text: String): List<MatchInfo> {
        val matchInfoList = mutableListOf<MatchInfo>()
        val regex = """\[Match Start\]: \[(.+?)\]\s*\[Match Type\]: \[(.+?)\]\s*\[Teams\]: \[(.+?) vs\. (.+?)\]\s*\[Match Outcome\]: \[(.+?)\]\s*\[Score\]: \[(.+?)\]\s*\[Odd for Match Outcome\]: \[(.+?)\]""".toRegex()
        val matches = regex.findAll(text)

        for (match in matches) {
            val datetime = match.groups[1]?.value?.trim() ?: ""
            val matchType = match.groups[2]?.value?.trim() ?: ""
            val teams = "${match.groups[3]?.value?.trim()} vs. ${match.groups[4]?.value?.trim()}"
            val outcome = match.groups[5]?.value?.trim() ?: ""
            val score = match.groups[6]?.value?.trim() ?: ""
            val odds = match.groups[7]?.value?.trim() ?: ""

            val matchInfo = MatchInfo(datetime, matchType, teams, outcome, null, score, null, odds, null)
            matchInfoList.add(matchInfo)
        }

        return matchInfoList
    }
}
