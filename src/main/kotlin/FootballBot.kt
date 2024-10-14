import dto.LeagueStats
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
    private val strategyChannelId: String = Config.getProperty("strategy.channel.id") ?: throw IllegalStateException("Strategy Channel ChatID not found")

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
    private fun formatMatchInfoWithResult(matchInfo: MatchInfo): String{
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
            $tags #Live
        """.trimIndent()
    }

    private fun formatPremiumMatchInfo(matchInfo: MatchInfo): String {
        val flag = getCountryFlag(matchInfo.matchType)
        val matchType = if (matchInfo.matchType.split(" ")[0] != "World") matchInfo.matchType else matchInfo.matchType.replaceFirst("World", "").trimIndent()
        val tags = getTags(matchType, matchInfo.teams)

        return """
            Match Time UTC: ${matchInfo.datetime}
            Match Type: $matchType$flag
            Teams: ${matchInfo.teams}
            Predicted Outcome: ${matchInfo.predictedOutcome}
            Predicted Score: ${matchInfo.predictedScore}
            Odds for the Predicted Outcome: ${matchInfo.odds}
        """.trimIndent()
    }

    private fun formatPremiumMatchInfoWithResult(matchInfo: MatchInfo): String{
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
            Odds for the Predicted Outcome: ${matchInfo.odds}
        """.trimIndent()
    }

    private fun formatLivePremiumMatch(matchInfo: MatchInfo): String{
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
            Odds for the Predicted Outcome: ${matchInfo.odds}
            #Live
        """.trimIndent()
    }

    private fun processMessage(messageText: String): String {
        return "This is a response to: $messageText"
    }

    private fun sendMessage(chatId: String, text: String, parseMode: String = "Markdown") {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        message.parseMode = parseMode

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

    fun updateLeaguePredictability() {
        // Получаем все матчи из базы данных
        val allMatches = DatabaseService.getAllMatches()

        // Получаем список всех лиг
        val allLeagues = DatabaseService.getAllLeagues()

        // Обновляем статистику по лигам с учетом стратегии
        val leagueStatsMap = calculateLeagueStats(allMatches, allLeagues)

        // Сохраняем обновленные данные в базе данных
        DatabaseService.updateLeaguePredictability(leagueStatsMap)

        logger.info("League predictability updated successfully")
    }


    private fun calculateLeagueStats(matches: List<MatchInfo>, predictableLeagues: List<String>): Map<String, LeagueStats> {
        val leagueStatsMap = mutableMapOf<String, LeagueStats>()

        matches.forEach { match ->
            val league = match.matchType
            val oddsValue = match.odds?.toDoubleOrNull() ?: return@forEach
            val stake = 100.0
            val actualOutcome = match.actualOutcome
            val predictedOutcome = match.predictedOutcome

            val stats = leagueStatsMap.getOrPut(league) { LeagueStats(leagueName = league) }

            // Общая статистика
            stats.totalMatches += 1
            stats.totalStakes += stake

            if (actualOutcome != null && predictedOutcome == actualOutcome) {
                stats.successfulPredictions += 1
                val profit = (oddsValue * stake) - stake
                stats.totalReturns += profit
            } else {
                // Вычитаем ставку при проигрыше
                stats.totalReturns -= stake
            }

            // Проверяем, соответствует ли матч стратегии
            val teams = match.teams.split(" vs. ")
            if (teams.size == 2) {
                val homeTeam = teams[0].trim()
                val awayTeam = teams[1].trim()
                val isHomeTeamPredicted = predictedOutcome == homeTeam
                val isAwayTeamPredicted = predictedOutcome == awayTeam
                val isPredictableLeague = league in predictableLeagues
                val isOddsInRange = oddsValue in 1.20..2.20
                val isNotDraw = predictedOutcome != "Draw"

                if (isHomeTeamPredicted && isPredictableLeague && isOddsInRange && isNotDraw) {
                    // Статистика по стратегии
                    stats.strategyTotalMatches += 1
                    stats.strategyTotalStakes += stake

                    if (actualOutcome != null && predictedOutcome == actualOutcome) {
                        stats.strategySuccessfulPredictions += 1
                        val profit = (oddsValue * stake) - stake
                        stats.strategyTotalReturns += profit
                    } else {
                        // Вычитаем ставку при проигрыше
                        stats.strategyTotalReturns -= stake
                    }
                }
            }
        }

        // Расчет метрик для каждой лиги
        leagueStatsMap.values.forEach { stats ->
            // Общая статистика
            stats.roi = if (stats.totalStakes > 0) (stats.totalReturns / stats.totalStakes) * 100 else 0.0
            stats.accuracy = if (stats.totalMatches > 0) (stats.successfulPredictions.toDouble() / stats.totalMatches) * 100 else 0.0

            // Статистика по стратегии
            stats.strategyRoi = if (stats.strategyTotalStakes > 0) (stats.strategyTotalReturns / stats.strategyTotalStakes) * 100 else 0.0
            stats.strategyAccuracy = if (stats.strategyTotalMatches > 0) (stats.strategySuccessfulPredictions.toDouble() / stats.strategyTotalMatches) * 100 else 0.0
        }

        return leagueStatsMap
    }


    fun sendPredictionAccuracyMessage() {
        val stats = DatabaseService.getStatisticsForPeriod(days = 1)

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Daily Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%

        **Strategy Matches:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last 24 hours."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText
        message.enableMarkdown(true)

        try {
            execute(message)
            logger.info("Prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send prediction accuracy message", e)
        }
    }

    suspend fun sendUpcomingMatchesToTelegram() {
        val matches = getMatchesWithoutMessageIdForNext5Hours()

        if (matches.isNotEmpty()) {
            // Получаем список предсказуемых лиг
            val predictableLeagues = DatabaseService.getPredictableLeagues(strategyRoiThreshold = 10.0, strategyAccuracyThreshold = 60.0)

            matches.forEach { match ->
                // Перед отправкой сообщения обновляем коэффициенты
                val teamsForOdds = match.teams.split(" vs. ")
                if (teamsForOdds.size == 2) {
                    val homeTeam = teamsForOdds[0].trim()
                    val awayTeam = teamsForOdds[1].trim()
                    val odds = footballService.getOddsForFixture(match.fixtureId, match.predictedOutcome ?: "", homeTeam, awayTeam)
                    if (odds != null) {
                        match.odds = odds.toString()
                        DatabaseService.updateMatchOdds(match)
                    }
                }

                // Отправляем матч в основной канал, если он еще не был отправлен
                val messageId = match.telegramMessageId ?: run {
                    val messageText = formatMatchInfo(match)
                    val newMessageId = sendMessageAndGetId(channelId, messageText)
                    if (newMessageId != null) {
                        val updatedMatchInfo = match.copy(telegramMessageId = newMessageId.toString())
                        DatabaseService.updateMatchMessageId(updatedMatchInfo)
                    }
                    newMessageId
                }

                // Проверяем, соответствует ли матч стратегии
                val oddsValue = match.odds?.toDoubleOrNull() ?: 0.0
                val teams = match.teams.split(" vs. ")
                if (teams.size == 2) {
                    val homeTeam = teams[0].trim()
                    val awayTeam = teams[1].trim()
                    val isHomeTeamPredicted = match.predictedOutcome == homeTeam
                    val isAwayTeamPredicted = match.predictedOutcome == awayTeam
                    val isPredictableLeague = match.matchType in predictableLeagues
                    val isOddsInRange = oddsValue in 1.20..2.20
                    val isNotDraw = match.predictedOutcome != "Draw"

                    // Проверяем, если прогноз - победа домашней или гостевой команды, и остальные условия стратегии выполняются
                    if (isHomeTeamPredicted && isPredictableLeague && isOddsInRange && isNotDraw) {
                        // Проверяем, был ли матч уже отправлен в канал стратегии
                        val strategyMessageId = match.strategyTelegramMessageId ?: run {
                            // Отправляем в отдельный канал
                            val strategyMessageText = formatPremiumMatchInfo(match)
                            val newStrategyMessageId = sendMessageAndGetId(strategyChannelId, strategyMessageText)
                            if (newStrategyMessageId != null) {
                                val updatedMatchInfo = match.copy(strategyTelegramMessageId = newStrategyMessageId.toString())
                                DatabaseService.updateMatchStrategyMessageId(updatedMatchInfo)
                            }
                            newStrategyMessageId
                        }
                    }
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
                // Обновляем сообщение в основном канале
                val messageId = updatedMatchInfo.telegramMessageId
                if (messageId != null) {
                    updateMessage(channelId, messageId, messageText)
                }

                // Обновляем сообщение в канале стратегии
                val strategyMessageId = updatedMatchInfo.strategyTelegramMessageId
                if (strategyMessageId != null) {
                    // Выбираем форматирование в зависимости от статуса матча
                    val strategyMessageText = if (updatedMatchInfo.actualOutcome != null) {
                        // Матч завершён, используем финальное форматирование
                        formatPremiumMatchInfoWithResult(updatedMatchInfo)
                    } else {
                        // Матч ещё идёт, используем форматирование для текущих матчей
                        formatLivePremiumMatch(updatedMatchInfo)
                    }
                    updateMessage(strategyChannelId, strategyMessageId, strategyMessageText)
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
        val stats = DatabaseService.getStatisticsForPeriod(days = 7)

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Weekly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%

        **Strategy Matches:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last week."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText
        message.enableMarkdown(true)

        try {
            execute(message)
            logger.info("Weekly prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send weekly prediction accuracy message", e)
        }
    }

    fun sendMonthlyPredictionAccuracyMessage() {
        val stats = DatabaseService.getStatisticsForPeriod(getDaysInLastMonth())

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Monthly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%

        **Strategy Matches:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last month."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText
        message.enableMarkdown(true)

        try {
            execute(message)
            logger.info("Monthly prediction accuracy message sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send monthly prediction accuracy message", e)
        }
    }

    fun sendYearlyPredictionAccuracyMessage() {
        val stats = DatabaseService.getStatisticsForPeriod(days = 365)

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Yearly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%

        **Strategy Matches:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last week."
        }

        val message = SendMessage()
        message.chatId = adminChatId
        message.text = messageText
        message.enableMarkdown(true)

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
                val stats = DatabaseService.getStatisticsForPeriod(days)
                val resultMessageText = if (stats.totalMatches > 0) {
                    """
                📊 **Prediction Statistics for Last $days Days**

                **Overall:**
                - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
                - ROI: ${"%.2f".format(stats.roi)}%

                **Strategy Matches:**
                - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
                - ROI: ${"%.2f".format(stats.strategyRoi)}%
                """.trimIndent()
                } else {
                    "No matches were played in the last $days days."
                }

                sendMessage(chatId, resultMessageText)
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
