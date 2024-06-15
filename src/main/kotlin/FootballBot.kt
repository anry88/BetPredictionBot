import dto.MatchInfo
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.slf4j.LoggerFactory

class FootballBot(val token: String) : TelegramLongPollingBot() {
    private val logger = LoggerFactory.getLogger(FootballBot::class.java)

    init {
        Config.getProperty("admin.chat.id")?.let { sendMessage(it, "Bot has been started") }
        setCommands()
    }

    private val adminChatId = Config.getProperty("admin.chat.id") ?: throw IllegalStateException("Admin chat ID not found in config")

    private var pendingMatches: List<MatchInfo> = emptyList()

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
                chatId == adminChatId && messageText.startsWith("/newmatch") -> {
                    val matchesText = messageText.removePrefix("/newmatch").trim()
                    handleNewMatchCommand(chatId, matchesText)
                }
                chatId == adminChatId && messageText == "/confirm" -> {
                    handleConfirmCommand(chatId)
                }
                chatId == adminChatId && messageText == "/reject" -> {
                    handleRejectCommand(chatId)
                }
                messageText == "/upcomingmatches" -> {
                    handleUpcomingMatchesCommand(chatId)
                }
                messageText == "/topmatch" -> {
                    handleTopMatchCommand(chatId)
                }
                messageText == "/start" -> {
                    handleStartCommand(chatId)
                }
                messageText == "/help" -> {
                    handleHelpCommand(chatId, chatId == adminChatId)
                }
                else -> {
                    val responseText = processMessage(messageText)
                    val message = SendMessage(chatId, responseText)
                    execute(message)
                }
            }
        }
    }

    private fun handleStartCommand(chatId: String) {
        val description = """
                Welcome to the Football Prediction Bot!
    
                No one can truly predict the future, but our Football Prediction Bot uses advanced analysis to estimate the outcomes of football matches. By leveraging in-depth analysis of team conditions, expert opinions, and bookmaker data, this bot provides insightful predictions.
    
                Please note that the predictions provided by this bot are for informational purposes only and are not recommendations for betting. Use the information at your own discretion and be aware of the regulations in your country regarding sports betting.
    
                To get a list of available commands, use /help.
            """.trimIndent()

        sendMessage(chatId, description)
    }

    private fun handleHelpCommand(chatId: String, isAdmin: Boolean) {
        val commonCommands = """
            /start - Start the bot and get information about it
            /upcomingmatches - Get upcoming matches within the next 24 hours
            /topmatch - Get the top match based on odds
        """.trimIndent()

        val adminCommands = """
            /newmatch - Add new match predictions
            /confirm - Confirm and save the pending match predictions
            /reject - Reject the pending match predictions
        """.trimIndent()

        val responseText = if (isAdmin) {
            "$commonCommands\n$adminCommands"
        } else {
            commonCommands
        }

        sendMessage(chatId, responseText)
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
            Teams: ${topMatch.teams}
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

    private fun setCommands() {
        val commands = mutableListOf<BotCommand>()
        commands.add(BotCommand("/start", "Start the bot and get information about it"))
        commands.add(BotCommand("/upcomingmatches", "Get upcoming matches within the next 24 hours"))
        commands.add(BotCommand("/topmatch", "Get the top match based on odds"))
        commands.add(BotCommand("/help", "Get the list of available commands"))

        val setMyCommands = SetMyCommands()
        setMyCommands.commands = commands

        try {
            execute(setMyCommands)
        } catch (e: Exception) {
            logger.error("Failed to set bot commands", e)
        }
    }
}
