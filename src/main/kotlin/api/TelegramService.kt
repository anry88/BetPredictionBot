package api

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup

interface TelegramService {
    fun sendMessageAndGetId(
        chatId: String,
        text: String,
        replyMarkup: InlineKeyboardMarkup? = null
    ): Int?

    fun updateMessage(chatId: String, messageId: String, text: String)
}