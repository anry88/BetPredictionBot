import dto.JsonlMatch
import dto.LeagueConfig
import dto.LeagueStats
import dto.MatchInfo
import dto.OutcomeStrategyConfig
import dto.TagsData
import dto.outcomeStrategyConfigs
import bot.formatter.MessageFormatter
import api.TelegramService
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
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import service.DatabaseService
import service.HttpAPIFootballService
import service.StrategyService
import service.initDatabase
import service.HttpLocalModelService
import service.ChatGPTService
import service.StarsPaymentService
import repository.SubscriptionPlan
import repository.SubscriptionType
import repository.ScheduledJob
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import bot.commands.AdminCommands
import bot.commands.GeneralCommands
import bot.invites.InviteHandler

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

    private val mainChannelFooter = "\n\n\uD83D\uDC49 @topPrediction_bot - check for more!"

    private val TELEGRAM_MESSAGE_LIMIT = 4096

    private val generalCommands = GeneralCommands(this)
    private val adminCommands = AdminCommands(this)
    private val inviteHandler = InviteHandler(this, strategyChannelId, adminChatId)
    private val paymentService = StarsPaymentService(this)

    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val leagueTags: Map<String, String>
    private val teamTags: Map<String, String>

    // Загружаем конфигурацию лиг из файла
    private val leaguesConfig: List<LeagueConfig>
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val pendingJobs = mutableMapOf<String, ScheduledJob>()
    private val jobCreationStates = mutableMapOf<String, JobCreationState>()
    private val editingJobs = mutableMapOf<String, Long>()

    private enum class JobCreationState {
        WAITING_LEAGUE_UPCOMING_FILTER,
        WAITING_LEAGUE_RECENT_FILTER,
        WAITING_ACCURACY_DAYS,
        WAITING_JOB_TIME
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
        startScheduledJobs()
    }

    private fun userTimezone(userId: String): Pair<String, String> {
        val tz = DatabaseService.userSettings.getTimezone(userId) ?: "UTC"
        val label = if (tz.startsWith("+") || tz.startsWith("-")) "UTC$tz" else tz
        return tz to label
    }

    private fun adjustMatchesTimezone(matches: List<MatchInfo>, zone: String): List<MatchInfo> {
        val zoneId = ZoneId.of(zone)
        return matches.map {
            val dt = LocalDateTime.parse(it.datetime, dateTimeFormatter)
            val converted = dt.atZone(ZoneId.of("UTC")).withZoneSameInstant(zoneId).format(dateTimeFormatter)
            it.copy(datetime = converted)
        }
    }

    override fun getBotToken(): String {
        return token
    }

    override fun getBotUsername(): String {
        return "MatchPredictionBot"
    }

    override fun sendMessageAndGetId(chatId: String, text: String): Int? {
        val messageText = if (chatId == channelId) text + mainChannelFooter else text
        val message = SendMessage()
        message.chatId = chatId
        message.text = messageText

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
            val newText = if (chatId == channelId) text + mainChannelFooter else text
            val editMessage = EditMessageText()
            editMessage.chatId = chatId
            editMessage.messageId = messageId.toInt()
            editMessage.text = newText

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

    fun sendPremiumInvoice(chatId: String, plan: SubscriptionPlan) {
        paymentService.sendPremiumInvoice(chatId, plan)
    }

    fun showSubscriptionOptions(chatId: String, text: String = "Choose subscription:") {
        val markup = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup()
        val rows = SubscriptionPlan.values().toList().chunked(2).map { chunk ->
            chunk.map { plan ->
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton(
                    "${plan.label} - ${paymentService.getPrice(plan)}⭐"
                ).apply { callbackData = plan.callbackData }
            }
        }
        markup.keyboard = rows
        val message = SendMessage(chatId, text)
        message.replyMarkup = markup
        execute(message)
    }

    private fun showJobsMenu(chatId: String) {
        val markup = InlineKeyboardMarkup()
        val rows = listOf(
            listOf(InlineKeyboardButton("Create Job").apply { callbackData = "jobs_create" }),
            listOf(InlineKeyboardButton("Edit Job").apply { callbackData = "jobs_edit" }),
            listOf(InlineKeyboardButton("Delete Job").apply { callbackData = "jobs_delete" })
        )
        markup.keyboard = rows
        val message = SendMessage(chatId, "Job management:")
        message.replyMarkup = markup
        execute(message)
    }

    private fun showCreateCategory(chatId: String) {
        val markup = InlineKeyboardMarkup()
        val rows = listOf(
            listOf(InlineKeyboardButton("Upcoming matches").apply { callbackData = "jobs_create_upcoming" }),
            listOf(InlineKeyboardButton("Recent results").apply { callbackData = "jobs_create_recent" }),
            listOf(InlineKeyboardButton("Accuracy stats").apply { callbackData = "jobs_create_accuracy" })
        )
        markup.keyboard = rows
        val message = SendMessage(chatId, "What do you want to schedule?")
        message.replyMarkup = markup
        execute(message)
    }

    private fun showUpcomingOptions(chatId: String) {
        val markup = InlineKeyboardMarkup()
        val rows = listOf(
            listOf(InlineKeyboardButton("All upcoming matches").apply { callbackData = "jobs_create_upcoming_all" }),
            listOf(InlineKeyboardButton("Upcoming matches for a league").apply { callbackData = "jobs_create_upcoming_league" }),
            listOf(InlineKeyboardButton("Premium upcoming matches").apply { callbackData = "jobs_create_upcoming_premium" })
        )
        markup.keyboard = rows
        val message = SendMessage(chatId, "Which upcoming matches to schedule:")
        message.replyMarkup = markup
        execute(message)
    }

    private fun showRecentOptions(chatId: String) {
        val markup = InlineKeyboardMarkup()
        val rows = listOf(
            listOf(InlineKeyboardButton("All recent results").apply { callbackData = "jobs_create_recent_all" }),
            listOf(InlineKeyboardButton("Recent results for a league").apply { callbackData = "jobs_create_recent_league" }),
            listOf(InlineKeyboardButton("Premium recent results").apply { callbackData = "jobs_create_recent_premium" })
        )
        markup.keyboard = rows
        val message = SendMessage(chatId, "Which recent results to schedule:")
        message.replyMarkup = markup
        execute(message)
    }

    private fun showEditJobs(chatId: String, userId: String) {
        val jobs = DatabaseService.jobs.getJobsByUser(userId)
        if (jobs.isEmpty()) {
            sendMessage(chatId, "No jobs to edit.")
            return
        }
        val markup = InlineKeyboardMarkup()
        val rows = jobs.map { job ->
            listOf(
                InlineKeyboardButton("${job.command} ${job.params ?: ""}").apply {
                    callbackData = "jobs_edit_${job.id}"
                }
            )
        }
        markup.keyboard = rows
        val message = SendMessage(chatId, "Select job to edit:")
        message.replyMarkup = markup
        execute(message)
    }

    private fun showDeleteJobs(chatId: String, userId: String) {
        val jobs = DatabaseService.jobs.getJobsByUser(userId)
        if (jobs.isEmpty()) {
            sendMessage(chatId, "No jobs to delete.")
            return
        }
        val markup = InlineKeyboardMarkup()
        val rows = jobs.map { job ->
            listOf(
                InlineKeyboardButton("${job.command} ${job.params ?: ""}").apply {
                    callbackData = "jobs_delete_${job.id}"
                }
            )
        }
        markup.keyboard = rows
        val message = SendMessage(chatId, "Select job to delete:")
        message.replyMarkup = markup
        execute(message)
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
            DatabaseService.users.addUserActivity(userId, firstName, lastName, username)

            val state = jobCreationStates[userId]
            if (state != null) {
                when (state) {
                    JobCreationState.WAITING_LEAGUE_UPCOMING_FILTER -> {
                        val jobId = editingJobs[userId]
                        handleScheduleLeagueUpcomingCommand(chatId, userId, messageText, jobId)
                    }
                    JobCreationState.WAITING_LEAGUE_RECENT_FILTER -> {
                        val jobId = editingJobs[userId]
                        handleScheduleLeagueRecentCommand(chatId, userId, messageText, jobId)
                    }
                    JobCreationState.WAITING_ACCURACY_DAYS -> {
                        val jobId = editingJobs[userId]
                        val days = messageText.toIntOrNull()
                        if (days == null || days <= 0) {
                            sendMessage(chatId, "Please provide a valid number of days.")
                        } else {
                            handleScheduleAccuracyCommand(chatId, userId, days, jobId)
                        }
                    }
                    JobCreationState.WAITING_JOB_TIME -> {
                        val job = pendingJobs[userId]
                        if (job == null) {
                            sendMessage(chatId, "No job in progress. Use /myjobs to create one.")
                        } else {
                            try {
                                val time = java.time.LocalTime.parse(messageText.trim())
                                val (zone, label) = userTimezone(userId)
                                val now = java.time.LocalDateTime.now(java.time.ZoneId.of(zone))
                                var next = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
                                if (!next.isAfter(now)) next = next.plusDays(1)
                                val epoch = next.atZone(java.time.ZoneId.of(zone)).toEpochSecond()
                                pendingJobs[userId] = job.copy(nextRun = epoch)
                                jobCreationStates.remove(userId)
                                sendMessage(chatId, "Time set to ${time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))} $label. Send /confirm to schedule or /cancel.")
                            } catch (e: Exception) {
                                sendMessage(chatId, "Invalid time format. Use HH:mm, e.g., 21:30")
                            }
                        }
                    }
                }
                return
            }

            when {
                chatId == adminChatId && messageText == "/getdatabase" -> {
                    adminCommands.handleGetDatabase(chatId)
                }

                chatId == adminChatId && messageText == "/usercount" -> {
                    adminCommands.handleUserCount(chatId)
                }

                chatId == adminChatId && messageText == "/activeusercount" -> {
                    adminCommands.handleActiveUserCount(chatId)
                }

                messageText == "/upcomingmatches" -> {
                    handleUpcomingMatchesCommand(chatId, userId)
                }

                messageText.startsWith("/leagueupcoming") -> {
                    handleUpcomingMatchesByLeagueCommand(chatId, userId, messageText)
                }

                messageText == "/premiummatches" -> {
                    handleUpcomingPremiumMatchesCommand(chatId, userId)
                }

                messageText == "/recentmatches" -> {
                    handleRecentMatchesCommand(chatId, userId)
                }

                messageText.startsWith("/leaguerecent") -> {
                    handleRecentMatchesByLeagueCommand(chatId, userId, messageText)
                }

                messageText == "/premiumrecent" -> {
                    handleRecentPremiumMatchesCommand(chatId, userId)
                }

                messageText.startsWith("/settimezone") -> {
                    generalCommands.handleSetTimezone(chatId, userId, messageText)
                }

                messageText.startsWith("/getaccuracy") -> {
                    handleGetAccuracyCommand(chatId, messageText)
                }

                messageText == "/myjobs" -> {
                    showJobsMenu(chatId)
                }

                messageText == "/confirm" -> {
                    handleConfirmJob(chatId, userId)
                }

                messageText == "/cancel" -> {
                    handleCancelJob(chatId, userId)
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
                chatId == adminChatId && messageText == "/uploadmodeldata" -> {
                    adminCommands.handleUploadModelData(chatId)
                }
                chatId == adminChatId && messageText.startsWith("/addPastResults") -> {
                    handleAddPastResultsCommand(chatId, messageText.removePrefix("/addPastResults ").trim())
                }
                messageText == "/start" -> {
                    generalCommands.handleStart(chatId)
                }

                messageText == "/help" -> {
                    generalCommands.handleHelp(chatId, chatId == adminChatId)
                }

                messageText == "/freepremiumlinks" -> {
                    generalCommands.handlePremiumLinks(chatId)
                }

                messageText == "/subscribe" -> {
                    generalCommands.handleSubscriptionMenu(chatId, userId)
                }

                messageText.startsWith("/createInviteLink") -> {
                    inviteHandler.handleCreateInviteLink(update.message)
                }

                else -> {
                    val responseText = processMessage(messageText)
                    val message = SendMessage(chatId, responseText)
                    execute(message)
                }
            }
        } else if (update.hasPreCheckoutQuery()) {
            val query = update.preCheckoutQuery
            val answer = AnswerPreCheckoutQuery()
            answer.setPreCheckoutQueryId(query.id)
            answer.setOk(true)
            execute(answer)
        } else if (update.hasCallbackQuery()) {
            val data = update.callbackQuery.data
            val chatId = update.callbackQuery.message.chatId.toString()
            val userId = update.callbackQuery.from.id.toString()
            val plan = SubscriptionPlan.values().firstOrNull { it.callbackData == data }
            when {
                plan != null -> sendPremiumInvoice(chatId, plan)
                data == "jobs_create" -> {
                    editingJobs.remove(userId)
                    showCreateCategory(chatId)
                }
                data == "jobs_edit" -> showEditJobs(chatId, userId)
                data == "jobs_delete" -> showDeleteJobs(chatId, userId)
                data == "jobs_create_upcoming" -> showUpcomingOptions(chatId)
                data == "jobs_create_recent" -> showRecentOptions(chatId)
                data == "jobs_create_accuracy" -> {
                    jobCreationStates[userId] = JobCreationState.WAITING_ACCURACY_DAYS
                    sendMessage(chatId, "Enter how many past days to calculate accuracy stats:")
                }
                data == "jobs_create_upcoming_all" -> handleScheduleUpcomingCommand(chatId, userId, editingJobs[userId])
                data == "jobs_create_upcoming_league" -> {
                    jobCreationStates[userId] = JobCreationState.WAITING_LEAGUE_UPCOMING_FILTER
                    sendMessage(chatId, "Enter league name or keyword:")
                }
                data == "jobs_create_upcoming_premium" -> handleSchedulePremiumUpcomingCommand(chatId, userId, editingJobs[userId])
                data == "jobs_create_recent_all" -> handleScheduleRecentCommand(chatId, userId, editingJobs[userId])
                data == "jobs_create_recent_league" -> {
                    jobCreationStates[userId] = JobCreationState.WAITING_LEAGUE_RECENT_FILTER
                    sendMessage(chatId, "Enter league name or keyword:")
                }
                data == "jobs_create_recent_premium" -> handleSchedulePremiumRecentCommand(chatId, userId, editingJobs[userId])
                data.startsWith("jobs_edit_") -> {
                    val id = data.removePrefix("jobs_edit_").toLongOrNull()
                    if (id != null) {
                        editingJobs[userId] = id
                        showCreateCategory(chatId)
                    }
                }
                data.startsWith("jobs_delete_") -> {
                    val id = data.removePrefix("jobs_delete_").toLongOrNull()
                    if (id != null) {
                        DatabaseService.jobs.deleteJob(id)
                        sendMessage(chatId, "Job deleted.")
                    }
                }
            }
        } else if (update.hasMessage() && update.message.hasSuccessfulPayment()) {
            val payment = update.message.successfulPayment
            val parts = payment.invoicePayload.split("_")
            val type = if (parts[0] == "bot") SubscriptionType.BOT else SubscriptionType.CHANNEL
            val months = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val userId = update.message.from.id.toString()
            val newExpiry = DatabaseService.subscriptions.addOrUpdateSubscription(userId, type, months)
            val expiryDate = java.time.Instant.ofEpochSecond(newExpiry).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
            if (type == SubscriptionType.CHANNEL) {
                val alreadyMember = inviteHandler.isUserInChannel(update.message.from.id)
                val link = inviteHandler.ensurePersonalInviteLink(update.message.from.id, newExpiry)
                if (alreadyMember) {
                    if (link == null) {
                        sendMessage(update.message.chatId.toString(), "Subscription active until $expiryDate.")
                    } else {
                        sendMessage(update.message.chatId.toString(), "Subscription active until $expiryDate. You are already in the premium channel.")
                    }
                } else {
                    if (link != null) {
                        val text = buildString {
                            append("Subscription active until $expiryDate\n")
                            append("Use this personal link to join the premium channel:\n")
                            append(link)
                            append("\nPlease do not share it with others.")
                        }
                        sendMessage(update.message.chatId.toString(), text)
                    } else {
                        sendMessage(update.message.chatId.toString(), "Subscription active until $expiryDate. Failed to create invite link, please contact the admin.")
                    }
                }
            } else {
                sendMessage(update.message.chatId.toString(), "Subscription active until $expiryDate")
            }
        } else if (update.hasChatJoinRequest()) {
            logger.info("Received chat join request: ${update.chatJoinRequest}")
            inviteHandler.handleChatJoinRequest(update.chatJoinRequest)
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




    private fun handleUpcomingMatchesCommand(chatId: String, userId: String) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        val isAdmin = userId == adminChatId || chatId == adminChatId
        if (!isPremium && !isAdmin) {
            val used = DatabaseService.commandUsage.getTotalUsage(userId)
            if (used >= 10) {
                sendMessage(chatId, "Monthly limit of 10 uses reached. Subscribe to remove the limit.")
                return
            } else {
                val total = DatabaseService.commandUsage.incrementUsage(userId, "upcomingmatches")
                val remaining = 10 - total
                sendMessage(chatId, "You have $remaining uses left this month.")
            }
        }

        val (zone, label) = userTimezone(userId)
        val upcomingMatches = DatabaseService.matches.getUpcomingMatches()
        if (upcomingMatches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(upcomingMatches, zone)
            val matchesByLeague = converted.groupBy { it.matchType }
            for ((_, matches) in matchesByLeague) {
                val messages = buildMatchMessages(matches, formatter = { formatUpcomingMatchInfo(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
            }
        } else {
            sendMessage(chatId, "No upcoming matches within the next 24 hours.")
        }
    }

    private fun handleUpcomingMatchesByLeagueCommand(chatId: String, userId: String, messageText: String) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        val isAdmin = userId == adminChatId || chatId == adminChatId
        if (!isPremium && !isAdmin) {
            val used = DatabaseService.commandUsage.getTotalUsage(userId)
            if (used >= 10) {
                sendMessage(chatId, "Monthly limit of 10 uses reached. Subscribe to remove the limit.")
                return
            } else {
                val total = DatabaseService.commandUsage.incrementUsage(userId, "leagueupcoming")
                val remaining = 10 - total
                sendMessage(chatId, "You have $remaining uses left this month.")
            }
        }

        val filter = messageText.removePrefix("/leagueupcoming").trim()
        if (filter.isBlank()) {
            sendMessage(chatId, "Usage: /leagueupcoming <filter>")
            return
        }

        val leagues = DatabaseService.matches.getAllLeagues().filter { it.contains(filter, ignoreCase = true) }
        if (leagues.isEmpty()) {
            sendMessage(chatId, "No leagues found for '$filter'.")
            return
        }

        var found = false
        val (zone, label) = userTimezone(userId)
        leagues.forEach { league ->
            val matches = DatabaseService.matches.getUpcomingMatchesForLeague(league)
            if (matches.isNotEmpty()) {
                val converted = adjustMatchesTimezone(matches, zone)
                val messages = buildMatchMessages(converted, formatter = { formatUpcomingMatchInfo(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
                found = true
            }
        }

        if (!found) {
            sendMessage(chatId, "No upcoming matches within the next 24 hours for '$filter'.")
        }
    }

    private fun handleUpcomingPremiumMatchesCommand(chatId: String, userId: String) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        val isAdmin = userId == adminChatId || chatId == adminChatId
        if (!isPremium && !isAdmin) {
            val used = DatabaseService.commandUsage.getTotalUsage(userId)
            if (used >= 10) {
                sendMessage(chatId, "Monthly limit of 10 uses reached. Subscribe to remove the limit.")
                return
            } else {
                val total = DatabaseService.commandUsage.incrementUsage(userId, "premiummatches")
                val remaining = 10 - total
                sendMessage(chatId, "You have $remaining uses left this month.")
            }
        }

        val (zone, label) = userTimezone(userId)
        val upcomingMatches = DatabaseService.matches.getUpcomingMatches()
        val premiumMatches = upcomingMatches.filter { match ->
            outcomeStrategyConfigs.any { config -> isMatchFitsStrategy(match, config) }
        }

        if (premiumMatches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(premiumMatches, zone)
            val matchesByLeague = converted.groupBy { it.matchType }
            for ((_, matches) in matchesByLeague) {
                val messages = buildMatchMessages(matches, formatter = { formatUpcomingMatchInfo(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
            }
        } else {
            sendMessage(chatId, "No upcoming matches within the next 24 hours.")
        }
    }

    private fun handleRecentMatchesCommand(chatId: String, userId: String) {
        val (zone, label) = userTimezone(userId)
        val recentMatches = DatabaseService.matches.getLastMatches(1)
        if (recentMatches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(recentMatches, zone)
            val matchesByLeague = converted.groupBy { it.matchType }
            for ((_, matches) in matchesByLeague) {
                val messages = buildMatchMessages(matches, formatter = { formatMatchInfoWithResultDetailed(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
            }
        } else {
            sendMessage(chatId, "No matches in the last 24 hours.")
        }
    }

    private fun handleRecentMatchesByLeagueCommand(chatId: String, userId: String, messageText: String) {
        val filter = messageText.removePrefix("/leaguerecent").trim()
        if (filter.isBlank()) {
            sendMessage(chatId, "Usage: /leaguerecent <filter>")
            return
        }

        val leagues = DatabaseService.matches.getAllLeagues().filter { it.contains(filter, ignoreCase = true) }
        if (leagues.isEmpty()) {
            sendMessage(chatId, "No leagues found for '$filter'.")
            return
        }

        var found = false
        val (zone, label) = userTimezone(userId)
        leagues.forEach { league ->
            val matches = DatabaseService.matches.getLastMatchesForLeague(league, 1)
            if (matches.isNotEmpty()) {
                val converted = adjustMatchesTimezone(matches, zone)
                val messages = buildMatchMessages(converted, formatter = { formatMatchInfoWithResultDetailed(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
                found = true
            }
        }

        if (!found) {
            sendMessage(chatId, "No matches in the last 24 hours for '$filter'.")
        }
    }

    private fun handleRecentPremiumMatchesCommand(chatId: String, userId: String) {
        val (zone, label) = userTimezone(userId)
        val recentMatches = DatabaseService.matches.getLastMatches(1)
        val premiumMatches = recentMatches.filter { match ->
            outcomeStrategyConfigs.any { config -> isMatchFitsStrategy(match, config) }
        }

        if (premiumMatches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(premiumMatches, zone)
            val matchesByLeague = converted.groupBy { it.matchType }
            for ((_, matches) in matchesByLeague) {
                val messages = buildMatchMessages(matches, formatter = { formatMatchInfoWithResultDetailed(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
            }
        } else {
            sendMessage(chatId, "No matches in the last 24 hours.")
        }
    }

    private fun handleGetJsonlCommand(chatId: String) {
        val matches = DatabaseService.matches.getAllMatchesForLastTwoYears()
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
        return MessageFormatter.formatMainUpcomingMatch(matchInfo, tags, isTest)
    }

    private fun formatUpcomingMatchInfo(matchInfo: MatchInfo, timezone: String): String {
        val league = leaguesConfig.find { it.description == matchInfo.matchType }
        return MessageFormatter.formatDirectUpcomingMatch(matchInfo, league, timezone)
    }

    private fun formatMatchInfoWithResult(matchInfo: MatchInfo): String {
        val tags = getTeamTags(matchInfo.teams)
        return MessageFormatter.formatMainCompletedMatch(matchInfo, tags, isTest)
    }

    private fun formatMatchInfoWithResultDetailed(matchInfo: MatchInfo, timezone: String): String {
        val league = leaguesConfig.find { it.description == matchInfo.matchType }
        return MessageFormatter.formatDirectCompletedMatch(matchInfo, league, timezone)
    }


    private fun formatLiveMatch(matchInfo: MatchInfo): String {
        val tags = getTeamTags(matchInfo.teams)
        return MessageFormatter.formatMainLiveMatch(matchInfo, tags, isTest)
    }

    private fun formatPremiumMatchInfo(matchInfo: MatchInfo): String {
        return MessageFormatter.formatPremiumUpcomingMatch(matchInfo)
    }

    private fun formatPremiumMatchInfoWithResult(matchInfo: MatchInfo): String {
        return MessageFormatter.formatPremiumCompletedMatch(matchInfo)
    }

    private fun formatLivePremiumMatch(matchInfo: MatchInfo): String {
        return MessageFormatter.formatPremiumLiveMatch(matchInfo)
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
        limit: Int = 3000,
        includeTags: Boolean = true
    ): List<Pair<String, List<MatchInfo>>> {
        if (matches.isEmpty()) return emptyList()

        val sorted = matches.sortedBy { it.datetime }
        val header = run {
            val matchType = combineLeagueName(sorted.first())
            val flag = getCountryFlag(sorted.first().matchType)
            "$matchType$flag"
        }
        val leagueTag = leagueTags.entries.firstOrNull { combineLeagueName(sorted.first()).contains(it.key, ignoreCase = true) }?.value
        val footer = if (includeTags) listOfNotNull("#Football", leagueTag).joinToString(" ") else ""

        val result = mutableListOf<Pair<String, List<MatchInfo>>>()
        var builder = StringBuilder(header)
        var current = mutableListOf<MatchInfo>()

        for (match in sorted) {
            val formatted = formatter(match)
            val potentialLength = builder.length + 2 + formatted.length

            if (potentialLength > limit && current.isNotEmpty()) {
                if (includeTags) {
                    builder.append("\n").append(footer)
                }
                result.add(builder.toString() to current.toList())
                builder = StringBuilder(header)
                current = mutableListOf()
            }

            builder.append("\n\n")
            builder.append(formatted)
            current.add(match)
        }

        if (current.isNotEmpty()) {
            if (includeTags) {
                builder.append("\n").append(footer)
            }
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

    fun sendMessage(chatId: String, text: String, parseMode: String = "Markdown") {
        val footer = if (chatId == channelId) {
            if (parseMode == "Markdown") mainChannelFooter.replace("_", "\\_") else mainChannelFooter
        } else ""
        val finalText = text + footer
        val message = SendMessage()
        message.chatId = chatId
        message.text = finalText
        message.parseMode = parseMode

        try {
            execute(message)
            logger.info("Sent message to chat $chatId")
        } catch (e: Exception) {
            logger.error("Failed to send message to chat $chatId", e)
        }
    }

    fun sendMultipartMessage(chatId: String, text: String, parseMode: String = "Markdown") {
        val footerLength = if (chatId == channelId) {
            if (parseMode == "Markdown") mainChannelFooter.replace("_", "\\_").length else mainChannelFooter.length
        } else 0
        val chunkSize = TELEGRAM_MESSAGE_LIMIT - footerLength

        var remaining = text
        while (remaining.length > chunkSize) {
            var splitIndex = remaining.lastIndexOf('\n', startIndex = chunkSize)
            if (splitIndex == -1) {
                splitIndex = remaining.lastIndexOf(' ', startIndex = chunkSize)
                if (splitIndex == -1) splitIndex = chunkSize
            }

            val chunk = remaining.substring(0, splitIndex).trimEnd()
            sendMessage(chatId, chunk, parseMode)
            remaining = remaining.substring(splitIndex).trimStart()
        }

        if (remaining.isNotEmpty()) {
            sendMessage(chatId, remaining, parseMode)
        }
    }

    fun getOrCreatePersonalLink(userId: Long): String? {
        val sub = DatabaseService.subscriptions.getSubscription(userId.toString(), SubscriptionType.CHANNEL)
            ?: return null
        return inviteHandler.ensurePersonalInviteLink(userId, sub.expiresAt)
    }

    private fun setCommands() {
        val commands = mutableListOf<BotCommand>()
        commands.add(BotCommand("/start", "Start the bot and get information about it"))
        commands.add(BotCommand("/help", "Get the list of available commands"))
        commands.add(BotCommand("/subscribe", "Purchase bot or Premium channel subscription"))
        commands.add(BotCommand("/freepremiumlinks", "Get available premium channel links for free"))
        commands.add(BotCommand("/upcomingmatches", "Get upcoming matches within the next 24 hours with analysis"))
        commands.add(BotCommand("/leagueupcoming", "Get upcoming matches for leagues matching a filter"))
        commands.add(BotCommand("/premiummatches", "Get premium matches for the next 24 hours"))
        commands.add(BotCommand("/recentmatches", "Get matches from the last 24 hours with results"))
        commands.add(BotCommand("/leaguerecent", "Get recent matches for leagues matching a filter"))
        commands.add(BotCommand("/premiumrecent", "Get premium matches from the last 24 hours"))
        commands.add(BotCommand("/getaccuracy", "Get prediction accuracy for a period"))
        commands.add(BotCommand("/myjobs", "Manage scheduled jobs"))
        commands.add(BotCommand("/settimezone", "Set your timezone by sending your current time"))

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
        val allLeagues = DatabaseService.matches.getAllLeagues()

        val leagueStatsMap = mutableMapOf<String, LeagueStats>()

        allLeagues.forEach { leagueName ->
            val matches = DatabaseService.matches.getLastMatchesForLeague(leagueName, 365)

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
        DatabaseService.matches.updateLeaguePredictability(leagueStatsMap)

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
        val leagues = DatabaseService.matches.getAllLeagues()
        val result = mutableListOf<LeagueStats>()
        leagues.forEach { league ->
            val matches = DatabaseService.matches.getLastMatchesForLeague(league, days)
            if (matches.isNotEmpty()) {
                result.add(calculateLeagueStatsForLeague(league, matches))
            }
        }
        return result
    }

    private fun formatLeagueStats(leagueStatsList: List<LeagueStats>): String {
        if (leagueStatsList.isEmpty()) return ""

        val builder = StringBuilder("\n\n")
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
        val stats = DatabaseService.matches.getStatisticsForPeriod(days = 1)
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(1))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Daily Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for the Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last 24 hours."
        }

        sendMultipartMessage(adminChatId, messageText)
        logger.info("Prediction accuracy message sent successfully")
    }

    suspend fun sendUpcomingMatchesToTelegram() {
        val matches = DatabaseService.matches.getMatchesWithoutMessageIdForNext8Hours()

        if (matches.isNotEmpty()) {
            val matchesByLeague = matches.groupBy { it.matchType }
            for ((league, leagueMatches) in matchesByLeague) {
                val leagueBatch = DatabaseService.matches.getLeagueMatchesWithoutMessageIdForNext20Hours(league).toMutableList()
                if (leagueBatch.isEmpty()) continue

                val iterator = leagueBatch.iterator()
                while (iterator.hasNext()) {
                    val match = iterator.next()
                    val info = footballService.getFixtureInfo(match.fixtureId)
                    if (info != null) {
                        when (info.statusShort) {
                            "CANC" -> {
                                DatabaseService.matches.deleteMatchByFixtureId(match.fixtureId, match.matchType)
                                iterator.remove()
                                continue
                            }
                            "PST" -> {
                                val oldDt = LocalDateTime.parse(match.datetime, dateTimeFormatter)
                                val newDt = LocalDateTime.parse(info.datetime, dateTimeFormatter)
                                val diff = kotlin.math.abs(java.time.Duration.between(oldDt, newDt).toDays())
                                if (diff <= 2) {
                                    match.datetime = info.datetime
                                    DatabaseService.matches.updateMatchDatetime(match)
                                } else {
                                    DatabaseService.matches.deleteMatchByFixtureId(match.fixtureId, match.matchType)
                                    iterator.remove()
                                    continue
                                }
                            }
                        }

                        val teamsStored = match.teams.split(" vs. ")
                        if (teamsStored.size == 2) {
                            val storedHome = teamsStored[0].trim()
                            val storedAway = teamsStored[1].trim()
                            if (storedHome != info.homeTeam || storedAway != info.awayTeam) {
                                match.teams = "${info.homeTeam} vs. ${info.awayTeam}"
                                var prediction: MatchInfo? = HttpLocalModelService.getModelPrediction(info.homeTeam, info.awayTeam, match)
                                var attempts = 0
                                while (prediction == null && attempts < 10) {
                                    attempts++
                                    try {
                                        prediction = ChatGPTService.getMatchPrediction(match)
                                    } catch (e: Exception) {
                                        logger.error("ChatGPT error on attempt #$attempts: ${'$'}{e.message}")
                                    }
                                }
                                if (prediction != null) {
                                    match.predictedOutcome = prediction.predictedOutcome
                                    match.predictedScore = prediction.predictedScore
                                    match.odds = prediction.odds
                                    match.modelHomeWinProb = prediction.modelHomeWinProb
                                    match.modelDrawProb = prediction.modelDrawProb
                                    match.modelAwayWinProb = prediction.modelAwayWinProb
                                    match.modelExpectedHomeGoals = prediction.modelExpectedHomeGoals
                                    match.modelExpectedAwayGoals = prediction.modelExpectedAwayGoals
                                    DatabaseService.matches.updateMatchPredictions(match)
                                }
                                DatabaseService.matches.updateMatchTeams(match)
                            }
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
                            DatabaseService.matches.updateMatchMessageId(updated)
                        }
                    }
                }

                val premiumMatches = leagueBatch.filter { match ->
                    outcomeStrategyConfigs.any { config -> isMatchFitsStrategy(match, config) }
                }

                if (premiumMatches.isNotEmpty()) {
                    val strategyMessages = buildMatchMessages(
                        premiumMatches,
                        formatter = { formatPremiumMatchInfo(it) },
                        includeTags = false
                    )
                    for ((text, batch) in strategyMessages) {
                        val msgId = sendMessageAndGetId(strategyChannelId, text)
                        if (msgId != null) {
                            batch.forEach { match ->
                                val updated = match.copy(strategyTelegramMessageId = msgId.toString())
                                DatabaseService.matches.updateMatchStrategyMessageId(updated)
                            }
                        }
                    }
                }

                delay(10000)
            }
        }
    }

    suspend fun updateLiveMatches() {
        val matchesToUpdate = DatabaseService.matches.getOngoingMatches()
        val updatedByMessageId = mutableMapOf<String, MutableList<MatchInfo>>()
        val updatedByStrategyId = mutableMapOf<String, MutableList<MatchInfo>>()

        for (match in matchesToUpdate) {
            val updatedMatchInfo = footballService.getLiveMatchInfo(match.fixtureId)
            if (updatedMatchInfo != null) {
                DatabaseService.matches.updateMatchResult(updatedMatchInfo)

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
            val matches = DatabaseService.matches.getMatchesByLeagueAndTelegramMessageId(league, messageId)
                .map { dbMatch ->
                    list.find { it.fixtureId == dbMatch.fixtureId } ?: dbMatch
                }
            val messageText = formatMatchesBatchForUpdate(matches)
            updateMessage(channelId, messageId, messageText)
            delay(10000)
        }

        for ((messageId, list) in updatedByStrategyId) {
            val league = list.first().matchType
            val matches = DatabaseService.matches.getMatchesByLeagueAndStrategyMessageId(league, messageId)
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
        val stats = DatabaseService.matches.getStatisticsForPeriod(days = 7)
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(7))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Weekly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for the Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last week."
        }

        sendMultipartMessage(channelId, messageText)
        logger.info("Weekly prediction accuracy message sent successfully")
    }

    fun sendMonthlyPredictionAccuracyMessage() {
        val stats = DatabaseService.matches.getStatisticsForPeriod(getDaysInLastMonth())
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(getDaysInLastMonth()))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Monthly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for the Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last month."
        }

        sendMultipartMessage(channelId, messageText)
        logger.info("Monthly prediction accuracy message sent successfully")
    }

    fun sendYearlyPredictionAccuracyMessage() {
        val stats = DatabaseService.matches.getStatisticsForPeriod(days = 365)
        val leagueText = formatLeagueStats(getLeagueStatsForPeriod(365))

        val messageText = if (stats.totalMatches > 0) {
            """
        📊 **Yearly Prediction Statistics**

        **Overall:**
        - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
        - ROI: ${"%.2f".format(stats.roi)}%
        """.trimIndent() + leagueText + """

        ✨ **Selected matches for the Premium channel:**
        - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
        - ROI: ${"%.2f".format(stats.strategyRoi)}%
        """.trimIndent()
        } else {
            "No matches were played in the last week."
        }

        sendMultipartMessage(channelId, messageText)
        logger.info("Yearly prediction accuracy message sent successfully")
    }

    private fun handleGetAccuracyCommand(chatId: String, messageText: String) {
        val parts = messageText.split(" ")
        if (parts.size == 2) {
            val days = parts[1].toIntOrNull()
            if (days != null && days > 0) {
                val stats = DatabaseService.matches.getStatisticsForPeriod(days)
                val leagueText = formatLeagueStats(getLeagueStatsForPeriod(days))
                val resultMessageText = if (stats.totalMatches > 0) {
                    """
                    📊 **Prediction Statistics for Last $days Days**

                    **Overall:**
                    - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
                    - ROI: ${"%.2f".format(stats.roi)}%
                    """.trimIndent() + leagueText + """

                    ✨ **Selected matches for the Premium channel:**
                    - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
                    - ROI: ${"%.2f".format(stats.strategyRoi)}%
                    """.trimIndent()
                } else {
                    "No matches were played in the last $days days."
                }

                sendMultipartMessage(chatId, resultMessageText)
            } else {
                sendMultipartMessage(chatId, "Please provide a valid number of days.")
            }
        } else {
            sendMultipartMessage(chatId, "Usage: /getaccuracy <number_of_days>")
        }
    }

    private fun handleGetStrategyEfficiencyCommand(chatId: String, messageText: String) {
        val parts = messageText.split(" ")
        if (parts.size == 2) {
            val days = parts[1].toIntOrNull()
            if (days != null && days > 0) {
                val stats = DatabaseService.matches.getDetailedStatisticsForPeriod(days)
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

                sendMultipartMessage(chatId, resultMessageText)
            } else {
                sendMultipartMessage(chatId, "Please provide a valid number of days.")
            }
    } else {
        sendMultipartMessage(chatId, "Usage: /getStrategyEfficiency <number_of_days>")
    }
}

    private fun requestJobTime(chatId: String, userId: String) {
        val (_, label) = userTimezone(userId)
        sendMessage(chatId, "Preview sent. Enter time in HH:mm ($label):")
        jobCreationStates[userId] = JobCreationState.WAITING_JOB_TIME
    }

    private fun handleScheduleUpcomingCommand(chatId: String, userId: String, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        sendUpcomingMatches(chatId, userId)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "upcomingmatches", null, 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleScheduleLeagueUpcomingCommand(chatId: String, userId: String, filter: String, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        val leagues = DatabaseService.matches.getAllLeagues().filter { it.contains(filter, ignoreCase = true) }
        if (leagues.isEmpty()) {
            sendMessage(chatId, "No leagues found for '$filter'.")
            return
        }
        if (leagues.size > 1) {
            val list = leagues.joinToString(separator = "\n") { "- $it" }
            sendMessage(chatId, "Multiple leagues found:\n$list\nPlease refine your filter.")
            return
        }
        val league = leagues.first()
        sendUpcomingMatchesForLeague(chatId, userId, league)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "leagueupcoming", league, 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleScheduleRecentCommand(chatId: String, userId: String, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        sendRecentMatches(chatId, userId)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "recentmatches", null, 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleScheduleAccuracyCommand(chatId: String, userId: String, days: Int, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        sendAccuracyStats(chatId, days)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "getaccuracy", days.toString(), 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleSchedulePremiumUpcomingCommand(chatId: String, userId: String, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        handleUpcomingPremiumMatchesCommand(chatId, userId)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "premiummatches", null, 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleScheduleLeagueRecentCommand(chatId: String, userId: String, filter: String, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        val leagues = DatabaseService.matches.getAllLeagues().filter { it.contains(filter, ignoreCase = true) }
        if (leagues.isEmpty()) {
            sendMessage(chatId, "No leagues found for '$filter'.")
            return
        }
        if (leagues.size > 1) {
            val list = leagues.joinToString(separator = "\n") { "- $it" }
            sendMessage(chatId, "Multiple leagues found:\n$list\nPlease refine your filter.")
            return
        }
        val league = leagues.first()
        sendRecentMatchesForLeague(chatId, userId, league)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "leaguerecent", league, 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleSchedulePremiumRecentCommand(chatId: String, userId: String, existingId: Long? = null) {
        val isPremium = DatabaseService.subscriptions.isActive(userId, SubscriptionType.BOT)
        if (!isPremium) {
            sendMessage(chatId, "This command is available for premium users only.")
            return
        }
        handleRecentPremiumMatchesCommand(chatId, userId)
        val id = existingId ?: 0
        pendingJobs[userId] = ScheduledJob(id, userId, "premiumrecent", null, 0, 24 * 60 * 60)
        requestJobTime(chatId, userId)
    }

    private fun handleConfirmJob(chatId: String, userId: String) {
        val job = pendingJobs[userId]
        if (job != null) {
            if (job.nextRun == 0L) {
                sendMessage(chatId, "Please set time for the job before confirming.")
                return
            }
            pendingJobs.remove(userId)
            if (job.id == 0L) {
                DatabaseService.jobs.addJob(job)
            } else {
                DatabaseService.jobs.updateJob(job)
            }
            editingJobs.remove(userId)
            jobCreationStates.remove(userId)
            sendMessage(chatId, "Job scheduled.")
        } else {
            sendMessage(chatId, "No pending job to confirm.")
        }
    }

    private fun handleCancelJob(chatId: String, userId: String) {
        if (pendingJobs.remove(userId) != null) {
            editingJobs.remove(userId)
            jobCreationStates.remove(userId)
            sendMessage(chatId, "Job creation cancelled.")
        } else {
            sendMessage(chatId, "No pending job to cancel.")
        }
    }

    private fun startScheduledJobs() {
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                try {
                    val jobs = DatabaseService.jobs.getDueJobs()
                    jobs.forEach { job ->
                        executeScheduledJob(job)
                        val next = job.nextRun + job.intervalSeconds
                        DatabaseService.jobs.updateNextRun(job.id, next)
                    }
                } catch (e: Exception) {
                    logger.error("Error processing scheduled jobs", e)
                }
                delay(60_000)
            }
        }
    }

    private fun executeScheduledJob(job: ScheduledJob) {
        val chatId = job.userId
        when (job.command) {
            "upcomingmatches" -> sendUpcomingMatches(chatId, job.userId)
            "leagueupcoming" -> job.params?.let { sendUpcomingMatchesForLeague(chatId, job.userId, it) }
            "recentmatches" -> sendRecentMatches(chatId, job.userId)
            "premiummatches" -> handleUpcomingPremiumMatchesCommand(chatId, job.userId)
            "leaguerecent" -> job.params?.let { sendRecentMatchesForLeague(chatId, job.userId, it) }
            "premiumrecent" -> handleRecentPremiumMatchesCommand(chatId, job.userId)
            "getaccuracy" -> job.params?.toIntOrNull()?.let { sendAccuracyStats(chatId, it) }
        }
    }

    private fun sendUpcomingMatches(chatId: String, userId: String) {
        val (zone, label) = userTimezone(userId)
        val upcomingMatches = DatabaseService.matches.getUpcomingMatches()
        if (upcomingMatches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(upcomingMatches, zone)
            val matchesByLeague = converted.groupBy { it.matchType }
            for ((_, matches) in matchesByLeague) {
                val messages = buildMatchMessages(matches, formatter = { formatUpcomingMatchInfo(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
            }
        }
    }

    private fun sendUpcomingMatchesForLeague(chatId: String, userId: String, league: String) {
        val (zone, label) = userTimezone(userId)
        val matches = DatabaseService.matches.getUpcomingMatchesForLeague(league)
        if (matches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(matches, zone)
            val messages = buildMatchMessages(converted, formatter = { formatUpcomingMatchInfo(it, label) }, includeTags = false)
            messages.forEach { (text, _) -> sendMessage(chatId, text) }
        }
    }

    private fun sendRecentMatches(chatId: String, userId: String) {
        val (zone, label) = userTimezone(userId)
        val recentMatches = DatabaseService.matches.getLastMatches(1)
        if (recentMatches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(recentMatches, zone)
            val matchesByLeague = converted.groupBy { it.matchType }
            for ((_, matches) in matchesByLeague) {
                val messages = buildMatchMessages(matches, formatter = { formatMatchInfoWithResultDetailed(it, label) }, includeTags = false)
                messages.forEach { (text, _) -> sendMessage(chatId, text) }
            }
        }
    }

    private fun sendRecentMatchesForLeague(chatId: String, userId: String, league: String) {
        val (zone, label) = userTimezone(userId)
        val matches = DatabaseService.matches.getLastMatchesForLeague(league, 1)
        if (matches.isNotEmpty()) {
            val converted = adjustMatchesTimezone(matches, zone)
            val messages = buildMatchMessages(converted, formatter = { formatMatchInfoWithResultDetailed(it, label) }, includeTags = false)
            messages.forEach { (text, _) -> sendMessage(chatId, text) }
        }
    }

    private fun sendAccuracyStats(chatId: String, days: Int) {
        val stats = DatabaseService.matches.getStatisticsForPeriod(days)
        if (stats.totalMatches > 0) {
            val leagueText = formatLeagueStats(getLeagueStatsForPeriod(days))
            val resultMessageText = """
                📊 **Prediction Statistics for Last $days Days**

                **Overall:**
                - Accuracy: ${"%.2f".format(stats.accuracy)}% (${stats.correctPredictions}/${stats.totalMatches})
                - ROI: ${"%.2f".format(stats.roi)}%
                """.trimIndent() + leagueText + """

                ✨ **Selected matches for the Premium channel:**
                - Accuracy: ${"%.2f".format(stats.strategyAccuracy)}% (${stats.strategyCorrectPredictions}/${stats.strategyTotalMatches})
                - ROI: ${"%.2f".format(stats.strategyRoi)}%
            """.trimIndent()
            sendMultipartMessage(chatId, resultMessageText)
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
            "African" to "🌍", // Можно использовать тот же глобус
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
            "Poland" to "🇵🇱",
            "Switzerland" to "🇨🇭",
            "Austria" to "🇦🇹",
            "Denmark" to "🇩🇰",
            "Norway" to "🇳🇴",
            "Sweden" to "🇸🇪",
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
            val matches = DatabaseService.matches.getMatchesByLeagueAndTelegramMessageId(matchInfo.matchType, matchInfo.telegramMessageId!!)
            val messageText = formatMatchesBatchForUpdate(matches)
            updateMessage(channelId, matchInfo.telegramMessageId!!, messageText)
        }
        // Update message in the strategy channel
        if (matchInfo.strategyTelegramMessageId != null) {
            val matches = DatabaseService.matches.getMatchesByLeagueAndStrategyMessageId(matchInfo.matchType, matchInfo.strategyTelegramMessageId!!)
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
        val leagueStatsList = DatabaseService.matches.getLeaguePredictabilityData()
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
            }.filter { match -> !DatabaseService.matches.matchExists(match) }

            if (matchInfos.isNotEmpty()) {
                DatabaseService.matches.appendRows(matchInfos)
                sendMessage(chatId, "Добавлено ${matchInfos.size} новых матчей.")
            } else {
                sendMessage(chatId, "Нет новых матчей для этого диапазона.")
            }

            delay(1000) // Задержка между запросами
            currentStart = currentEnd.plusDays(1)
        }

        sendMessage(chatId, "Сбор данных завершен.")
    }

    fun cleanupInviteLinks(channelId: String) {
        inviteHandler.cleanupInviteLinks(channelId)
    }
}
