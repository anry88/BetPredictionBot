import dto.MatchInfo
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

class FootballBot(val token: String) : TelegramLongPollingBot() {
    private val logger = LoggerFactory.getLogger(FootballBot::class.java)
    init {
        Config.getProperty("admin.chat.id")?.let { sendMessage(it,"Bot has been started") }
    }

    private val adminChatId = Config.getProperty("admin.chat.id") ?: throw IllegalStateException("Admin chat ID not found in config")

    private var pendingMatches: List<MatchInfo> = emptyList()
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.SECOND_OF_MINUTE, 0, 2, true)
        .optionalEnd()
        .appendPattern("X")
        .toFormatter()

    override fun getBotToken(): String {
        return token
    }

    override fun getBotUsername(): String {
        return "MatchPredictionBot"
    }

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {
            val messageText = update.message.text
            val chatId = update.message.chatId.toString()

            when {
                chatId == adminChatId && messageText.startsWith("/newMatch") -> {
                    val matchesText = messageText.removePrefix("/newMatch").trim()
                    handleNewMatchCommand(chatId, matchesText)
                }
                chatId == adminChatId && messageText == "/confirm" -> {
                    handleConfirmCommand(chatId)
                }
                chatId == adminChatId && messageText == "/reject" -> {
                    handleRejectCommand(chatId)
                }
                messageText == "/upcomingMatches" -> {
                    handleUpcomingMatchesCommand(chatId)
                }
                messageText == "/topMatch" -> {
                    handleTopMatchCommand(chatId)
                }
                else -> {
                    val responseText = processMessage(messageText)
                    val message = SendMessage(chatId, responseText)
                    execute(message)
                }
            }
        }
    }

    private fun handleNewMatchCommand(chatId: String, matchesText: String) {
        runBlocking {
            pendingMatches = ChatGPTService.getMatchPredictionsWithRetry(matchesText)
            if (pendingMatches.isNotEmpty()) {
                pendingMatches.forEach {
                    sendMessage(chatId, formatMatchInfo(it))
                }
                sendMessage(chatId, "Please confirm the predictions by typing /confirm or reject by typing /reject")
            } else {
                sendMessage(chatId, "Failed to get match predictions after 3 attempts.")
            }
        }
    }

    private fun handleConfirmCommand(chatId: String) {
        if (pendingMatches.isNotEmpty()) {
            CSVService.appendRows(pendingMatches)
            sendMessage(chatId, "Predictions confirmed and saved.")
            pendingMatches = emptyList()
        } else {
            sendMessage(chatId, "No predictions to confirm.")
        }
    }

    private fun handleRejectCommand(chatId: String) {
        if (pendingMatches.isNotEmpty()) {
            sendMessage(chatId, "Predictions have been rejected.")
            pendingMatches = emptyList()
        } else {
            sendMessage(chatId, "No predictions to reject.")
        }
    }

    private fun handleUpcomingMatchesCommand(chatId: String) {
        runBlocking {
            val upcomingMatches = CSVService.getUpcomingMatches()
            if (upcomingMatches.isNotEmpty()) {
                upcomingMatches.forEach {
                    sendMessage(chatId, formatMatchInfo(it))
                }
            } else {
                sendMessage(chatId, "No upcoming matches within the next 24 hours.")
            }
        }
    }

    private fun handleTopMatchCommand(chatId: String) {
        val upcomingMatches = CSVService.getUpcomingMatches()
        val topMatch = upcomingMatches
            .filter { it.odds.toDouble() in 1.5..2.5 }
            .maxByOrNull { it.odds }

        val responseText = if (topMatch != null) {
            """
            [Top Match]
            Match Time: ${topMatch.datetime}
            Match Type: ${topMatch.matchType}
            Teams: ${topMatch.teams}}
            Predicted Outcome: ${topMatch.outcome}
            Score: ${topMatch.score}
            Odds: ${topMatch.odds}
            """.trimIndent()
        } else {
            "No top match found."
        }

        val message = SendMessage(chatId, responseText)
        execute(message)
    }


    private fun formatMatchInfo(matchInfo: MatchInfo): String {
        return """
            Match Time: ${matchInfo.datetime}
            Match Type: ${matchInfo.matchType}
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.outcome}
            Score: ${matchInfo.score}
            Odds: ${matchInfo.odds}
        """.trimIndent()
    }

     private fun processMessage(messageText: String): String {
        return "This is a response to: $messageText"
    }

    private fun sendMessage(chatId: String, text: String) {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text

        try {
            execute(message)
            logger.info("Sent message to chat $chatId")
        } catch (e: Exception) {
            logger.error("Failed to send message to chat $chatId", e)
        }
    }
}
