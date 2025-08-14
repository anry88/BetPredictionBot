package repository

import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CommandUsageRepository {
    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    private fun currentMonth(): String =
        LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"))

    fun incrementUsage(userId: String, command: String): Int {
        val connection = getConnection()
        val month = currentMonth()
        val select = connection.prepareStatement(
            "SELECT count FROM command_usage WHERE user_id = ? AND command = ? AND month = ?"
        )
        select.setString(1, userId)
        select.setString(2, command)
        select.setString(3, month)
        val rs = select.executeQuery()
        val existing = if (rs.next()) rs.getInt("count") else null
        rs.close()
        select.close()
        val newCount = (existing ?: 0) + 1
        val stmt = connection.prepareStatement(
            "INSERT INTO command_usage (user_id, command, month, count) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(user_id, command, month) DO UPDATE SET count = ?"
        )
        stmt.setString(1, userId)
        stmt.setString(2, command)
        stmt.setString(3, month)
        stmt.setInt(4, newCount)
        stmt.setInt(5, newCount)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
        return getTotalUsage(userId)
    }

    fun getUsage(userId: String, command: String): Int {
        val connection = getConnection()
        val month = currentMonth()
        val stmt = connection.prepareStatement(
            "SELECT count FROM command_usage WHERE user_id = ? AND command = ? AND month = ?"
        )
        stmt.setString(1, userId)
        stmt.setString(2, command)
        stmt.setString(3, month)
        val rs = stmt.executeQuery()
        val count = if (rs.next()) rs.getInt("count") else 0
        rs.close()
        stmt.close()
        connection.close()
        return count
    }

    fun getTotalUsage(userId: String): Int {
        val connection = getConnection()
        val month = currentMonth()
        val stmt = connection.prepareStatement(
            "SELECT SUM(count) as total FROM command_usage WHERE user_id = ? AND month = ?"
        )
        stmt.setString(1, userId)
        stmt.setString(2, month)
        val rs = stmt.executeQuery()
        val total = if (rs.next()) rs.getInt("total") else 0
        rs.close()
        stmt.close()
        connection.close()
        return total
    }

    fun clearOldEntries() {
        val connection = getConnection()
        val month = currentMonth()
        val stmt = connection.prepareStatement(
            "DELETE FROM command_usage WHERE month < ?"
        )
        stmt.setString(1, month)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }
}
