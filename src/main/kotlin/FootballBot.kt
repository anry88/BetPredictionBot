import DatabaseService.getCorrectPredictionsForPeriod
import DatabaseService.getCorrectPredictionsLast24Hours
import DatabaseService.getMatchesWithoutMessageIdForNext12Hours
import dto.MatchInfo
import `interface`.TelegramService
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class FootballBot(private val token: String) : TelegramLongPollingBot(), TelegramService {
    private val logger = LoggerFactory.getLogger(FootballBot::class.java)
    private val adminChatId = Config.getProperty("admin.chat.id") ?: throw IllegalStateException("Admin chat ID not found in config")
    private val channelId: String = Config.getProperty("channel.chat.id") ?: throw IllegalStateException("Channel ChatID not found")

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

    override fun sendMessageAndGetId(chatId: String, text: String): Int? {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        message.disableNotification = true

        return try {
            val sentMessage = execute(message)
            sentMessage.messageId
        } catch (e: Exception) {
            logger.error("Failed to send message", e)
            null
        }
    }

    override fun updateMessage(chatId: String, messageId: String, text: String) {
        try {
            val editMessage = EditMessageText()
            editMessage.chatId = chatId
            editMessage.messageId = messageId.toInt()
            editMessage.text = text

            execute(editMessage)
            logger.info("Message with ID $messageId updated successfully")
        } catch (e: TelegramApiRequestException) {
            if (e.apiResponse == "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message") {
                logger.info("No update needed for message with ID $messageId as the content is already up to date")
            } else {
                logger.error("Failed to update message with ID $messageId", e)
            }
        } catch (e: Exception) {
            logger.error("Failed to update message with ID $messageId", e)
        }
    }

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {
            val messageText = update.message.text
            val chatId = update.message.chatId.toString()
            val userId = update.message.from.id.toString()
            val firstName = update.message.from.firstName
            val lastName = update.message.from.lastName
            val username = update.message.from.userName

            // Записываем активность пользователя
            DatabaseService.addUserActivity(userId, firstName, lastName, username)

            when {
                chatId == adminChatId && messageText == "/getdatabase" -> {
                    handleGetDatabaseCommand(chatId)
                }
                chatId == adminChatId && messageText == "/usercount" -> {
                    handleUserCountCommand(chatId)
                }
                chatId == adminChatId && messageText == "/activeusercount" -> {
                    handleActiveUserCountCommand(chatId)
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
            /usercount - Get the count of unique users
            /activeusercount - Get the count of unique users active last day
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

    private fun handleUserCountCommand(chatId: String) {
        val userCount = DatabaseService.getUserCount()
        sendMessage(chatId, "Number of unique users: $userCount")
    }
    private fun handleActiveUserCountCommand(chatId: String) {
        val userCount = DatabaseService.getActiveUserCountLast24Hours()
        sendMessage(chatId, "Number of unique users for last day: $userCount")
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
            .filter {
                val odds = it.odds?.toDoubleOrNull()
                odds != null && odds in 1.5..2.5
            }
            .maxByOrNull { it.odds?.toDoubleOrNull() ?: Double.MIN_VALUE }

        val responseText = if (topMatch != null) {
            """
        [Top Match]
        Match Time: ${topMatch.datetime}
        Match Type: ${topMatch.matchType}
        Teams: ${topMatch.teams}
        Predicted Outcome: ${topMatch.predictedOutcome}
        """.trimIndent()
        } else {
            "No top match found."
        }

        val message = SendMessage(chatId, responseText)
        execute(message)
    }


    fun formatMatchInfo(matchInfo: MatchInfo): String {
        return """
            Match Time: ${matchInfo.datetime}
            Match Type: ${matchInfo.matchType}
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.predictedOutcome}
        """.trimIndent()
    }
    fun formatMatchInfoWithResult(matchInfo: MatchInfo): String{
        val isPredictionCorrect = matchInfo.predictedOutcome == matchInfo.actualOutcome
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        return """
            Match Time: ${matchInfo.datetime}
            Match Type: ${matchInfo.matchType}
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.predictedOutcome}
            Actual Outcome: ${matchInfo.actualOutcome}$emoji
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
            commands.add(BotCommand("/usercount", "Get the count of unique users"))
            commands.add(BotCommand("/activeusercount", "Get the count of unique users active last day"))
        }

        val setMyCommands = SetMyCommands()
        setMyCommands.commands = commands

        try {
            execute(setMyCommands)
        } catch (e: Exception) {
            logger.error("Failed to set bot commands", e)
        }
    }
    fun sendPredictionAccuracyMessage() {
        val result = getCorrectPredictionsLast24Hours()
        val accuracy = result.first
        val correct = result.second.first
        val totalMatches = result.second.second
        val messageText = "The accuracy of predictions in the last 24 hours is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."

        val message = SendMessage()
        message.chatId = channelId
        message.text = messageText
        message.disableNotification = true

        try {
            execute(message)
            logger.info("Prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send prediction accuracy message", e)
        }
    }
    fun sendUpcomingMatchesToTelegram() {
        val matches = getMatchesWithoutMessageIdForNext12Hours()

        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                val messageText = formatMatchInfo(match)
                val messageId = sendMessageAndGetId(channelId, messageText)

                if (messageId != null) {
                    val updatedMatchInfo = match.copy(telegramMessageId = messageId.toString())
                    DatabaseService.updateMatchMessageId(updatedMatchInfo)
                }
            }
        }
    }
    private fun getDaysInLastMonth(): Int {
        val currentDate = LocalDate.now()
        val lastMonth = currentDate.minusMonths(1)
        val lastMonthYearMonth = YearMonth.of(lastMonth.year, lastMonth.month)
        return lastMonthYearMonth.lengthOfMonth()
    }
    fun sendWeeklyPredictionAccuracyMessage() {
        val result = getCorrectPredictionsForPeriod(days = 7)
        val accuracy = result.first
        val correct = result.second.first
        val totalMatches = result.second.second
        val messageText = "The accuracy of predictions in the last week is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."

        val message = SendMessage()
        message.chatId = channelId
        message.text = messageText
        message.disableNotification = true

        try {
            execute(message)
            logger.info("Weekly prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send weekly prediction accuracy message", e)
        }
    }
    fun sendMonthlyPredictionAccuracyMessage() {
        val result = getCorrectPredictionsForPeriod(getDaysInLastMonth())
        val accuracy = result.first
        val correct = result.second.first
        val totalMatches = result.second.second
        val messageText = "The accuracy of predictions in the last month is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."

        val message = SendMessage()
        message.chatId = channelId
        message.text = messageText
        message.disableNotification = true

        try {
            execute(message)
            logger.info("Monthly prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send monthly prediction accuracy message", e)
        }
    }

}
