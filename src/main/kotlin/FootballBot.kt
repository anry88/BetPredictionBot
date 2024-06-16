import dto.MatchInfo
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.slf4j.LoggerFactory
import service.DatabaseService
import service.HttpFootBallDataService
import service.initDatabase
import java.io.File

class FootballBot(val token: String) : TelegramLongPollingBot() {
    private val logger = LoggerFactory.getLogger(FootballBot::class.java)
    private val httpFootBallDataService = HttpFootBallDataService()
    private val adminChatId = Config.getProperty("admin.chat.id") ?: throw IllegalStateException("Admin chat ID not found in config")

    init {
        Config.getProperty("admin.chat.id")?.let { sendMessage(it, "Bot has been started") }
        initDatabase("predictions.db") // Используем правильный путь к вашему файлу базы данных
        setCommands()
    }

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
                chatId == adminChatId && messageText == "/getdatabase" -> {
                    handleGetDatabaseCommand(chatId)
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
            /topmatch - Get the top match
        """.trimIndent()

        val adminCommands = """
            /getdatabase - Get the database file
        """.trimIndent()

        val responseText = if (isAdmin) {
            "$commonCommands\n$adminCommands"
        } else {
            commonCommands
        }

        sendMessage(chatId, responseText)
    }

    private fun handleGetDatabaseCommand(chatId: String) {
        val databaseFile = File("predictions.db")
        if (databaseFile.exists()) {
            val document = SendDocument()
            document.chatId = chatId
            document.document = InputFile(databaseFile)
            document.caption = "Here is the database file."
            execute(document)
        } else {
            sendMessage(chatId, "Database file not found.")
        }
    }

    private fun handleUpcomingMatchesCommand(chatId: String) {
        val upcomingMatches = DatabaseService.getUpcomingMatches()
        if (upcomingMatches.isNotEmpty()) {
            upcomingMatches.forEach {
                sendMessage(chatId, formatMatchInfo(it))
            }
        } else {
            sendMessage(chatId, "No upcoming matches within the next 24 hours.")
        }
    }

    private fun handleTopMatchCommand(chatId: String) {
        val upcomingMatches = DatabaseService.getUpcomingMatches()
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

        if (adminChatId.isNotEmpty()) {
            commands.add(BotCommand("/getdatabase", "Get the database file"))
        }

        val setMyCommands = SetMyCommands()
        setMyCommands.commands = commands

        try {
            execute(setMyCommands)
        } catch (e: Exception) {
            logger.error("Failed to set bot commands", e)
        }
    }
}
