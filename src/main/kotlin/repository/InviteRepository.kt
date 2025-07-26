package repository

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import repository.InviteLink
import repository.InviteSubscriber
import repository.JoinRequest

object InviteLinks {
    const val TABLE = "invite_links"
}

object InviteSubscribers {
    const val TABLE = "invite_subscribers"
}

class InviteRepository {
    private val logger = LoggerFactory.getLogger(InviteRepository::class.java)

    private fun getConnection(): Connection = DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun createInviteLink(inviteLink: String, maxSubscribers: Int, days: Int): Long {
        val connection = getConnection()
        val currentTime = System.currentTimeMillis() / 1000
        val expiresAt = currentTime + days * 24 * 60 * 60
        val sql = "INSERT INTO invite_links (invite_link, max_subscribers, created_at, expires_at, is_active) VALUES (?, ?, ?, ?, 1)"
        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)
        statement.setInt(2, maxSubscribers)
        statement.setLong(3, currentTime)
        statement.setLong(4, expiresAt)
        statement.executeUpdate()
        val id = statement.generatedKeys.getLong(1)
        statement.close()
        connection.close()
        return id
    }

    fun addJoinRequest(inviteLinkId: Long, userId: String, username: String?, firstName: String?, lastName: String?): Boolean {
        val connection = getConnection()
        val currentTime = System.currentTimeMillis() / 1000
        val sql = "INSERT INTO join_requests (invite_link_id, user_id, username, first_name, last_name, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, inviteLinkId)
        statement.setString(2, userId)
        statement.setString(3, username)
        statement.setString(4, firstName)
        statement.setString(5, lastName)
        statement.setLong(6, currentTime)
        val result = statement.executeUpdate() > 0
        statement.close()
        connection.close()
        return result
    }

    fun approveJoinRequest(inviteLinkId: Long, userId: String): Boolean {
        val connection = getConnection()
        val currentTime = System.currentTimeMillis() / 1000
        connection.autoCommit = false
        return try {
            val getExpirySql = "SELECT expires_at FROM invite_links WHERE id = ?"
            val getExpiryStmt = connection.prepareStatement(getExpirySql)
            getExpiryStmt.setLong(1, inviteLinkId)
            val expiryResult = getExpiryStmt.executeQuery()
            if (!expiryResult.next()) return false
            val expiresAt = expiryResult.getLong("expires_at")
            val updateRequestSql = "UPDATE join_requests SET status = 'approved' WHERE invite_link_id = ? AND user_id = ? AND status = 'pending'"
            val updateRequestStmt = connection.prepareStatement(updateRequestSql)
            updateRequestStmt.setLong(1, inviteLinkId)
            updateRequestStmt.setString(2, userId)
            updateRequestStmt.executeUpdate()
            val insertSubscriberSql = "INSERT INTO invite_subscribers (invite_link_id, user_id, joined_at) VALUES (?, ?, ?)"
            val insertSubscriberStmt = connection.prepareStatement(insertSubscriberSql)
            insertSubscriberStmt.setLong(1, inviteLinkId)
            insertSubscriberStmt.setString(2, userId)
            insertSubscriberStmt.setLong(3, currentTime)
            insertSubscriberStmt.executeUpdate()
            val updateLinkSql = "UPDATE invite_links SET expires_at = ? WHERE id = ?"
            val updateLinkStmt = connection.prepareStatement(updateLinkSql)
            updateLinkStmt.setLong(1, expiresAt)
            updateLinkStmt.setLong(2, inviteLinkId)
            updateLinkStmt.executeUpdate()
            connection.commit()
            true
        } catch (e: Exception) {
            connection.rollback()
            logger.error("Error approving join request", e)
            false
        } finally {
            connection.autoCommit = true
            connection.close()
        }
    }

    fun cleanupExpiredSubscribers(): List<InviteSubscriber> {
        val url = "jdbc:sqlite:predictions.db"
        val connection = DriverManager.getConnection(url)
        val currentTime = System.currentTimeMillis() / 1000
        val expiredSubscribers = mutableListOf<InviteSubscriber>()
        val selectSql = "SELECT s.id, l.invite_link, s.user_id, s.username, s.first_name, s.last_name, s.joined_at FROM invite_subscribers s JOIN invite_links l ON s.invite_link_id = l.id WHERE l.expires_at < ? AND l.is_active = 1"
        val selectStmt = connection.prepareStatement(selectSql)
        selectStmt.setLong(1, currentTime)
        val resultSet = selectStmt.executeQuery()
        while (resultSet.next()) {
            expiredSubscribers.add(
                InviteSubscriber(
                    id = resultSet.getInt("id"),
                    inviteLink = resultSet.getString("invite_link"),
                    userId = resultSet.getString("user_id"),
                    username = resultSet.getString("username"),
                    firstName = resultSet.getString("first_name"),
                    lastName = resultSet.getString("last_name"),
                    joinedAt = resultSet.getLong("joined_at")
                )
            )
        }
        resultSet.close()
        selectStmt.close()
        if (expiredSubscribers.isNotEmpty()) {
            val deleteSql = "DELETE FROM invite_subscribers WHERE invite_link_id IN (SELECT id FROM invite_links WHERE expires_at < ?)"
            val deleteStmt = connection.prepareStatement(deleteSql)
            deleteStmt.setLong(1, currentTime)
            deleteStmt.executeUpdate()
        }
        connection.close()
        return expiredSubscribers
    }

    fun getPendingJoinRequests(): List<JoinRequest> {
        val connection = getConnection()
        val requests = mutableListOf<JoinRequest>()
        val sql = "SELECT jr.*, il.max_subscribers, il.expires_at FROM join_requests jr JOIN invite_links il ON jr.invite_link_id = il.id WHERE jr.status = 'pending' AND il.is_active = 1 AND il.expires_at > ?"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, System.currentTimeMillis() / 1000)
        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            requests.add(
                JoinRequest(
                    id = resultSet.getLong("id"),
                    inviteLinkId = resultSet.getLong("invite_link_id"),
                    userId = resultSet.getString("user_id"),
                    username = resultSet.getString("username"),
                    firstName = resultSet.getString("first_name"),
                    lastName = resultSet.getString("last_name"),
                    status = resultSet.getString("status"),
                    createdAt = resultSet.getLong("created_at"),
                    maxSubscribers = resultSet.getInt("max_subscribers"),
                    expiresAt = resultSet.getLong("expires_at")
                )
            )
        }
        resultSet.close()
        statement.close()
        connection.close()
        return requests
    }

    fun getSubscriberCount(inviteLinkId: Long): Int {
        val connection = getConnection()
        val sql = "SELECT COUNT(*) FROM invite_subscribers WHERE invite_link_id = ?"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, inviteLinkId)
        val resultSet = statement.executeQuery()
        val count = resultSet.getInt(1)
        resultSet.close()
        statement.close()
        connection.close()
        return count
    }

    fun getMaxSubscribersForLink(inviteLinkId: Long): Int {
        val connection = getConnection()
        val sql = "SELECT max_subscribers FROM invite_links WHERE id = ?"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, inviteLinkId)
        val resultSet = statement.executeQuery()
        val maxSubscribers = if (resultSet.next()) resultSet.getInt(1) else 0
        resultSet.close()
        statement.close()
        connection.close()
        return maxSubscribers
    }

    fun getExpiredInviteLinks(): List<InviteLink> {
        val connection = getConnection()
        val currentTime = System.currentTimeMillis() / 1000
        val links = mutableListOf<InviteLink>()
        val sql = "SELECT id, invite_link, max_subscribers, created_at, expires_at, is_active FROM invite_links WHERE expires_at < ? AND is_active = 1"
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, currentTime)
        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            links.add(
                InviteLink(
                    id = resultSet.getInt("id"),
                    inviteLink = resultSet.getString("invite_link"),
                    maxSubscribers = resultSet.getInt("max_subscribers"),
                    createdAt = resultSet.getLong("created_at"),
                    expiresAt = resultSet.getLong("expires_at"),
                    isActive = resultSet.getBoolean("is_active")
                )
            )
        }
        resultSet.close()
        statement.close()
        connection.close()
        return links
    }

    fun getSubscribersForInviteLink(inviteLink: String): List<InviteSubscriber> {
        val connection = getConnection()
        val subscribers = mutableListOf<InviteSubscriber>()
        val sql = "SELECT s.id, s.user_id, s.username, s.first_name, s.last_name, s.joined_at FROM invite_subscribers s JOIN invite_links l ON s.invite_link_id = l.id WHERE l.invite_link = ?"
        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)
        val resultSet = statement.executeQuery()
        while (resultSet.next()) {
            subscribers.add(
                InviteSubscriber(
                    id = resultSet.getInt("id"),
                    inviteLink = inviteLink,
                    userId = resultSet.getString("user_id"),
                    username = resultSet.getString("username"),
                    firstName = resultSet.getString("first_name"),
                    lastName = resultSet.getString("last_name"),
                    joinedAt = resultSet.getLong("joined_at")
                )
            )
        }
        resultSet.close()
        statement.close()
        connection.close()
        return subscribers
    }

    fun removeInviteSubscriber(inviteLink: String, userId: String): Boolean {
        val connection = getConnection()
        val sql = "DELETE FROM invite_subscribers WHERE invite_link_id IN (SELECT id FROM invite_links WHERE invite_link = ?) AND user_id = ?"
        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)
        statement.setString(2, userId)
        val result = statement.executeUpdate() > 0
        statement.close()
        connection.close()
        return result
    }

    fun removeInviteLink(inviteLink: String): Boolean {
        val connection = getConnection()
        val sql = "DELETE FROM invite_links WHERE invite_link = ?"
        val statement = connection.prepareStatement(sql)
        statement.setString(1, inviteLink)
        val result = statement.executeUpdate() > 0
        statement.close()
        connection.close()
        return result
    }

    fun getInviteLinkId(inviteLink: String): Long? {
        var connection: Connection? = null
        var statement: PreparedStatement? = null
        var resultSet: ResultSet? = null
        return try {
            connection = getConnection()
            val query = "SELECT id FROM invite_links WHERE invite_link = ? AND is_active = 1"
            statement = connection.prepareStatement(query)
            statement.setString(1, inviteLink)
            resultSet = statement.executeQuery()
            if (resultSet.next()) resultSet.getLong("id") else null
        } catch (e: Exception) {
            logger.error("Error getting invite link ID", e)
            null
        } finally {
            resultSet?.close()
            statement?.close()
            connection?.close()
        }
    }

    fun removeUserFromChannel(userId: Long, channelId: Long) {
        val connection = getConnection()
        val sql = "DELETE FROM invite_subscribers WHERE user_id = ? AND invite_link_id IN (SELECT id FROM invite_links WHERE chat_id = ?)"
        val statement = connection.prepareStatement(sql)
        statement.setString(1, userId.toString())
        statement.setString(2, channelId.toString())
        statement.executeUpdate()
        statement.close()
        connection.close()
    }

    fun deactivateInviteLink(linkId: Int) {
        val connection = getConnection()
        val sql = "UPDATE invite_links SET is_active = 0 WHERE id = ?"
        val statement = connection.prepareStatement(sql)
        statement.setInt(1, linkId)
        statement.executeUpdate()
        statement.close()
        connection.close()
    }

    fun getActiveInviteLinksWithRemainingSlots(): List<Pair<String, Int>> {
        val connection = getConnection()
        val sql = "SELECT id, invite_link, max_subscribers FROM invite_links WHERE is_active = 1 AND expires_at > ?"
        val stmt = connection.prepareStatement(sql)
        stmt.setLong(1, System.currentTimeMillis() / 1000)
        val rs = stmt.executeQuery()
        val result = mutableListOf<Pair<String, Int>>()
        while (rs.next()) {
            val id = rs.getLong("id")
            val link = rs.getString("invite_link")
            val maxSub = rs.getInt("max_subscribers")
            val current = getSubscriberCount(id)
            val remaining = maxSub - current
            if (remaining > 0) {
                result.add(link to remaining)
            }
        }
        rs.close()
        stmt.close()
        connection.close()
        return result
    }
}
