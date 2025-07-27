package bot.commands

import FootballBot
import service.DatabaseService
import repository.SubscriptionType

class GeneralCommands(private val bot: FootballBot) {
    fun handleStart(chatId: String) {
        val description = """
                Welcome to the Football Prediction Bot!

                No one can truly predict the future, but our Football Prediction Bot uses advanced analysis to estimate the outcomes of football matches. By leveraging in-depth analysis of team conditions, expert opinions, and bookmaker data, this bot provides insightful predictions.

                Please note that the predictions provided by this bot are for informational purposes only and are not recommendations for betting. Use the information at your own discretion and be aware of the regulations in your country regarding sports betting.

                To get a list of available commands, use /help.
            """.trimIndent()
        bot.sendMessage(chatId, description)
    }

    fun handleHelp(chatId: String, isAdmin: Boolean) {
        val commonCommands = """
            /start - Start the bot and get information about it
            /freepremiumlinks - Get available premium channel links for free
            /subscribe - Purchase bot or Premium channel subscription
            /upcomingmatches - Get upcoming matches within the next 24 hours with analysis
            /leagueupcoming <filter> - Get upcoming matches for leagues matching the filter
            /getaccuracy <days> - Get prediction accuracy for the last <days> days
            
            Commands /upcomingmatches and /leagueupcoming together are limited to 10 uses per month for non-premium users.
        """.trimIndent()

        val adminCommands = """
            /getdatabase - Get the database file
            /usercount - Get the count of unique users
            /activeusercount - Get the count of unique users active last day
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

        bot.sendMessage(chatId, responseText)
    }

    fun handlePremiumLinks(chatId: String) {
        val links = service.DatabaseService.invites.getActiveInviteLinksWithRemainingSlots()
        if (links.isEmpty()) {
            bot.sendMessage(chatId, "No available free premium channel joining links at the moment.")
        } else {
            val text = buildString {
                append("Available free premium channel joining links:\n")
                links.forEach { (link, left) ->
                    append("$link - $left slots left\n")
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
        }
        val statusText = if (statusLines.isEmpty()) {
            "Choose a subscription plan:"
        } else {
            statusLines.joinToString(separator = "\n", postfix = "\n\nChoose a subscription plan:")
        }
        bot.showSubscriptionOptions(chatId, statusText)
    }

}
