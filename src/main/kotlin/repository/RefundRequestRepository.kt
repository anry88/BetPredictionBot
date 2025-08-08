package repository

import java.sql.Connection
import java.sql.DriverManager


data class RefundRequest(
    val id: Long,
    val userId: String,
    val paymentId: Long,
    val reason: String,
    val status: String,
    val createdAt: Long,
    val adminComment: String?,
    val userComment: String?
)

class RefundRequestRepository {
    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun createRequest(userId: String, paymentId: Long, reason: String): Long {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "INSERT INTO refund_requests (user_id, payment_id, reason, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        stmt.setString(1, userId)
        stmt.setLong(2, paymentId)
        stmt.setString(3, reason)
        stmt.setLong(4, System.currentTimeMillis() / 1000)
        stmt.executeUpdate()
        val rs = stmt.generatedKeys
        val id = if (rs.next()) rs.getLong(1) else 0L
        rs.close()
        stmt.close()
        connection.close()
        return id
    }

    fun updateStatus(id: Long, status: String, comment: String? = null) {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "UPDATE refund_requests SET status = ?, admin_comment = ? WHERE id = ?"
        )
        stmt.setString(1, status)
        stmt.setString(2, comment)
        stmt.setLong(3, id)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }

    fun saveUserComment(id: Long, comment: String) {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "UPDATE refund_requests SET user_comment = ?, status = 'pending' WHERE id = ?"
        )
        stmt.setString(1, comment)
        stmt.setLong(2, id)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }

    fun getRequest(id: Long): RefundRequest? {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "SELECT id, user_id, payment_id, reason, status, created_at, admin_comment, user_comment FROM refund_requests WHERE id = ?"
        )
        stmt.setLong(1, id)
        val rs = stmt.executeQuery()
        val req = if (rs.next()) {
            RefundRequest(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getLong("payment_id"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getString("admin_comment"),
                rs.getString("user_comment")
            )
        } else {
            null
        }
        rs.close()
        stmt.close()
        connection.close()
        return req
    }
}

