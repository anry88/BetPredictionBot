import dto.MatchResponse
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import service.footballDataApi

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)

    val telegramBotToken: String = Config.getProperty("telegram.bot.token") ?: throw IllegalStateException("Telegram Token not found")

    try {
        botsApi.registerBot(FootballBot(telegramBotToken))
        logger.info("Football bot started successfully")
    } catch (e: Exception) {
        logger.error("Failed to start football bot", e)
    }
    fetchMatches()
//    startDailyChatGPTRequest()
}
//fun startDailyChatGPTRequest() {
//    val dailyRequestJob = CoroutineScope(Dispatchers.IO).launch {
//        while (isActive) {
//            val matchList = ChatGPTService.getMatchListFromChatGPTWithRetry()
//            println(matchList) // Здесь вы можете добавить код для обработки полученных данных
//
//            delay(TimeUnit.DAYS.toMillis(1))
//        }
//    }
//}
fun fetchMatches() {
    val call = footballDataApi.getMatches()
    call.enqueue(object : Callback<MatchResponse> {
        override fun onResponse(call: Call<MatchResponse>, response: Response<MatchResponse>) {
            if (response.isSuccessful) {
                val matches = response.body()?.matches
                // Process the matches data as needed
                matches?.forEach { match ->
                    println("Match: ${match.homeTeam.name} vs ${match.awayTeam.name}, Score: ${match.score.fullTime.homeTeam} - ${match.score.fullTime.awayTeam}")
                }
            } else {
                println("Request failed with code: ${response.code()}")
            }
        }

        override fun onFailure(call: Call<MatchResponse>, t: Throwable) {
            println("Request failed with error: ${t.message}")
        }
    })
}