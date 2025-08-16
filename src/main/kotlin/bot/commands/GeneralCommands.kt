package bot.commands

import FootballBot
import service.DatabaseService
import repository.SubscriptionType
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

class GeneralCommands(private val bot: FootballBot) {
    fun handleStart(chatId: String) {
        val description = """
                ⚽ Welcome to Football Prediction Bot!

                No one can truly predict the future - but AI can make a pretty good guess 😉
                This bot uses advanced AI models to analyze team stats, trends and bookmaker data to generate smart football match predictions.

                🧠 All predictions are AI-generated and meant for informational purposes only.
                Please use responsibly and follow your local laws regarding sports betting.

                Type /help to see what this bot can do!
            """.trimIndent()
        bot.sendMessage(chatId, description)
    }

    fun handleHelp(chatId: String, isAdmin: Boolean) {
        val commonCommands = """
            /start - Start the bot and get information about it
            /freepremiumlinks - Get available premium channel links for free
            /subscribe - Purchase bot or Premium channel subscription
            /paysupport - Request a refund
            /upcomingmatches - Get upcoming matches within the next 24 hours with analysis
            /leagueupcoming <filter> - Get upcoming matches for leagues matching the filter
            /premiummatches - Get matches selected for the Premium channel
            /recentmatches - Get matches from the last 24 hours with results
            /leaguerecent <filter> - Get matches from the last 24 hours for leagues matching the filter
            /premiumrecent - Get premium matches from the last 24 hours
            /getaccuracy <days> - Get prediction accuracy for the last <days> days
            /tasks - Schedule automatic bot commands at a convenient time
            /settimezone <HH:mm> - Set your timezone by sending your current time

            Commands /upcomingmatches, /leagueupcoming and /premiummatches together are limited to 10 uses per month for non-premium users. Premium subscribers have unlimited access.
        """.trimIndent()

        val adminCommands = """
            
            /getdatabase - Get the database file
            /usercount - Get the count of unique users
            /activeusercount - Get the count of unique users active last day
            /getStrategyEfficiency n - Get strategy efficiency for 'n' period
            /getLeaguePredictability - Get League Predictability data
            /getjsonl - Get the matches data in .jsonl format
            /uploadmodeldata - Upload matches data to the model
            /updatePastMatches - Update results of matches from the last two days
            /addPastResults league season startDate endDate - Add past results to database
            /createInviteLink subscribers days - Create an invite link for the premium channel
            /refundapprove id - Approve refund request
            /refunddecline id - Decline refund request
            /refundinfo id message - Request more info for refund
        """.trimIndent()

        val responseText = if (isAdmin) {
            "$commonCommands\n$adminCommands"
        } else {
            commonCommands
        }

        bot.sendMessage(chatId, responseText)
    }

    fun handlePremiumLinks(chatId: String) {
        val links = service.DatabaseService.invites.getActiveInviteLinksWithRemainingSlots()
        if (links.isEmpty()) {
            bot.sendMessage(chatId, "No available free premium channel joining links at the moment.")
        } else {
            val text = buildString {
                append("Available free premium channel joining links:\n")
                links.forEach { (link, left, expires) ->
                    val date = java.time.Instant.ofEpochSecond(expires)
                        .atZone(java.time.ZoneId.of("UTC"))
                        .toLocalDate()
                    append("$link - $left slots left (valid until $date)\n")
                }
            }.trim()
            bot.sendMessage(chatId, text)
        }
    }

    fun handleSubscriptionMenu(chatId: String, userId: String) {
        val now = System.currentTimeMillis() / 1000
        val botSub = DatabaseService.subscriptions.getSubscription(userId, SubscriptionType.BOT)
        val channelSub = DatabaseService.subscriptions.getSubscription(userId, SubscriptionType.CHANNEL)
        val statusLines = mutableListOf<String>()
        botSub?.takeIf { it.expiresAt > now }?.let { sub ->
            val date = java.time.Instant.ofEpochSecond(sub.expiresAt)
                .atZone(java.time.ZoneId.of("UTC"))
                .toLocalDate()
            statusLines += "Bot premium access active until $date"
        }
        channelSub?.takeIf { it.expiresAt > now }?.let { sub ->
            val date = java.time.Instant.ofEpochSecond(sub.expiresAt)
                .atZone(java.time.ZoneId.of("UTC"))
                .toLocalDate()
            statusLines += "Premium channel access active until $date"
            bot.getOrCreatePersonalLink(userId.toLong())?.let { link ->
                statusLines += "Your personal channel link: $link"
                statusLines += "Do not share it with others"
            }
        }
        val statusText = if (statusLines.isEmpty()) {
            "Choose a subscription plan:"
        } else {
            statusLines.joinToString(separator = "\n", postfix = "\n\nChoose a subscription plan:")
        }
        bot.showSubscriptionOptions(chatId, statusText)
    }

    fun handleSetTimezone(chatId: String, userId: String, messageText: String) {
        val timeStr = messageText.removePrefix("/settimezone").trim()
        if (timeStr.isBlank()) {
            bot.sendMessage(chatId, "Usage: /settimezone <HH:mm>")
            return
        }
        try {
            val userTime = LocalTime.parse(timeStr)
            val utcNow = LocalTime.now(ZoneId.of("UTC"))
            var diffMinutes = Duration.between(utcNow, userTime).toMinutes().toInt()
            if (diffMinutes < -720) diffMinutes += 1440
            if (diffMinutes > 720) diffMinutes -= 1440
            val offsetHours = (diffMinutes / 60.0).roundToInt()
            val zone = ZoneOffset.ofHours(offsetHours)
            val oldZone = DatabaseService.userSettings.getTimezone(userId) ?: "UTC"
            DatabaseService.userSettings.setTimezone(userId, zone.id)
            val label = if (zone.id.startsWith("+") || zone.id.startsWith("-")) "UTC${zone.id}" else zone.id
            bot.sendMessage(chatId, "Timezone set to $label")
            if (oldZone != zone.id) {
                val jobs = DatabaseService.jobs.getJobsByUser(userId)
                if (jobs.isNotEmpty()) {
                    val oldZoneId = ZoneId.of(oldZone)
                    val newZoneId = ZoneId.of(zone.id)
                    val nowNew = LocalDateTime.now(newZoneId)
                    jobs.forEach { job ->
                        val localTime = Instant.ofEpochSecond(job.nextRun).atZone(oldZoneId).toLocalTime()
                        var next = nowNew.with(localTime)
                        if (!next.isAfter(nowNew)) next = next.plusDays(1)
                        DatabaseService.jobs.updateNextRun(job.id, next.atZone(newZoneId).toEpochSecond())
                    }
                    bot.sendMessage(chatId, "Existing tasks moved to $label time.")
                }
            }
        } catch (e: DateTimeParseException) {
            bot.sendMessage(chatId, "Invalid time format. Use HH:mm, e.g., /settimezone 21:30")
        }
    }

}
