import dto.JsonlMatch
import dto.LeagueConfig
import dto.LeagueStats
import dto.MatchInfo
import dto.OutcomeStrategyConfig
import dto.TagsData
import dto.outcomeStrategyConfigs
import `interface`.TelegramService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import service.DatabaseService.getMatchesWithoutMessageIdForNext8Hours
import service.HttpAPIFootballService
import service.StrategyService
import service.initDatabase
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember
import org.telegram.telegrambots.meta.api.objects.ChatJoinRequest
import org.telegram.telegrambots.meta.api.objects.Message

class FootballBot(private val token: String) : TelegramLongPollingBot(), TelegramService {
    private val logger = LoggerFactory.getLogger(FootballBot::class.java)
    private val adminChatId =
        Config.getProperty("admin.chat.id") ?: throw IllegalStateException("Admin chat ID not found in config")
    private val channelId: String =
        Config.getProperty("channel.chat.id") ?: throw IllegalStateException("Channel ChatID not found")
    private val footballService = HttpAPIFootballService(this)
    private val strategyChannelId: String =
        Config.getProperty("strategy.channel.id") ?: throw IllegalStateException("Strategy Channel ChatID not found")
    private val isTest: Boolean = Config.getProperty("test")?.toBoolean() ?: false

    private val leagueTags: Map<String, String>
    private val teamTags: Map<String, String>

    // Загружаем конфигурацию лиг из файла
    private val leaguesConfig: List<LeagueConfig>
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun loadLeaguesConfig(): List<LeagueConfig> {
        val leaguesJson = javaClass.getResource("/leagues.json")?.readText()
            ?: throw IllegalStateException("leagues.json not found")
        return json.decodeFromString(leaguesJson)
    }

    init {
        Config.getProperty("admin.chat.id")?.let { sendMessage(it, "Bot has been started") }
        initDatabase("predictions.db") // Используем правильный путь к вашему файлу базы данных
        setCommands()
        val tags = loadTags()
        leagueTags = tags.first
        teamTags = tags.second
        leaguesConfig = loadLeaguesConfig()
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
        logger.info("Received update: ${update.updateId}")
        
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

                chatId == adminChatId && messageText.startsWith("/getAccuracy") -> {
                    handleGetAccuracyCommand(chatId, messageText)
                }

                chatId == adminChatId && messageText.startsWith("/getStrategyEfficiency") -> {
                    handleGetStrategyEfficiencyCommand(chatId, messageText)
                }

                chatId == adminChatId && messageText == "/getLeaguePredictability" -> {
                    handleGetLeaguePredictabilityCommand(chatId)
                }

                chatId == adminChatId && messageText == "/getjsonl" -> {
                    handleGetJsonlCommand(chatId)
                }
                chatId == adminChatId && messageText.startsWith("/addPastResults") -> {
                    handleAddPastResultsCommand(chatId, messageText.removePrefix("/addPastResults ").trim())
                }
                messageText == "/start" -> {
                    handleStartCommand(chatId)
                }

                messageText == "/help" -> {
                    handleHelpCommand(chatId, chatId == adminChatId)
                }

                messageText.startsWith("/createInviteLink") -> {
                    handleCreateInviteLink(update.message)
                }

                else -> {
                    val responseText = processMessage(messageText)
                    val message = SendMessage(chatId, responseText)
                    execute(message)
                }
            }
        } else if (update.hasChatJoinRequest()) {
            logger.info("Received chat join request: ${update.chatJoinRequest}")
            handleChatJoinRequest(update.chatJoinRequest)
        } else if (update.hasMyChatMember()) {
            logger.info("Received chat member update: ${update.myChatMember}")
        } else if (update.hasChatMember()) {
            logger.info("Received chat member update: ${update.chatMember}")
        }
    }

    private fun loadTags(): Pair<Map<String, String>, Map<String, String>> {
        val fileContent =
            javaClass.getResource("/tags.json")?.readText() ?: throw IllegalStateException("leagues.json not found")
        val json = Json { ignoreUnknownKeys = true }
        val tagsData = json.decodeFromString<TagsData>(fileContent)
        return Pair(tagsData.leagues, tagsData.teams)
    }

    private fun generateTag(teamName: String): String {
        // Remove special characters and spaces, keep only alphanumeric
        val cleanName = teamName.replace(Regex("[^a-zA-Z0-9]"), "")
        return "#$cleanName"
    }

    private fun getTags(matchType: String, teams: String): String {
        // Разделяем команды по " vs. "
        val splitTeams = teams.split(" vs. ")
        val homeTeam = splitTeams.getOrNull(0)?.trim()
        val awayTeam = splitTeams.getOrNull(1)?.trim()

        // 1. Ищем ровно один тег для лиги (берём первый совпавший)
        val leagueTag = leagueTags.entries.firstOrNull { (leagueName, _) ->
            // здесь можно сделать и точное, и частичное сравнение
            // если названия в leagueTags совпадают «один в один», можно использовать:
            //   matchType.equals(leagueName, ignoreCase = true)
            // а если нужно «содержится в названии лиги»:
            matchType.contains(leagueName, ignoreCase = true)
        }?.value

        // 2. Пытаемся найти ровно один тег для домашней команды
        val homeTag = homeTeam?.let { ht ->
            teamTags.entries.firstOrNull { (teamName, _) ->
                ht.equals(teamName, ignoreCase = true)
            }?.value ?: run {
                val newTag = generateTag(ht)
                // Add new tag to the map
                (teamTags as MutableMap)[ht] = newTag
                // Save updated tags to file
                saveTags()
                // Send notification about new tag
                sendMessage(adminChatId, "New team tag generated: $ht -> $newTag")
                newTag
            }
        }

        // 3. Пытаемся найти ровно один тег для гостевой команды
        val awayTag = awayTeam?.let { at ->
            teamTags.entries.firstOrNull { (teamName, _) ->
                // при строгом сравнении:
                at.equals(teamName, ignoreCase = true)
            }?.value ?: run {
                val newTag = generateTag(at)
                // Add new tag to the map
                (teamTags as MutableMap)[at] = newTag
                // Save updated tags to file
                saveTags()
                // Send notification about new tag
                sendMessage(adminChatId, "New team tag generated: $at -> $newTag")
                newTag
            }
        }

        // Собираем итоговые теги
        val tags = listOfNotNull("#Football", leagueTag, homeTag, awayTag)

        // Склеиваем в строку или возвращаем пусто, если ничего не нашли
        return tags.joinToString(" ").ifBlank { "" }
    }

    private fun getTeamTags(teams: String): String {
        val splitTeams = teams.split(" vs. ")
        val homeTeam = splitTeams.getOrNull(0)?.trim()
        val awayTeam = splitTeams.getOrNull(1)?.trim()

        val homeTag = homeTeam?.let { ht ->
            teamTags.entries.firstOrNull { (teamName, _) ->
                ht.equals(teamName, ignoreCase = true)
            }?.value ?: run {
                val newTag = generateTag(ht)
                (teamTags as MutableMap)[ht] = newTag
                saveTags()
                sendMessage(adminChatId, "New team tag generated: $ht -> $newTag")
                newTag
            }
        }

        val awayTag = awayTeam?.let { at ->
            teamTags.entries.firstOrNull { (teamName, _) ->
                at.equals(teamName, ignoreCase = true)
            }?.value ?: run {
                val newTag = generateTag(at)
                (teamTags as MutableMap)[at] = newTag
                saveTags()
                sendMessage(adminChatId, "New team tag generated: $at -> $newTag")
                newTag
            }
        }

        val tags = listOfNotNull(homeTag, awayTag)
        return tags.joinToString(" ").ifBlank { "" }
    }

    private fun saveTags() {
        try {
            val tagsData = TagsData(leagueTags, teamTags)
            val json = Json { prettyPrint = true }
            val jsonString = json.encodeToString(tagsData)
            val file = File("src/main/resources/tags.json")
            file.writeText(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to save tags", e)
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
        """.trimIndent()

        val adminCommands = """
            /getdatabase - Get the database file
            /usercount - Get the count of unique users
            /activeusercount - Get the count of unique users active last day
            /upcomingmatches - Get upcoming matches within the next 24 hours
            /getAccuracy n - Get prediction accuracy for 'n' period
            /getStrategyEfficiency n - Get strategy efficiency for 'n' period
            /getLeaguePredictability - Get League Predictability data
            /getjsonl - Get the matches data in .jsonl format
            /addPastResults league season startDate endDate - Add past results to database
            /createInviteLink subscribers days - Create an invite link for the premium channel
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

    private fun handleGetJsonlCommand(chatId: String) {
        val matches = DatabaseService.getAllMatchesForLastTwoYears()
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
        val tags = getTeamTags(matchInfo.teams)

        var testData = ""

        if (isTest) {
            testData =
                """
            Probabilities: ${if (matchInfo.modelHomeWinProb != null) "%.2f%%".format(matchInfo.modelHomeWinProb!! * 100) else "0%"} - ${if (matchInfo.modelDrawProb != null) "%.2f%%".format(matchInfo.modelDrawProb!! * 100) else "0%"} - ${if (matchInfo.modelAwayWinProb != null) "%.2f%%".format(matchInfo.modelAwayWinProb!! * 100) else "0%"}
            Expected Goals: ${if (matchInfo.modelExpectedHomeGoals != null) {"%.2f".format(matchInfo.modelExpectedHomeGoals)} else 0} : ${if (matchInfo.modelExpectedAwayGoals != null) {"%.2f".format(matchInfo.modelExpectedAwayGoals)} else 0}
            Odds: ${if (matchInfo.homeWinOdds != null) {matchInfo.homeWinOdds} else 0} - ${if (matchInfo.drawOdds != null) {matchInfo.drawOdds} else 0} - ${if (matchInfo.awayWinOdds != null) {matchInfo.awayWinOdds} else 0}"""
        }

        return """
            ${matchInfo.datetime} UTC
            ${matchInfo.teams}
            Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$testData
            $tags
        """.trimIndent()
    }

    private fun formatMatchInfoWithResult(matchInfo: MatchInfo): String {
        val isPredictionCorrect = matchInfo.predictedOutcome?.lowercase() == matchInfo.actualOutcome?.lowercase()
        val emoji = if (isPredictionCorrect) "✅" else "❌"
        val tags = getTeamTags(matchInfo.teams)

        var testData = ""

        if (isTest){
            testData =
                """
            Probabilities: ${if (matchInfo.modelHomeWinProb != null) "%.2f%%".format(matchInfo.modelHomeWinProb!! * 100) else "0%"} - ${if (matchInfo.modelDrawProb != null) "%.2f%%".format(matchInfo.modelDrawProb!! * 100) else "0%"} - ${if (matchInfo.modelAwayWinProb != null) "%.2f%%".format(matchInfo.modelAwayWinProb!! * 100) else "0%"}
            Expected Goals: ${if (matchInfo.modelExpectedHomeGoals != null) {"%.2f".format(matchInfo.modelExpectedHomeGoals)} else 0} : ${if (matchInfo.modelExpectedAwayGoals != null) {"%.2f".format(matchInfo.modelExpectedAwayGoals)} else 0}
            Odds: ${if (matchInfo.homeWinOdds != null) {matchInfo.homeWinOdds} else 0} - ${if (matchInfo.drawOdds != null) {matchInfo.drawOdds} else 0} - ${if (matchInfo.awayWinOdds != null) {matchInfo.awayWinOdds} else 0}"""
        }

        return """
            ${matchInfo.datetime} UTC
            ${matchInfo.teams}
            Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$emoji
            Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}$testData
            $tags
        """.trimIndent()
    }

    private fun formatLiveMatch(matchInfo: MatchInfo): String {
        val tags = getTeamTags(matchInfo.teams)

        var testData = ""

        if (isTest){
            testData =
                """
            Probabilities: ${if (matchInfo.modelHomeWinProb != null) "%.2f%%".format(matchInfo.modelHomeWinProb!! * 100) else "0%"} - ${if (matchInfo.modelDrawProb != null) "%.2f%%".format(matchInfo.modelDrawProb!! * 100) else "0%"} - ${if (matchInfo.modelAwayWinProb != null) "%.2f%%".format(matchInfo.modelAwayWinProb!! * 100) else "0%"}
            Expected Goals: ${if (matchInfo.modelExpectedHomeGoals != null) {"%.2f".format(matchInfo.modelExpectedHomeGoals)} else 0} : ${if (matchInfo.modelExpectedAwayGoals != null) {"%.2f".format(matchInfo.modelExpectedAwayGoals)} else 0}
            Odds: ${if (matchInfo.homeWinOdds != null) {matchInfo.homeWinOdds} else 0} - ${if (matchInfo.drawOdds != null) {matchInfo.drawOdds} else 0} - ${if (matchInfo.awayWinOdds != null) {matchInfo.awayWinOdds} else 0}"""
        }

        return """
            ${matchInfo.datetime} UTC
            ${matchInfo.teams}
            Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}
            Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'$testData
            $tags #Live
        """.trimIndent()
    }

    private fun formatPremiumMatchInfo(matchInfo: MatchInfo): String {

        var probabilityPredictedOutcome = 0.0

        val teams = matchInfo.teams.split(" vs. ")
        if (teams.size == 2) {
            val homeTeam = teams[0].trim()
            val awayTeam = teams[1].trim()
            probabilityPredictedOutcome =
                when (matchInfo.predictedOutcome) {
                    homeTeam -> {
                        (matchInfo.modelHomeWinProb!! * 100)
                    }
                    "Draw" -> {
                        (matchInfo.modelDrawProb!! * 100)
                    }
                    awayTeam -> {
                        ((matchInfo.modelAwayWinProb!! * 100))
                    }
                    else -> 0.0
                }
        }

        return """
            ${matchInfo.datetime} UTC
            ${matchInfo.teams}
            Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore} (${"%.2f".format(probabilityPredictedOutcome)}%)
            Odds: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
        """.trimIndent()
    }

    private fun formatPremiumMatchInfoWithResult(matchInfo: MatchInfo): String {
        val isPredictionCorrect = matchInfo.predictedOutcome?.lowercase() == matchInfo.actualOutcome?.lowercase()
        val emoji = if (isPredictionCorrect) "✅" else "❌"

        var probabilityPredictedOutcome = 0.0

        val teams = matchInfo.teams.split(" vs. ")
        if (teams.size == 2) {
            val homeTeam = teams[0].trim()
            val awayTeam = teams[1].trim()
            probabilityPredictedOutcome =
                when (matchInfo.predictedOutcome) {
                    homeTeam -> {
                        (matchInfo.modelHomeWinProb!! * 100)
                    }
                    "Draw" -> {
                        (matchInfo.modelDrawProb!! * 100)
                    }
                    awayTeam -> {
                        ((matchInfo.modelAwayWinProb!! * 100))
                    }
                    else -> 0.0
                }
        }

        return """
            ${matchInfo.datetime} UTC
            ${matchInfo.teams}
            Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore}$emoji (${"%.2f".format(probabilityPredictedOutcome)}%)
            Actual: ${matchInfo.actualOutcome} ${matchInfo.actualScore}
            Odds: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
        """.trimIndent()
    }

    private fun formatLivePremiumMatch(matchInfo: MatchInfo): String {

        var probabilityPredictedOutcome = 0.0

        val teams = matchInfo.teams.split(" vs. ")
        if (teams.size == 2) {
            val homeTeam = teams[0].trim()
            val awayTeam = teams[1].trim()
            probabilityPredictedOutcome =
                when (matchInfo.predictedOutcome) {
                    homeTeam -> {
                        (matchInfo.modelHomeWinProb!! * 100)
                    }
                    "Draw" -> {
                        (matchInfo.modelDrawProb!! * 100)
                    }
                    awayTeam -> {
                        ((matchInfo.modelAwayWinProb!! * 100))
                    }
                    else -> 0.0
                }
        }

        return """
            ${matchInfo.datetime} UTC
            ${matchInfo.teams}
            Prediction: ${matchInfo.predictedOutcome} ${matchInfo.predictedScore} (${"%.2f".format(probabilityPredictedOutcome)}%)
            Current: ${matchInfo.actualScore} ${matchInfo.elapsed}'
            Odds: ${matchInfo.odds} (${matchInfo.bookmakerName ?: "Default"})
            #Live
        """.trimIndent()
    }

    private fun formatMatchesBatch(matches: List<MatchInfo>, formatter: (MatchInfo) -> String): String {
        if (matches.isEmpty()) return ""
        val matchType = combineLeagueName(matches.first())
        val flag = getCountryFlag(matches.first().matchType)
        val header = "$matchType$flag"
        val body = matches.sortedBy { it.datetime }.joinToString("\n\n") { formatter(it) }
        val leagueTag = leagueTags.entries.firstOrNull { matchType.contains(it.key, ignoreCase = true) }?.value
        val footer = listOfNotNull("#Football", leagueTag).joinToString(" ")
        return "$header\n\n$body\n$footer"
    }

    private fun buildMatchMessages(
        matches: List<MatchInfo>,
        formatter: (MatchInfo) -> String,
        limit: Int = 4000
    ): List<Pair<String, List<MatchInfo>>> {
        if (matches.isEmpty()) return emptyList()

        val sorted = matches.sortedBy { it.datetime }
        val header = run {
            val matchType = combineLeagueName(sorted.first())
            val flag = getCountryFlag(sorted.first().matchType)
            "$matchType$flag"
        }
        val leagueTag = leagueTags.entries.firstOrNull { combineLeagueName(sorted.first()).contains(it.key, ignoreCase = true) }?.value
        val footer = listOfNotNull("#Football", leagueTag).joinToString(" ")

        val result = mutableListOf<Pair<String, List<MatchInfo>>>()
        var builder = StringBuilder(header)
        var current = mutableListOf<MatchInfo>()

        for (match in sorted) {
            val formatted = formatter(match)
            val potentialLength = builder.length + 2 + formatted.length

            if (potentialLength > limit && current.isNotEmpty()) {
                builder.append("\n").append(footer)
                result.add(builder.toString() to current.toList())
                builder = StringBuilder(header)
                current = mutableListOf()
            }

            builder.append("\n\n")
            builder.append(formatted)
            current.add(match)
        }

        if (current.isNotEmpty()) {
            builder.append("\n").append(footer)
            result.add(builder.toString() to current.toList())
        }

        return result
    }

    private fun formatMatchesBatchForUpdate(matches: List<MatchInfo>): String {
        return formatMatchesBatch(matches) { match ->
            when {
                match.actualOutcome != null -> formatMatchInfoWithResult(match)
                match.elapsed != null -> formatLiveMatch(match)
                else -> formatMatchInfo(match)
            }
        }
    }

    private fun formatPremiumMatchesBatch(matches: List<MatchInfo>, formatter: (MatchInfo) -> String): String {
        if (matches.isEmpty()) return ""
        val matchType = combineLeagueName(matches.first())
        val flag = getCountryFlag(matches.first().matchType)
        val header = "$matchType$flag"
        val body = matches.sortedBy { it.datetime }.joinToString("\n\n") { formatter(it) }
        return "$header\n\n$body"
    }

    private fun formatPremiumMatchesBatchForUpdate(matches: List<MatchInfo>): String {
        return formatPremiumMatchesBatch(matches) { match ->
            when {
                match.actualOutcome != null -> formatPremiumMatchInfoWithResult(match)
                match.elapsed != null -> formatLivePremiumMatch(match)
                else -> formatPremiumMatchInfo(match)
            }
        }
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
            val matches = DatabaseService.getLastMatchesForLeague(leagueName, 365)

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
            if (match.bookmakerName != "Default" && match.bookmakerName != null) {

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

                    for (config in outcomeStrategyConfigs) {

                        if (isMatchFitsStrategy(match, config)) {
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
        stats.accuracy =
            if (stats.totalMatches > 0) (stats.successfulPredictions.toDouble() / stats.totalMatches) * 100 else 0.0

        stats.roi = (stats.roi * 100.0).roundToInt() / 100.0
        stats.accuracy = (stats.accuracy * 100.0).roundToInt() / 100.0

        // Статистика по стратегии
        stats.strategyRoi =
            if (stats.strategyTotalStakes > 0) (stats.strategyTotalReturns / stats.strategyTotalStakes) * 100 else 0.0
        stats.strategyAccuracy =
            if (stats.strategyTotalMatches > 0) (stats.strategySuccessfulPredictions.toDouble() / stats.strategyTotalMatches) * 100 else 0.0
        stats.strategyRoi = (stats.strategyRoi * 100.0).roundToInt() / 100.0
        stats.strategyAccuracy = (stats.strategyAccuracy * 100.0).roundToInt() / 100.0

        return stats
    }

    private fun getLeagueStatsForPeriod(days: Int): List<LeagueStats> {
        val leagues = DatabaseService.getAllLeagues()
        val result = mutableListOf<LeagueStats>()
        leagues.forEach { league ->
            val matches = DatabaseService.getLastMatchesForLeague(league, days)
            if (matches.isNotEmpty()) {
                result.add(calculateLeagueStatsForLeague(league, matches))
            }
        }
        return result
    }

    private fun formatLeagueStats(leagueStatsList: List<LeagueStats>): String {
        if (leagueStatsList.isEmpty()) return ""

        val builder = StringBuilder("\n")
        leagueStatsList.sortedBy { it.leagueName }.forEachIndexed { index, stats ->
            val flag = getCountryFlag(stats.leagueName)
            builder.append("$flag **${stats.leagueName}**\n")
            builder.append("- Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.successfulPredictions}/${stats.totalMatches})\n")
            builder.append("- ROI: ${"%.2f".format(stats.roi)}%\n")
            if (index != leagueStatsList.lastIndex) builder.append("\n")
        }
        return builder.toString()
    }

    fun sendPredictionAccuracyMessage() {
        val stats = DatabaseService.getStatisticsForPeriod(days = 1)
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(1))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Daily Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for Premium channel:**
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
        val matches = getMatchesWithoutMessageIdForNext8Hours()

        if (matches.isNotEmpty()) {
            val matchesByLeague = matches.groupBy { it.matchType }
            for ((league, leagueMatches) in matchesByLeague) {
                val leagueBatch = DatabaseService.getLeagueMatchesWithoutMessageIdForNext20Hours(league)
                if (leagueBatch.isEmpty()) continue

                for (match in leagueBatch) {
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
                }

                val leagueMessages = buildMatchMessages(
                    leagueBatch,
                    formatter = { formatMatchInfo(it) }
                )
                for ((text, batch) in leagueMessages) {
                    val msgId = sendMessageAndGetId(channelId, text)
                    if (msgId != null) {
                        batch.forEach { match ->
                            val updated = match.copy(telegramMessageId = msgId.toString())
                            DatabaseService.updateMatchMessageId(updated)
                        }
                    }
                }

                val premiumMatches = leagueBatch.filter { match ->
                    outcomeStrategyConfigs.any { config -> isMatchFitsStrategy(match, config) }
                }

                if (premiumMatches.isNotEmpty()) {
                    val strategyMessages = buildMatchMessages(
                        premiumMatches,
                        formatter = { formatPremiumMatchInfo(it) }
                    )
                    for ((text, batch) in strategyMessages) {
                        val msgId = sendMessageAndGetId(strategyChannelId, text)
                        if (msgId != null) {
                            batch.forEach { match ->
                                val updated = match.copy(strategyTelegramMessageId = msgId.toString())
                                DatabaseService.updateMatchStrategyMessageId(updated)
                            }
                        }
                    }
                }

                delay(10000)
            }
        }
    }

    suspend fun updateLiveMatches() {
        val matchesToUpdate = DatabaseService.getOngoingMatches()
        val updatedByMessageId = mutableMapOf<String, MutableList<MatchInfo>>()
        val updatedByStrategyId = mutableMapOf<String, MutableList<MatchInfo>>()

        for (match in matchesToUpdate) {
            val updatedMatchInfo = footballService.getLiveMatchInfo(match.fixtureId)
            if (updatedMatchInfo != null) {
                DatabaseService.updateMatchResult(updatedMatchInfo)

                updatedMatchInfo.telegramMessageId?.let { id ->
                    updatedByMessageId.getOrPut(id) { mutableListOf() }.add(updatedMatchInfo)
                }

                updatedMatchInfo.strategyTelegramMessageId?.let { id ->
                    updatedByStrategyId.getOrPut(id) { mutableListOf() }.add(updatedMatchInfo)
                }
            }
            delay(1000)
        }

        for ((messageId, list) in updatedByMessageId) {
            val league = list.first().matchType
            val matches = DatabaseService.getMatchesByLeagueAndTelegramMessageId(league, messageId)
                .map { dbMatch ->
                    list.find { it.fixtureId == dbMatch.fixtureId } ?: dbMatch
                }
            val messageText = formatMatchesBatchForUpdate(matches)
            updateMessage(channelId, messageId, messageText)
            delay(10000)
        }

        for ((messageId, list) in updatedByStrategyId) {
            val league = list.first().matchType
            val matches = DatabaseService.getMatchesByLeagueAndStrategyMessageId(league, messageId)
                .map { dbMatch ->
                    list.find { it.fixtureId == dbMatch.fixtureId } ?: dbMatch
                }
            val messageText = formatPremiumMatchesBatchForUpdate(matches)
            updateMessage(strategyChannelId, messageId, messageText)
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
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(7))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Weekly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last week."
        }

        val message = SendMessage()
        message.chatId = channelId
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
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(getDaysInLastMonth()))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Monthly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last month."
        }

        val message = SendMessage()
        message.chatId = channelId
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
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(365))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Yearly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last week."
        }

        val message = SendMessage()
        message.chatId = channelId
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
                val leagueText = formatLeagueStats(getLeagueStatsForPeriod(days))
                val resultMessageText = if (stats.totalMatches > 0) {
                    """
                    📊 **Prediction Statistics for Last $days Days**

                    **Overall:**
                    - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
                    - ROI: ${"%.2f".format(stats.roi)}%
                    """.trimIndent() + leagueText + """

                    ✨ **Selected matches for Premium channel:**
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

    private fun handleGetStrategyEfficiencyCommand(chatId: String, messageText: String) {
        val parts = messageText.split(" ")
        if (parts.size == 2) {
            val days = parts[1].toIntOrNull()
            if (days != null && days > 0) {
                val stats = DatabaseService.getDetailedStatisticsForPeriod(days)
                val resultMessageText = if (stats.totalMatches > 0) {
                    """
                    📊 **Strategy Efficiency for Last $days Days**

                    **Overall Statistics:**
                    - Total Matches: ${stats.totalMatches}
                    - Correct Predictions: ${stats.correctPredictions}
                    - Accuracy: ${"%.2f".format(stats.accuracy)}%
                    - ROI: ${"%.2f".format(stats.roi)}%

                    **Strategy Statistics:**
                    - Total Matches: ${stats.strategyTotalMatches}
                    - Correct Predictions: ${stats.strategyCorrectPredictions}
                    - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}%
                    - ROI: ${"%.2f".format(stats.strategyRoi)}%

                    **By Outcome Type:**
                    - Home Win: ${"%.2f".format(stats.homeWinAccuracy)}% (${stats.homeWinSuccesses}/${stats.homeWinPredictions})
                    - Draw: ${"%.2f".format(stats.drawAccuracy)}% (${stats.drawSuccesses}/${stats.drawPredictions})
                    - Away Win: ${"%.2f".format(stats.awayWinAccuracy)}% (${stats.awayWinSuccesses}/${stats.awayWinPredictions})

                    **ROI by Outcome Type:**
                    - Home Win: ${"%.2f".format(stats.homeWinRoi)}%
                    - Draw: ${"%.2f".format(stats.drawRoi)}%
                    - Away Win: ${"%.2f".format(stats.awayWinRoi)}%
                    """.trimIndent()
                } else {
                    "No matches were played in the last $days days."
                }

                sendMessage(chatId, resultMessageText)
            } else {
                sendMessage(chatId, "Please provide a valid number of days.")
            }
        } else {
            sendMessage(chatId, "Usage: /getStrategyEfficiency <number_of_days>")
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
            "Greece" to "🇬🇷",
            "Belgium" to "🇧🇪",
            "Scotland" to "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F",
            "Czech-Republic" to "🇨🇿",
            "United-Arab-Emirates" to "🇦🇪",
            "Mexico" to "🇲🇽",
            "Qatar" to "🇶🇦",
            "China" to "🇨🇳",
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
            "Friendlies" to "\uD83C\uDFF3", //белый флаг
            "Asian" to "🌏",
            "Uruguay" to "🇺🇾",
            "Chile" to "🇨🇱",
            "Bolivia" to "🇧🇴",
            "South-Korea" to "🇰🇷",
            "Japan" to "🇯🇵",
            "FIFA" to "🌎",
            "Colombia" to "🇨🇴",
            "Venezuela" to "🇻🇪",
            "Ecuador" to "🇪🇨",
            "Peru" to "🇵🇪",
            "Paraguay" to "🇵🇾",
            "Iran" to "🇮🇷",
            "India" to "🇮🇳",
            "Indonesia" to "🇮🇩",
            "Poland" to "🇵🇱"
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

        return ""
    }

    fun updateMatchMessages(matchInfo: MatchInfo) {
        // Update message in the main channel
        if (matchInfo.telegramMessageId != null) {
            val matches = DatabaseService.getMatchesByLeagueAndTelegramMessageId(matchInfo.matchType, matchInfo.telegramMessageId!!)
            val messageText = formatMatchesBatchForUpdate(matches)
            updateMessage(channelId, matchInfo.telegramMessageId!!, messageText)
        }
        // Update message in the strategy channel
        if (matchInfo.strategyTelegramMessageId != null) {
            val matches = DatabaseService.getMatchesByLeagueAndStrategyMessageId(matchInfo.matchType, matchInfo.strategyTelegramMessageId!!)
            val strategyMessageText = formatPremiumMatchesBatchForUpdate(matches)
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

    private fun isMatchFitsStrategy(match: MatchInfo, config: OutcomeStrategyConfig): Boolean {
        return StrategyService.isMatchFitsStrategy(match, config)
    }

    private fun combineLeagueName(matchInfo: MatchInfo): String {

        return if (matchInfo.matchType.split(" ")[0] != "World") matchInfo.matchType else matchInfo.matchType.replaceFirst("World", "").trimIndent()
    }

    private fun handleAddPastResultsCommand(chatId: String, messageText: String) {
        val parts = messageText.split(" ")
        if (parts.size != 4) {
            sendMessage(chatId, "Неверный формат команды. Используйте: <leagueId> <season> <start_date> <end_date>")
            return
        }

        val leagueId = parts[0].toIntOrNull()
        val season = parts[1].toIntOrNull()
        val startDate = LocalDate.parse(parts[2])
        val endDate = LocalDate.parse(parts[3])

        if (leagueId == null || season == null) {
            sendMessage(chatId, "Ошибка парсинга leagueId или season.")
            return
        }

        sendMessage(chatId, "Начинаем загрузку матчей для лиги $leagueId, сезона $season с $startDate по $endDate...")
        CoroutineScope(Dispatchers.IO).launch {
            processMatchResults(leagueId, season, startDate, endDate, chatId)
        }
    }

    private suspend fun processMatchResults(leagueId: Int, season: Int, startDate: LocalDate, endDate: LocalDate, chatId: String) {
        var currentStart = startDate

        while (currentStart.isBefore(endDate) || currentStart.isEqual(endDate)) {
            val currentEnd = currentStart.plusDays(9).coerceAtMost(endDate)

            sendMessage(chatId, "Запрашиваем матчи с $currentStart по $currentEnd...")

            val matches = footballService.getPastMatches(leagueId, season, currentStart.toString(), currentEnd.toString())

            val matchInfos = matches.map { match ->

                val isoDateTime = match.fixture.date // Оригинальная дата и время в ISO формате
                val parsedDateTime = OffsetDateTime.parse(isoDateTime) // Парсим ISO строку

                // Приводим к нужному формату
                val formatterMatchDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val datetime = parsedDateTime.format(formatterMatchDate) // Форматируем дату и время

                val homeTeam = match.teams.home.name
                val awayTeam = match.teams.away.name

                val homeGoals = match.score?.fulltime?.home ?: 0
                val awayGoals = match.score?.fulltime?.away ?: 0

                // Определяем победителя
                val winner = when {
                    homeGoals > awayGoals -> homeTeam
                    awayGoals > homeGoals -> awayTeam
                    else -> "Draw"
                }

                MatchInfo(
                    fixtureId = match.fixture.id.toString(),
                    datetime = datetime,
                    matchType = "${match.league.country} ${match.league.name}",
                    teams = "$homeTeam vs. $awayTeam",
                    predictedOutcome = null,
                    actualOutcome = winner,
                    predictedScore = null,
                    actualScore = "$homeGoals:$awayGoals",
                    odds = null,
                    telegramMessageId = null,
                    strategyTelegramMessageId = null,
                    elapsed = null,
                    bookmakerName = null,
                    homeWinOdds = null,
                    drawOdds = null,
                    awayWinOdds = null,
                    modelHomeWinProb = null,
                    modelDrawProb = null,
                    modelAwayWinProb = null,
                    modelExpectedHomeGoals = null,
                    modelExpectedAwayGoals = null
                )
            }.filter { match -> !DatabaseService.matchExists(match) }

            if (matchInfos.isNotEmpty()) {
                DatabaseService.appendRows(matchInfos)
                sendMessage(chatId, "Добавлено ${matchInfos.size} новых матчей.")
            } else {
                sendMessage(chatId, "Нет новых матчей для этого диапазона.")
            }

            delay(1000) // Задержка между запросами
            currentStart = currentEnd.plusDays(1)
        }

        sendMessage(chatId, "Сбор данных завершен.")
    }

    private fun handleCreateInviteLink(message: Message) {
        if (message.chatId != adminChatId.toLong()) {
            sendMessage(message.chatId.toString(), "This command is only available in the admin chat")
            return
        }

        val args = message.text.split(" ")
        if (args.size != 3) {
            val usageMessage = """
                Usage: /createInviteLink <subscribers_count> <days>
                
                Example: /createInviteLink 10 7
                
                Parameters:
                - subscribers_count: maximum number of users who can join using this link
                - days: link validity period in days
            """.trimIndent()
            sendMessage(message.chatId.toString(), usageMessage)
            return
        }

        try {
            val maxSubscribers = args[1].toInt()
            val days = args[2].toInt()

            if (maxSubscribers <= 0 || days <= 0) {
                sendMessage(message.chatId.toString(), "Subscribers count and days must be positive numbers")
                return
            }

            // Create invite link through Telegram API
            val createChatInviteLink = CreateChatInviteLink()
            createChatInviteLink.chatId = strategyChannelId
            createChatInviteLink.expireDate = (System.currentTimeMillis() / 1000 + days * 24 * 60 * 60).toInt()
            createChatInviteLink.createsJoinRequest = true

            val inviteLink = execute(createChatInviteLink)

            // Create database record
            val inviteLinkId = DatabaseService.createInviteLink(inviteLink.inviteLink, maxSubscribers, days)
            if (inviteLinkId > 0) {
                val response = """
                    <b>New premium channel invite link created</b>
                    <b>Link ID:</b> $inviteLinkId
                    <b>Link:</b> ${inviteLink.inviteLink}
                    <b>Valid for:</b> $days days
                    <b>Max subscribers:</b> $maxSubscribers
                    
                    <i>Users must send a join request and specify the link ID.</i>
                """.trimIndent()
                
                sendMessage(message.chatId.toString(), response, "HTML")
            } else {
                sendMessage(message.chatId.toString(), "Error creating link in database")
            }
        } catch (e: NumberFormatException) {
            sendMessage(message.chatId.toString(), "Invalid number format. Please use positive integers.")
        } catch (e: Exception) {
            logger.error("Error creating invite link", e)
            sendMessage(message.chatId.toString(), "An error occurred while creating the link")
        }
    }

    private fun handleChatJoinRequest(chatJoinRequest: ChatJoinRequest) {
        try {
            val chatId = chatJoinRequest.chat.id.toString()
            if (chatId == strategyChannelId) {
                logger.info("Processing join request for strategy channel")
                
                // Get link from request
                val inviteLink = chatJoinRequest.inviteLink?.inviteLink
                
                if (inviteLink != null) {
                    // Get link ID from database
                    val inviteLinkId = DatabaseService.getInviteLinkId(inviteLink)
                    
                    if (inviteLinkId != null) {
                        // Check if subscriber limit is reached
                        val subscriberCount = DatabaseService.getSubscriberCount(inviteLinkId)
                        val maxSubscribers = DatabaseService.getMaxSubscribersForLink(inviteLinkId)
                        
                        if (subscriberCount >= maxSubscribers) {
                            sendMessage(chatJoinRequest.user.id.toString(), "Sorry, the subscriber limit for this link has been reached.")
                            return
                        }
                        
                        // Save join request
                        val success = DatabaseService.addJoinRequest(
                            inviteLinkId,
                            chatJoinRequest.user.id.toString(),
                            chatJoinRequest.user.userName,
                            chatJoinRequest.user.firstName,
                            chatJoinRequest.user.lastName
                        )

                        if (success) {
                            // Automatically approve request
                            val approved = DatabaseService.approveJoinRequest(inviteLinkId, chatJoinRequest.user.id.toString())
                            
                            if (approved) {
                                // Approve join request through Telegram API
                                val approveChatJoinRequest = ApproveChatJoinRequest()
                                approveChatJoinRequest.chatId = strategyChannelId
                                approveChatJoinRequest.userId = chatJoinRequest.user.id
                                execute(approveChatJoinRequest)

                                // Send notification to admin chat
                                val notification = """
                                    <b>New user joined the premium channel</b>
                                    <b>User:</b> ${chatJoinRequest.user.firstName} ${chatJoinRequest.user.lastName ?: ""} (@${chatJoinRequest.user.userName ?: "no username"})
                                    <b>ID:</b> ${chatJoinRequest.user.id}
                                    <b>Link ID:</b> $inviteLinkId
                                    <b>Current subscribers:</b> ${subscriberCount + 1}/$maxSubscribers
                                """.trimIndent()
                                sendMessage(adminChatId, notification, "HTML")
                                
                                // Send message to user
                                sendMessage(chatJoinRequest.user.id.toString(), "Your request to join the premium channel has been approved!")
                            } else {
                                sendMessage(chatJoinRequest.user.id.toString(), "An error occurred while processing your request. Please try again later.")
                            }
                        }
                    } else {
                        sendMessage(adminChatId, "Someone's trying to join with link that created by not my bot.")
                    }
                } else {
                    sendMessage(chatJoinRequest.user.id.toString(), "Invalid invite link.")
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling chat join request", e)
        }
    }

    fun cleanupInviteLinks(channelId: String) {
        try {
            // Get list of expired subscribers
            val expiredSubscribers = DatabaseService.cleanupExpiredSubscribers()
            
            for (subscriber in expiredSubscribers) {
                try {
                    // Remove user from channel
                    CoroutineScope(Dispatchers.IO).launch {
                        removeUserFromChannel(subscriber.userId.toLong(), channelId.toLong())
                    }
                    
                    // Send notification to admin chat
                    val notification = """
                        <b>User removed from premium channel</b>
                        <b>Reason:</b> Invite link expired
                        <b>User:</b> ${subscriber.firstName} ${subscriber.lastName ?: ""} (@${subscriber.username ?: "no username"})
                        <b>ID:</b> ${subscriber.userId}
                        <b>Link:</b> ${subscriber.inviteLink}
                    """.trimIndent()
                    sendMessage(adminChatId, notification, "HTML")
                    
                    // Send message to user
                    sendMessage(subscriber.userId, "Your access to the premium channel has expired. Thank you for using our service!")
                } catch (e: Exception) {
                    logger.error("Error removing user ${subscriber.userId} from channel", e)
                }
            }

            // Update status of expired links in database
            val expiredLinks = DatabaseService.getExpiredInviteLinks()
            for (link in expiredLinks) {
                DatabaseService.deactivateInviteLink(link.id)
                logger.info("Deactivated expired invite link ID: ${link.id}")
            }
        } catch (e: Exception) {
            logger.error("Error in cleanupInviteLinks", e)
        }
    }

    private suspend fun removeUserFromChannel(userId: Long, channelId: Long) {
        try {
            // Remove user from channel by banning
            val banChatMember = BanChatMember()
            banChatMember.chatId = channelId.toString()
            banChatMember.userId = userId
            execute(banChatMember)
            
            // Add delay to ensure user is removed from channel
            delay(2000) // 2 seconds delay
            
            // Unban user
            val unbanChatMember = UnbanChatMember()
            unbanChatMember.chatId = channelId.toString()
            unbanChatMember.userId = userId
            execute(unbanChatMember)
            
            // Update database
            DatabaseService.removeUserFromChannel(userId, channelId)
            
            logger.info("Successfully removed user $userId from channel $channelId")
        } catch (e: Exception) {
            logger.error("Failed to remove user $userId from channel $channelId", e)
        }
    }
}
