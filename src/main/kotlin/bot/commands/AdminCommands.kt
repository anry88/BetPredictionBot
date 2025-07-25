package bot.commands

import FootballBot
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.objects.InputFile
import service.DatabaseService
import java.io.File

class AdminCommands(private val bot: FootballBot) {
    fun handleGetDatabase(chatId: String) {
        val databaseFile = File("predictions.db")
        if (databaseFile.exists()) {
            val document = SendDocument()
            document.chatId = chatId
            document.document = InputFile(databaseFile)
            document.caption = "Here is the database file."
            bot.execute(document)
        } else {
            bot.sendMessage(chatId, "Database file not found.")
        }
    }

    fun handleUserCount(chatId: String) {
        val userCount = DatabaseService.users.getUserCount()
        bot.sendMessage(chatId, "Number of unique users: $userCount")
    }

    fun handleActiveUserCount(chatId: String) {
        val userCount = DatabaseService.users.getActiveUserCountLast24Hours()
        bot.sendMessage(chatId, "Number of unique users for last day: $userCount")
    }
}
