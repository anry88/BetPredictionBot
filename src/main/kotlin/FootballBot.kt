import dto.MatchInfo
import dto.TagsData
import `interface`.TelegramService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
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
import service.DatabaseService
import service.DatabaseService.getCorrectPredictionsForPeriod
import service.DatabaseService.getMatchesWithoutMessageIdForNext5Hours
import service.HttpAPIFootballService
import service.initDatabase
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class FootballBot(private val token: String) : TelegramLongPollingBot(), TelegramService {
    private val logger = LoggerFactory.getLogger(FootballBot::class.java)
    private val adminChatId = Config.getProperty("admin.chat.id") ?: throw IllegalStateException("Admin chat ID not found in config")
    private val channelId: String = Config.getProperty("channel.chat.id") ?: throw IllegalStateException("Channel ChatID not found")
    private val footballService = HttpAPIFootballService(this)

    private val leagueTags: Map<String, String>
    private val teamTags: Map<String, String>

    init {
        Config.getProperty("admin.chat.id")?.let { sendMessage(it, "Bot has been started") }
        initDatabase("predictions.db") // Используем правильный путь к вашему файлу базы данных
        setCommands()
        val tags = loadTags()
        leagueTags = tags.first
        teamTags = tags.second
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
                chatId == adminChatId && messageText == "/upcomingmatches" -> {
                    handleUpcomingMatchesCommand(chatId)
                }
                chatId == adminChatId && messageText == "/topmatch" -> {
                    handleTopMatchCommand(chatId)
                }
                chatId == adminChatId && messageText.startsWith("/getAccuracy") -> {
                    handleGetAccuracyCommand(chatId, messageText)
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

    private fun loadTags(): Pair<Map<String, String>, Map<String, String>> {
        val fileContent = javaClass.getResource("/tags.json")?.readText() ?: throw IllegalStateException("leagues.json not found")
        val json = Json { ignoreUnknownKeys = true }
        val tagsData = json.decodeFromString<TagsData>(fileContent)
        return Pair(tagsData.leagues, tagsData.teams)
    }

    private fun getTags(matchType: String, teams: String): String {
        val tags = mutableSetOf<String>()

        // Добавляем тег для лиги
        leagueTags.forEach { (leagueName, tag) ->
            if (matchType.contains(leagueName, ignoreCase = true)) {
                tags.add(tag)
            }
        }

        // Добавляем теги для команд
        teamTags.forEach { (teamName, tag) ->
            if (teams.contains(teamName, ignoreCase = true)) {
                tags.add(tag)
            }
        }

        // Преобразуем набор тегов в строку с пробелами между тегами
        return if (tags.isNotEmpty()) tags.joinToString(" ") else ""
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
        """.trimIndent()

        val adminCommands = """
            /getdatabase - Get the database file
            /usercount - Get the count of unique users
            /activeusercount - Get the count of unique users active last day
            /upcomingmatches - Get upcoming matches within the next 24 hours
            /topmatch - Get the top match
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


    private fun formatMatchInfo(matchInfo: MatchInfo): String {
        val flag = getCountryFlag(matchInfo.matchType)
        val matchType = if (matchInfo.matchType.split(" ")[0] != "World") matchInfo.matchType else matchInfo.matchType.replaceFirst("World", "").trimIndent()
        val tags = getTags(matchType, matchInfo.teams)

        return """
            Match Time UTC: ${matchInfo.datetime}
            Match Type: $matchType$flag
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.predictedOutcome}
            Predicted Score: ${matchInfo.predictedScore}
            $tags
        """.trimIndent()
    }
    fun formatMatchInfoWithResult(matchInfo: MatchInfo): String{
        val isPredictionCorrect = matchInfo.predictedOutcome?.lowercase() == matchInfo.actualOutcome?.lowercase()
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val flag = getCountryFlag(matchInfo.matchType)
        val matchType = if (matchInfo.matchType.split(" ")[0] != "World") matchInfo.matchType else matchInfo.matchType.replaceFirst("World", "").trimIndent()
        val tags = getTags(matchType, matchInfo.teams)

        return """
            Match Time UTC: ${matchInfo.datetime}
            Match Type: $matchType$flag
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.predictedOutcome}$emoji
            Actual Outcome: ${matchInfo.actualOutcome}
            Predicted Score: ${matchInfo.predictedScore}
            Actual Score: ${matchInfo.actualScore}
            $tags
        """.trimIndent()
    }

    private fun formatLiveMatch(matchInfo: MatchInfo): String{
        val flag = getCountryFlag(matchInfo.matchType)
        val matchType = if (matchInfo.matchType.split(" ")[0] != "World") matchInfo.matchType else matchInfo.matchType.replaceFirst("World", "").trimIndent()
        val tags = getTags(matchType, matchInfo.teams)

        return """
            Match Time UTC: ${matchInfo.datetime}
            Match Type: $matchType$flag
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.predictedOutcome}
            Predicted Score: ${matchInfo.predictedScore}
            Current Score: ${matchInfo.actualScore} ${matchInfo.elapsed}'
            #Live $tags
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
        commands.add(BotCommand("/help", "Get the list of available commands"))

        val setMyCommands = SetMyCommands()
        setMyCommands.commands = commands

        try {
            execute(setMyCommands)
        } catch (e: Exception) {
            logger.error("Failed to set bot commands", e)
        }
    }
    fun sendPredictionAccuracyMessage() {
        val result = getCorrectPredictionsForPeriod(days = 1)
        val accuracy = result.first
        val correct = result.second.first
        val totalMatches = result.second.second

        val messageText = if (totalMatches > 0) {
            // Сообщение с точностью исходов прогнозов и уточнением, что речь не о счетах
            "The accuracy of outcome predictions (not scores) in the last 24 hours is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."
        } else {
            // Сообщение об отсутствии матчей
            "No matches were played in the last 24 hours."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText

        try {
            execute(message)
            logger.info("Prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send prediction accuracy message", e)
        }
    }
    fun sendUpcomingMatchesToTelegram() {
        val matches = getMatchesWithoutMessageIdForNext5Hours()

        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                val messageText = formatMatchInfo(match)
                val messageId = sendMessageAndGetId(channelId, messageText)

                if (messageId != null) {
                    val updatedMatchInfo = match.copy(telegramMessageId = messageId.toString())
                    DatabaseService.updateMatchMessageId(updatedMatchInfo)
                }
                Thread.sleep(10000)
            }
        }
    }

    suspend fun updateLiveMatches() {
        val matchesToUpdate = DatabaseService.getOngoingMatches()
        for (match in matchesToUpdate) {
            val updatedMatchInfo = footballService.getLiveMatchInfo(match.fixtureId)
            if (updatedMatchInfo != null) {
                // Обновляем базу данных с новыми actualScore и actualOutcome
                DatabaseService.updateMatchResult(updatedMatchInfo)
                // Выбираем форматирование в зависимости от статуса матча
                val messageText = if (updatedMatchInfo.actualOutcome != null) {
                    // Матч завершён, используем финальное форматирование
                    formatMatchInfoWithResult(updatedMatchInfo)
                } else {
                    // Матч ещё идёт, используем форматирование для текущих матчей
                    formatLiveMatch(updatedMatchInfo)
                }
                // Обновляем сообщение в Telegram
                val messageId = updatedMatchInfo.telegramMessageId
                if (messageId != null) {
                    updateMessage(channelId, messageId, messageText)
                } else {
                    logger.warn("No telegramMessageId for match with fixtureId ${updatedMatchInfo.fixtureId}")
                    val telegramMessageId = sendMessageAndGetId(channelId, messageText)
                    if (telegramMessageId != null) {
                        val newMatchInfo = match.copy(telegramMessageId = telegramMessageId.toString())
                        DatabaseService.updateMatchMessageId(newMatchInfo)
                    }
                }
            }
            // Добавьте задержку, чтобы не превышать лимиты API
            delay(10000)
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

        val messageText = if (totalMatches > 0) {
            // Сообщение с точностью исходов прогнозов и уточнением, что речь не о счетах
            "The accuracy of outcome predictions (not scores) in the last week is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."
        } else {
            // Сообщение об отсутствии матчей
            "No matches were played in the last week."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText

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

        val messageText = if (totalMatches > 0) {
            // Сообщение с точностью исходов прогнозов и уточнением, что речь не о счетах
            "The accuracy of outcome predictions (not scores) in the last month is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."
        } else {
            // Сообщение об отсутствии матчей
            "No matches were played in the last month."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText

        try {
            execute(message)
            logger.info("Monthly prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send monthly prediction accuracy message", e)
        }
    }

    fun sendYearlyPredictionAccuracyMessage() {
        val result = getCorrectPredictionsForPeriod(days = 365)
        val accuracy = result.first
        val correct = result.second.first
        val totalMatches = result.second.second

        val messageText = if (totalMatches > 0) {
            // Сообщение с точностью исходов прогнозов и уточнением, что речь не о счетах
            "The accuracy of outcome predictions (not scores) in the last year is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."
        } else {
            // Сообщение об отсутствии матчей
            "No matches were played in the last year."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText

        try {
            execute(message)
            logger.info("Yearly prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send yearly prediction accuracy message", e)
        }
    }
    private fun handleGetAccuracyCommand(chatId: String, messageText: String) {
        val parts = messageText.split(" ")
        if (parts.size == 2) {
            val days = parts[1].toIntOrNull()
            if (days != null && days > 0) {
                val result = getCorrectPredictionsForPeriod(days)
                val accuracy = result.first
                val correct = result.second.first
                val totalMatches = result.second.second
                val text = "The accuracy of predictions in the last $days days is ${"%.2f".format(accuracy)}% ($correct/$totalMatches)."
                sendMessage(chatId, text)
            } else {
                sendMessage(chatId, "Please provide a valid number of days.")
            }
        } else {
            sendMessage(chatId, "Usage: /getAccuracy <number_of_days>")
        }
    }

    private fun getCountryFlag(text: String): String {
        // Словарь сопоставления названий стран с эмодзи-флагами
        val countryNameToEmoji = mapOf(
            // Английские названия
            "Spain" to "🇪🇸",
            "Germany" to "🇩🇪",
            "France" to "🇫🇷",
            "Portugal" to "🇵🇹",
            "Russia" to "🇷🇺",
            "England" to "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F", // Специальный эмодзи-флаг для Англии
            "Italy" to "🇮🇹",
            "Netherlands" to "🇳🇱",
            "Ukraine" to "🇺🇦",
            "Turkey" to "🇹🇷",
            "USA" to "🇺🇸",
            "Saudi-Arabia" to "🇸🇦",
            "Saudi Arabia" to "🇸🇦", // Вариант без дефиса
            "United States" to "🇺🇸", // Дополнительный вариант для USA
            "Argentina" to "🇦🇷",
            "Brazil" to "🇧🇷",
//            "UEFA" to "🇪🇺",
            "UEFA" to "🌍",
            "CONMEBOL" to "🌎",
            "Europe" to "🌍", // Глобус с Европой и Африкой
            "Asia" to "🌏",   // Глобус с Азией и Австралией
            "Africa" to "🌍", // Можно использовать тот же глобус
            "Americas" to "🌎", // Глобус с Америкой
            "North America" to "🌎",
            "South America" to "🌎",
            "Australia" to "🌏",
            "Oceania" to "🌏",
            "Friendlies" to "\uD83C\uDFF3" //белый флаг
        )

        // Приводим текст к нижнему регистру для нечувствительного поиска
        val lowerCaseText = text.lowercase()

        // Итерация по ключам словаря и проверка наличия страны в тексте
        for ((country, emoji) in countryNameToEmoji) {
            // Приводим название страны к нижнему регистру для сравнения
            val lowerCaseCountry = country.lowercase()

            // Используем регулярное выражение с границами слова для точного поиска
            val regex = "\\b${Regex.escape(lowerCaseCountry)}\\b".toRegex()

            if (regex.containsMatchIn(lowerCaseText)) {
                return emoji
            }
        }

        // Если страна не найдена, возвращаем пустую строку или можно вернуть специальный символ, например, белый флаг
        return "" // Или " " для белого флага по умолчанию
    }

}
