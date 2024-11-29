import dto.JsonlMatch
import dto.LeagueStats
import dto.MatchInfo
import dto.OutcomeStrategyConfig
import dto.TagsData
import dto.outcomeStrategyConfigs
import `interface`.TelegramService
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import service.DatabaseService
import service.DatabaseService.getMatchesWithoutMessageIdForNext5Hours
import service.HttpAPIFootballService
import service.initDatabase
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

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
                chatId == adminChatId && messageText == "/getLeaguePredictability" -> {
                    handleGetLeaguePredictabilityCommand(chatId)
                }
                chatId == adminChatId && messageText == "/getjsonl" -> {
                    handleGetJsonlCommand(chatId)
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

        // Add tag for the league
        leagueTags.forEach { (leagueName, tag) ->
            val regex = "\\b${Regex.escape(leagueName)}\\b".toRegex(RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(matchType)) {
                tags.add(tag)
            }
        }

        // Add tags for the teams
        teamTags.forEach { (teamName, tag) ->
            val regex = "\\b${Regex.escape(teamName)}\\b".toRegex(RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(teams)) {
                tags.add(tag)
            }
        }

        // Convert the set of tags to a string with spaces between tags
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
            /getAccuracy n - Get prediction accuracy for 'n' period
            /getLeaguePredictability - Get League Predictability data
            /getjsonl - Get the matches data in .jsonl format
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

    private fun handleGetJsonlCommand(chatId: String) {
        val matches = DatabaseService.getAllMatches()
        if (matches.isNotEmpty()) {
            val jsonlFile = createJsonlFile(matches)
            if (jsonlFile != null && jsonlFile.exists()) {
                val document = SendDocument()
                document.chatId = chatId
                document.document = InputFile(jsonlFile)
                document.caption = "Here is the .jsonl file."
                execute(document)
                jsonlFile.delete() // Delete the file after sending
            } else {
                sendMessage(chatId, "Failed to create the .jsonl file.")
            }
        } else {
            sendMessage(chatId, "No matches found.")
        }
    }

    private fun createJsonlFile(matches: List<MatchInfo>): File? {
        return try {
            val file = File("matches.jsonl")
            file.bufferedWriter().use { writer ->
                for (match in matches) {
                    val jsonLine = createJsonObjectString(match)
                    writer.write(jsonLine)
                    writer.newLine()
                }
            }
            file
        } catch (e: Exception) {
            logger.error("Failed to create .jsonl file", e)
            null
        }
    }

    private fun createJsonObjectString(match: MatchInfo): String {
        val jsonlMatch = JsonlMatch(
            date = match.datetime,
            matchType = match.matchType,
            teams = match.teams,
            predictedScore = match.predictedScore,
            actualScore = match.actualScore,
            predictedOutcome = match.predictedOutcome,
            actualOutcome = match.actualOutcome,
            odds = match.odds,
            bookmakerName = match.bookmakerName,
            homeWinOdds = match.homeWinOdds,
            drawOdds = match.drawOdds,
            awayWinOdds = match.awayWinOdds
        )
        val json = Json { prettyPrint = false }
        return json.encodeToString(jsonlMatch)
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
            Odds for the Predicted Outcome: ${matchInfo.odds} (Bookmaker: ${matchInfo.bookmakerName ?: "Default"})
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
            Odds for the Predicted Outcome: ${matchInfo.odds} (Bookmaker: ${matchInfo.bookmakerName  ?: "Default"})
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
            Odds for the Predicted Outcome: ${matchInfo.odds} (Bookmaker: ${matchInfo.bookmakerName  ?: "Default"})
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
        // Получаем список всех лиг
        val allLeagues = DatabaseService.getAllLeagues()

        val leagueStatsMap = mutableMapOf<String, LeagueStats>()

        allLeagues.forEach { leagueName ->
            // Получаем последние 150 матчей для лиги
            val matches = DatabaseService.getLastNMatchesForLeague(leagueName, 150)

            // Если матчей недостаточно, можно пропустить или всё равно посчитать статистику
            if (matches.isNotEmpty()) {
                // Рассчитываем статистику для лиги
                val stats = calculateLeagueStatsForLeague(leagueName, matches)
                leagueStatsMap[leagueName] = stats
            } else {
                logger.warn("No matches found for league $leagueName")
            }
        }

        // Сохраняем обновленные данные в базе данных
        DatabaseService.updateLeaguePredictability(leagueStatsMap)

        logger.info("League predictability updated successfully")
    }

    private fun calculateLeagueStatsForLeague(leagueName: String, matches: List<MatchInfo>): LeagueStats {
        val stats = LeagueStats(leagueName = leagueName)

        matches.forEach { match ->
            val oddsValue = match.odds?.toDoubleOrNull() ?: return@forEach
            val stake = 100.0
            val actualOutcome = match.actualOutcome
            val predictedOutcome = match.predictedOutcome

            // Общая статистика
            stats.totalMatches += 1
            stats.totalStakes += stake

            if (predictedOutcome != null && actualOutcome != null) {
                val teams = match.teams.split(" vs. ")
                if (teams.size == 2) {
                    val homeTeam = teams[0].trim()
                    val awayTeam = teams[1].trim()

                    // Определяем тип предсказания и обновляем соответствующие счетчики и ROI
                    when (predictedOutcome) {
                        homeTeam -> {
                            stats.homeWinPredictions += 1
                            stats.homeWinStakes += stake
                            if (predictedOutcome == actualOutcome) {
                                stats.homeWinSuccesses += 1
                                val profit = (oddsValue * stake) - stake
                                stats.homeWinReturns += profit
                            } else {
                                stats.homeWinReturns -= stake
                            }
                        }
                        awayTeam -> {
                            stats.awayWinPredictions += 1
                            stats.awayWinStakes += stake
                            if (predictedOutcome == actualOutcome) {
                                stats.awayWinSuccesses += 1
                                val profit = (oddsValue * stake) - stake
                                stats.awayWinReturns += profit
                            } else {
                                stats.awayWinReturns -= stake
                            }
                        }
                        "Draw" -> {
                            stats.drawPredictions += 1
                            stats.drawStakes += stake
                            if (predictedOutcome == actualOutcome) {
                                stats.drawSuccesses += 1
                                val profit = (oddsValue * stake) - stake
                                stats.drawReturns += profit
                            } else {
                                stats.drawReturns -= stake
                            }
                        }
                    }
                }

                // Обновляем общую статистику
                if (predictedOutcome == actualOutcome) {
                    stats.successfulPredictions += 1
                    val profit = (oddsValue * stake) - stake
                    stats.totalReturns += profit
                } else {
                    stats.totalReturns -= stake
                }

                // Проверяем, соответствует ли матч стратегии
//            val teams = match.teams.split(" vs. ")
//                if (teams.size == 2) {
//                    val homeTeam = teams[0].trim()
//                    val isHomeTeamPredicted = predictedOutcome == homeTeam
//                    val isOddsInRange = oddsValue in 1.20..2.20
//                    val isNotDraw = predictedOutcome != "Draw"
//
//                    if (isHomeTeamPredicted && isOddsInRange && isNotDraw) {
//                        // Статистика по стратегии
//                        stats.strategyTotalMatches += 1
//                        stats.strategyTotalStakes += stake
//
//                        if (predictedOutcome == actualOutcome) {
//                            stats.strategySuccessfulPredictions += 1
//                            val profit = (oddsValue * stake) - stake
//                            stats.strategyTotalReturns += profit
//                        } else {
//                            // Вычитаем ставку при проигрыше
//                            stats.strategyTotalReturns -= stake
//                        }
//                    }
//                }
                for (config in outcomeStrategyConfigs) {
                    // Get predictable leagues for the current outcome type
                    val predictableLeagues = DatabaseService.getPredictableLeagues(
                        outcomeType = config.outcomeType,
                        roiThreshold = config.roiThreshold,
                        accuracyThreshold = config.accuracyThreshold
                    )
                    if (isMatchFitsStrategy(match, config, predictableLeagues)) {
                        // Статистика по стратегии
                        stats.strategyTotalMatches += 1
                        stats.strategyTotalStakes += stake

                        if (predictedOutcome == actualOutcome) {
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
        }

        // Вычисляем точность и ROI для каждого типа
        stats.homeWinAccuracy = if (stats.homeWinPredictions > 0) {
            (stats.homeWinSuccesses.toDouble() / stats.homeWinPredictions) * 100
        } else 0.0

        stats.homeWinRoi = if (stats.homeWinStakes > 0) {
            (stats.homeWinReturns / stats.homeWinStakes) * 100
        } else 0.0

        stats.drawAccuracy = if (stats.drawPredictions > 0) {
            (stats.drawSuccesses.toDouble() / stats.drawPredictions) * 100
        } else 0.0

        stats.drawRoi = if (stats.drawStakes > 0) {
            (stats.drawReturns / stats.drawStakes) * 100
        } else 0.0

        stats.awayWinAccuracy = if (stats.awayWinPredictions > 0) {
            (stats.awayWinSuccesses.toDouble() / stats.awayWinPredictions) * 100
        } else 0.0

        stats.awayWinRoi = if (stats.awayWinStakes > 0) {
            (stats.awayWinReturns / stats.awayWinStakes) * 100
        } else 0.0

        // Округляем значения до двух знаков после запятой
        stats.homeWinAccuracy = (stats.homeWinAccuracy * 100).roundToInt() / 100.0
        stats.homeWinRoi = (stats.homeWinRoi * 100).roundToInt() / 100.0
        stats.drawAccuracy = (stats.drawAccuracy * 100).roundToInt() / 100.0
        stats.drawRoi = (stats.drawRoi * 100).roundToInt() / 100.0
        stats.awayWinAccuracy = (stats.awayWinAccuracy * 100).roundToInt() / 100.0
        stats.awayWinRoi = (stats.awayWinRoi * 100).roundToInt() / 100.0

        // Существующий код для расчета ROI и общей точности
        stats.roi = if (stats.totalStakes > 0) (stats.totalReturns / stats.totalStakes) * 100 else 0.0
        stats.accuracy = if (stats.totalMatches > 0) (stats.successfulPredictions.toDouble() / stats.totalMatches) * 100 else 0.0

        stats.roi = (stats.roi * 100.0).roundToInt() / 100.0
        stats.accuracy = (stats.accuracy * 100.0).roundToInt() / 100.0

        // Статистика по стратегии
        stats.strategyRoi = if (stats.strategyTotalStakes > 0) (stats.strategyTotalReturns / stats.strategyTotalStakes) * 100 else 0.0
        stats.strategyAccuracy = if (stats.strategyTotalMatches > 0) (stats.strategySuccessfulPredictions.toDouble() / stats.strategyTotalMatches) * 100 else 0.0
        stats.strategyRoi = (stats.strategyRoi * 100.0).roundToInt() / 100.0
        stats.strategyAccuracy = (stats.strategyAccuracy * 100.0).roundToInt() / 100.0

        return stats
    }

//    private fun calculateLeagueStats(matches: List<MatchInfo>, predictableLeagues: List<String>): Map<String, LeagueStats> {
//        val leagueStatsMap = mutableMapOf<String, LeagueStats>()
//
//        matches.forEach { match ->
//            val league = match.matchType
//            val oddsValue = match.odds?.toDoubleOrNull() ?: return@forEach
//            val stake = 100.0
//            val actualOutcome = match.actualOutcome
//            val predictedOutcome = match.predictedOutcome
//
//            val stats = leagueStatsMap.getOrPut(league) { LeagueStats(leagueName = league) }
//
//            if (actualOutcome != null) {
//                // Общая статистика
//                stats.totalMatches += 1
//                stats.totalStakes += stake
//
//                if (predictedOutcome == actualOutcome) {
//                    stats.successfulPredictions += 1
//                    val profit = (oddsValue * stake) - stake
//                    stats.totalReturns += profit
//                } else {
//                    // Вычитаем ставку при проигрыше
//                    stats.totalReturns -= stake
//                }
//
//                // Проверяем, соответствует ли матч стратегии
//                val teams = match.teams.split(" vs. ")
//                if (teams.size == 2) {
//                    val homeTeam = teams[0].trim()
//                    val awayTeam = teams[1].trim()
//                    val isHomeTeamPredicted = predictedOutcome == homeTeam
//                    val isAwayTeamPredicted = predictedOutcome == awayTeam
//                    val isPredictableLeague = league in predictableLeagues
//                    val isOddsInRange = oddsValue in 1.20..2.20
//                    val isNotDraw = predictedOutcome != "Draw"
//
//                    if (isHomeTeamPredicted && isOddsInRange && isNotDraw) {
//                        // Статистика по стратегии
//                        stats.strategyTotalMatches += 1
//                        stats.strategyTotalStakes += stake
//
//                        if (predictedOutcome == actualOutcome) {
//                            stats.strategySuccessfulPredictions += 1
//                            val profit = (oddsValue * stake) - stake
//                            stats.strategyTotalReturns += profit
//                        } else {
//                            // Вычитаем ставку при проигрыше
//                            stats.strategyTotalReturns -= stake
//                        }
//                    }
//                }
//            }
//        }
//
//        // Расчет метрик для каждой лиги
//        leagueStatsMap.values.forEach { stats ->
//            // Общая статистика
//            stats.roi = if (stats.totalStakes > 0) (stats.totalReturns / stats.totalStakes) * 100 else 0.0
//            stats.accuracy = if (stats.totalMatches > 0) (stats.successfulPredictions.toDouble() / stats.totalMatches) * 100 else 0.0
//
//            // Статистика по стратегии
//            stats.strategyRoi = if (stats.strategyTotalStakes > 0) (stats.strategyTotalReturns / stats.strategyTotalStakes) * 100 else 0.0
//            stats.strategyAccuracy = if (stats.strategyTotalMatches > 0) (stats.strategySuccessfulPredictions.toDouble() / stats.strategyTotalMatches) * 100 else 0.0
//        }
//
//        return leagueStatsMap
//    }


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

    // In FootballBot.kt
    suspend fun sendUpcomingMatchesToTelegram() {
        val matches = getMatchesWithoutMessageIdForNext5Hours()

        if (matches.isNotEmpty()) {
            // Process each match
            for (match in matches) {
                // Update odds if necessary
                val teamsForOdds = match.teams.split(" vs. ")
                if (teamsForOdds.size == 2) {
                    val homeTeam = teamsForOdds[0].trim()
                    val awayTeam = teamsForOdds[1].trim()
                    val oddsInfo = footballService.getOddsForFixture(
                        match.fixtureId, match.predictedOutcome ?: "", homeTeam, awayTeam
                    )
                    if (oddsInfo != null) {
                        match.odds = oddsInfo.odds.toString()
                        match.bookmakerName = oddsInfo.bookmakerName
                        match.homeWinOdds = oddsInfo.homeWinOdds?.toString()
                        match.drawOdds = oddsInfo.drawOdds?.toString()
                        match.awayWinOdds = oddsInfo.awayWinOdds?.toString()

                        DatabaseService.updateMatchOdds(match)
                    }
                }

                // Send match to main channel if not sent yet
                val messageId = match.telegramMessageId ?: run {
                    val messageText = formatMatchInfo(match)
                    val newMessageId = sendMessageAndGetId(channelId, messageText)
                    if (newMessageId != null) {
                        val updatedMatchInfo = match.copy(telegramMessageId = newMessageId.toString())
                        DatabaseService.updateMatchMessageId(updatedMatchInfo)
                    }
                    newMessageId
                }

                val fitsStrategyLeagues = DatabaseService.isLeagueFitsStrategy(strategyRoiThreshold = 10.0, strategyAccuracyThreshold = 60.0)

                if (match.matchType in fitsStrategyLeagues){
                    // Loop over each outcome strategy configuration
                    for (config in outcomeStrategyConfigs) {
                        // Get predictable leagues for the current outcome type
                        val predictableLeagues = DatabaseService.getPredictableLeagues(
                            outcomeType = config.outcomeType,
                            roiThreshold = config.roiThreshold,
                            accuracyThreshold = config.accuracyThreshold
                        )
                        // Check if match fits the strategy
                        if (isMatchFitsStrategy(match, config, predictableLeagues)) {
                            // Check if the match has already been sent to the premium channel
                            val strategyMessageId = match.strategyTelegramMessageId ?: run {
                                // Send to premium channel
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
                }

                // Delay between messages to avoid API rate limits
                delay(10000)
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

    fun updateMatchMessages(matchInfo: MatchInfo) {
        // Update message in the main channel
        if (matchInfo.telegramMessageId != null) {
            val messageText = formatMatchInfoWithResult(matchInfo)
            updateMessage(channelId, matchInfo.telegramMessageId!!, messageText)
        }
        // Update message in the strategy channel
        if (matchInfo.strategyTelegramMessageId != null) {
            val strategyMessageText = formatPremiumMatchInfoWithResult(matchInfo)
            updateMessage(strategyChannelId, matchInfo.strategyTelegramMessageId!!, strategyMessageText)
        }
    }

    fun deleteMatchMessages(matchInfo: MatchInfo) {
        // If message was sent to the main channel, delete it
        if (matchInfo.telegramMessageId != null) {
            deleteMessage(channelId, matchInfo.telegramMessageId!!)
        }
        // If message was sent to the strategy channel, delete it
        if (matchInfo.strategyTelegramMessageId != null) {
            deleteMessage(strategyChannelId, matchInfo.strategyTelegramMessageId!!)
        }
    }

    private fun deleteMessage(chatId: String, messageId: String) {
        try {
            val deleteMessage = DeleteMessage()
            deleteMessage.chatId = chatId
            deleteMessage.messageId = messageId.toInt()
            execute(deleteMessage)
            logger.info("Message with ID $messageId deleted successfully from chat $chatId")
        } catch (e: Exception) {
            logger.error("Failed to delete message with ID $messageId from chat $chatId", e)
        }
    }

    private fun handleGetLeaguePredictabilityCommand(chatId: String) {
        val leagueStatsList = DatabaseService.getLeaguePredictabilityData()
        if (leagueStatsList.isNotEmpty()) {
            val messages = formatLeaguePredictabilityData(leagueStatsList)
            messages.forEach { messageText ->
                sendMessage(chatId, messageText)
            }
        } else {
            sendMessage(chatId, "Данные о прогнозируемости лиг недоступны.")
        }
    }

    private fun formatLeaguePredictabilityData(leagueStatsList: List<LeagueStats>): List<String> {
        val messages = mutableListOf<String>()
        val messageBuilder = StringBuilder()
        messageBuilder.append("📊 **League Predictability Data**\n\n")
        leagueStatsList.sortedBy { it.leagueName }.forEach { stats ->
            val leagueInfo = """
        **${stats.leagueName}**
        - Overall Accuracy: ${stats.accuracy}%
        - ROI: ${stats.roi}%
        - Strategy Accuracy: ${stats.strategyAccuracy}%
        - Strategy ROI: ${stats.strategyRoi}%
        - Home Win Accuracy: ${stats.homeWinAccuracy}%
        - Home Win ROI: ${stats.homeWinRoi}%
        - Draw Accuracy: ${stats.drawAccuracy}%
        - Draw ROI: ${stats.drawRoi}%
        - Away Win Accuracy: ${stats.awayWinAccuracy}%
        - Away Win ROI: ${stats.awayWinRoi}%
        
    """.trimIndent()

            // Проверяем, если сообщение превышает лимит по длине, добавляем его в список и начинаем новое
            if (messageBuilder.length + leagueInfo.length > 4000) {
                messages.add(messageBuilder.toString())
                messageBuilder.clear()
            }
            messageBuilder.append(leagueInfo)
        }

        if (messageBuilder.isNotEmpty()) {
            messages.add(messageBuilder.toString())
        }

        return messages
    }

    // In FootballBot.kt
    private fun isMatchFitsStrategy(
        match: MatchInfo,
        config: OutcomeStrategyConfig,
        predictableLeagues: List<String>
    ): Boolean {
        val oddsValue = match.odds?.toDoubleOrNull() ?: 0.0
        val teams = match.teams.split(" vs. ")
        if (teams.size == 2) {
            val homeTeam = teams[0].trim()
            val awayTeam = teams[1].trim()
            val predictedOutcome = match.predictedOutcome
            val isOutcomePredicted = when (config.outcomeType) {
                "HomeWin" -> predictedOutcome == homeTeam
                "AwayWin" -> predictedOutcome == awayTeam
                "Draw" -> predictedOutcome == "Draw"
                else -> false
            }
            val isPredictableLeague = match.matchType in predictableLeagues
            val isOddsInRange = oddsValue in config.minOdds..config.maxOdds
            val isNotDefaultBookmaker = match.bookmakerName != "Default" && match.bookmakerName != null

            return isOutcomePredicted && isPredictableLeague && isOddsInRange && isNotDefaultBookmaker
        }
        return false
    }

}
