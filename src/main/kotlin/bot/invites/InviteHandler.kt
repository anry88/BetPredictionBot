package bot.invites

import FootballBot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberLeft
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned
import org.telegram.telegrambots.meta.api.objects.ChatJoinRequest
import org.telegram.telegrambots.meta.api.objects.Message
import service.DatabaseService

class InviteHandler(private val bot: FootballBot,
                    private val strategyChannelId: String,
                    private val adminChatId: String) {

    private val logger = LoggerFactory.getLogger(InviteHandler::class.java)

    private fun createPersonalInviteLink(userId: Long, expiresAt: Long): String? {
        return try {
            val createChatInviteLink = CreateChatInviteLink()
            createChatInviteLink.chatId = strategyChannelId
            createChatInviteLink.expireDate = expiresAt.toInt()
            createChatInviteLink.createsJoinRequest = true

            val inviteLink = bot.execute(createChatInviteLink)
            DatabaseService.invites.createInviteLink(inviteLink.inviteLink, 1, expiresAt, userId.toString())
            inviteLink.inviteLink
        } catch (e: Exception) {
            logger.error("Error creating personal invite link", e)
            null
        }
    }

    fun ensurePersonalInviteLink(userId: Long, expiresAt: Long): String? {
        val existing = DatabaseService.invites.getLatestLinkForUser(userId.toString())
        return if (existing != null) {
            if (existing.expiresAt < expiresAt) {
                DatabaseService.invites.updateInviteLinkExpiry(existing.id.toLong(), expiresAt)
            }
            existing.inviteLink
        } else {
            createPersonalInviteLink(userId, expiresAt)
        }
    }

    fun isUserInChannel(userId: Long): Boolean {
        return try {
            val getChatMember = GetChatMember()
            getChatMember.chatId = strategyChannelId
            getChatMember.userId = userId
            val member = bot.execute(getChatMember)
            when (member) {
                is ChatMemberLeft, is ChatMemberBanned -> false
                else -> true
            }
        } catch (e: Exception) {
            logger.error("Error checking membership for user $userId", e)
            false
        }
    }

    fun handleCreateInviteLink(message: Message) {
        if (message.chatId != adminChatId.toLong()) {
            bot.sendMessage(message.chatId.toString(), "This command is only available in the admin chat")
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
            bot.sendMessage(message.chatId.toString(), usageMessage)
            return
        }

        try {
            val maxSubscribers = args[1].toInt()
            val days = args[2].toInt()

            if (maxSubscribers <= 0 || days <= 0) {
                bot.sendMessage(message.chatId.toString(), "Subscribers count and days must be positive numbers")
                return
            }

            val now = java.time.Instant.now()
            val expireInstant = now.plusSeconds(days.toLong() * 24 * 60 * 60)

            val createChatInviteLink = CreateChatInviteLink()
            createChatInviteLink.chatId = strategyChannelId
            createChatInviteLink.expireDate = expireInstant.epochSecond.toInt()
            createChatInviteLink.createsJoinRequest = true

            val inviteLink = bot.execute(createChatInviteLink)

            val inviteLinkId = DatabaseService.invites.createInviteLink(inviteLink.inviteLink, maxSubscribers, expireInstant.epochSecond, null)
            if (inviteLinkId > 0) {
                val response = """
                    <b>New premium channel invite link created</b>
                    <b>Link ID:</b> $inviteLinkId
                    <b>Link:</b> ${inviteLink.inviteLink}
                    <b>Valid for:</b> $days days
                    <b>Max subscribers:</b> $maxSubscribers

                    <i>Users must send a join request and specify the link ID.</i>
                """.trimIndent()

                bot.sendMessage(message.chatId.toString(), response, "HTML")
            } else {
                bot.sendMessage(message.chatId.toString(), "Error creating link in database")
            }
        } catch (e: NumberFormatException) {
            bot.sendMessage(message.chatId.toString(), "Invalid number format. Please use positive integers.")
        } catch (e: Exception) {
            logger.error("Error creating invite link", e)
            bot.sendMessage(message.chatId.toString(), "An error occurred while creating the link")
        }
    }

    fun handleChatJoinRequest(chatJoinRequest: ChatJoinRequest) {
        try {
            val chatId = chatJoinRequest.chat.id.toString()
            if (chatId == strategyChannelId) {
                logger.info("Processing join request for strategy channel")

                val inviteLink = chatJoinRequest.inviteLink?.inviteLink

                if (inviteLink != null) {
                    val inviteLinkId = DatabaseService.invites.getInviteLinkId(inviteLink)

                    if (inviteLinkId != null) {
                        val subscriberCount = DatabaseService.invites.getSubscriberCount(inviteLinkId)
                        val maxSubscribers = DatabaseService.invites.getMaxSubscribersForLink(inviteLinkId)

                        if (subscriberCount >= maxSubscribers) {
                            bot.sendMessage(chatJoinRequest.user.id.toString(), "Sorry, the subscriber limit for this link has been reached.")
                            return
                        }

                        val success = DatabaseService.invites.addJoinRequest(
                            inviteLinkId,
                            chatJoinRequest.user.id.toString(),
                            chatJoinRequest.user.userName,
                            chatJoinRequest.user.firstName,
                            chatJoinRequest.user.lastName
                        )

                        if (success) {
                            val approved = DatabaseService.invites.approveJoinRequest(inviteLinkId, chatJoinRequest.user.id.toString())

                            if (approved) {
                                val approveChatJoinRequest = ApproveChatJoinRequest()
                                approveChatJoinRequest.chatId = strategyChannelId
                                approveChatJoinRequest.userId = chatJoinRequest.user.id
                                bot.execute(approveChatJoinRequest)

                                val notification = """
                                    <b>New user joined the premium channel</b>
                                    <b>User:</b> ${chatJoinRequest.user.firstName} ${chatJoinRequest.user.lastName ?: ""} (@${chatJoinRequest.user.userName ?: "no username"})
                                    <b>ID:</b> ${chatJoinRequest.user.id}
                                    <b>Link ID:</b> $inviteLinkId
                                    <b>Current subscribers:</b> ${subscriberCount + 1}/$maxSubscribers
                                """.trimIndent()
                                bot.sendMessage(adminChatId, notification, "HTML")

                                bot.sendMessage(chatJoinRequest.user.id.toString(), "Your request to join the premium channel has been approved!")
                            } else {
                                bot.sendMessage(chatJoinRequest.user.id.toString(), "An error occurred while processing your request. Please try again later.")
                            }
                        }
                    } else {
                        bot.sendMessage(adminChatId, "Someone's trying to join with link that created by not my bot.")
                    }
                } else {
                    bot.sendMessage(chatJoinRequest.user.id.toString(), "Invalid invite link.")
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling chat join request", e)
        }
    }

    fun cleanupInviteLinks(channelId: String) {
        try {
            val expiredSubscribers = DatabaseService.invites.cleanupExpiredSubscribers()

            for (subscriber in expiredSubscribers) {
                try {
                    CoroutineScope(Dispatchers.IO).launch {
                        removeUserFromChannel(subscriber.userId.toLong(), channelId.toLong())
                    }

                    val notification = """
                        <b>User removed from premium channel</b>
                        <b>Reason:</b> Invite link expired
                        <b>User:</b> ${subscriber.firstName} ${subscriber.lastName ?: ""} (@${subscriber.username ?: "no username"})
                        <b>ID:</b> ${subscriber.userId}
                        <b>Link:</b> ${subscriber.inviteLink}
                    """.trimIndent()
                    bot.sendMessage(adminChatId, notification, "HTML")

                    bot.sendMessage(subscriber.userId, "Your access to the premium channel has expired. Thank you for using our service!")
                } catch (e: Exception) {
                    logger.error("Error removing user ${subscriber.userId} from channel", e)
                }
            }

            val expiredLinks = DatabaseService.invites.getExpiredInviteLinks()
            for (link in expiredLinks) {
                DatabaseService.invites.deactivateInviteLink(link.id)
                logger.info("Deactivated expired invite link ID: ${link.id}")
            }
        } catch (e: Exception) {
            logger.error("Error in cleanupInviteLinks", e)
        }
    }

    private suspend fun removeUserFromChannel(userId: Long, channelId: Long) {
        try {
            val banChatMember = BanChatMember()
            banChatMember.chatId = channelId.toString()
            banChatMember.userId = userId
            bot.execute(banChatMember)

            delay(2000)

            val unbanChatMember = UnbanChatMember()
            unbanChatMember.chatId = channelId.toString()
            unbanChatMember.userId = userId
            bot.execute(unbanChatMember)

            DatabaseService.invites.removeUserFromChannel(userId, channelId)

            logger.info("Successfully removed user $userId from channel $channelId")
        } catch (e: Exception) {
            logger.error("Failed to remove user $userId from channel $channelId", e)
        }
    }
}
