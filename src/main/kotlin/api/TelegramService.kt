package api

interface TelegramService {
    fun sendMessageAndGetId(chatId: String, text: String): Int?
    fun updateMessage(chatId: String, messageId: String, text: String)
}