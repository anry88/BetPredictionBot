import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.slf4j.LoggerFactory
import service.HttpFootBallDataService

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)

    val telegramBotToken: String =
        Config.getProperty("telegram.bot.token") ?: throw IllegalStateException("Telegram Token not found")

    try {
        botsApi.registerBot(FootballBot(telegramBotToken))
        logger.info("Football bot started successfully")
    } catch (e: Exception) {
        logger.error("Failed to start football bot", e)
    }

    val footballDataService = HttpFootBallDataService()
    footballDataService.fetchMatches()
}
