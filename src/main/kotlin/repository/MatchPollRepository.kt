package repository

import java.sql.Connection
import java.sql.DriverManager

data class MatchPoll(
    val fixtureId: String,
    val teams: String?,
    val pollMessageId: String?,
    val pollId: String?,
    val pollDate: String,
    val closed: Boolean
)

class MatchPollRepository {
    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun addPoll(fixtureId: String, pollDate: String, teams: String) {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "INSERT OR IGNORE INTO match_polls (fixture_id, poll_date, teams, closed) VALUES (?, ?, ?, 0)"
        )
        stmt.setString(1, fixtureId)
        stmt.setString(2, pollDate)
        stmt.setString(3, teams)
        stmt.executeUpdate()
        stmt.close()
        conn.close()
    }

    fun existsPollForDate(pollDate: String): Boolean {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "SELECT 1 FROM match_polls WHERE poll_date = ? LIMIT 1"
        )
        stmt.setString(1, pollDate)
        val rs = stmt.executeQuery()
        val exists = rs.next()
        rs.close()
        stmt.close()
        conn.close()
        return exists
    }

    fun markPollPosted(fixtureId: String, pollMessageId: String, pollId: String) {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "UPDATE match_polls SET poll_message_id = ?, poll_id = ? WHERE fixture_id = ?"
        )
        stmt.setString(1, pollMessageId)
        stmt.setString(2, pollId)
        stmt.setString(3, fixtureId)
        stmt.executeUpdate()
        stmt.close()
        conn.close()
    }

    fun markPollClosed(fixtureId: String) {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "UPDATE match_polls SET closed = 1 WHERE fixture_id = ?"
        )
        stmt.setString(1, fixtureId)
        stmt.executeUpdate()
        stmt.close()
        conn.close()
    }

    fun getPollByFixtureId(fixtureId: String): MatchPoll? {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "SELECT fixture_id, teams, poll_message_id, poll_id, poll_date, closed FROM match_polls WHERE fixture_id = ?"
        )
        stmt.setString(1, fixtureId)
        val rs = stmt.executeQuery()
        val poll = if (rs.next()) {
            MatchPoll(
                rs.getString("fixture_id"),
                rs.getString("teams"),
                rs.getString("poll_message_id"),
                rs.getString("poll_id"),
                rs.getString("poll_date"),
                rs.getInt("closed") == 1
            )
        } else null
        rs.close()
        stmt.close()
        conn.close()
        return poll
    }

    fun getPendingPolls(): List<MatchPoll> {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "SELECT fixture_id, teams, poll_message_id, poll_id, poll_date, closed FROM match_polls WHERE poll_message_id IS NULL AND closed = 0"
        )
        val rs = stmt.executeQuery()
        val polls = mutableListOf<MatchPoll>()
        while (rs.next()) {
            polls += MatchPoll(
                rs.getString("fixture_id"),
                rs.getString("teams"),
                rs.getString("poll_message_id"),
                rs.getString("poll_id"),
                rs.getString("poll_date"),
                rs.getInt("closed") == 1
            )
        }
        rs.close()
        stmt.close()
        conn.close()
        return polls
    }

    fun getOpenPostedPolls(): List<MatchPoll> {
        val conn = getConnection()
        val stmt = conn.prepareStatement(
            "SELECT fixture_id, teams, poll_message_id, poll_id, poll_date, closed " +
                    "FROM match_polls WHERE poll_message_id IS NOT NULL AND closed = 0"
        )
        val rs = stmt.executeQuery()
        val polls = mutableListOf<MatchPoll>()
        while (rs.next()) {
            polls += MatchPoll(
                rs.getString("fixture_id"),
                rs.getString("teams"),
                rs.getString("poll_message_id"),
                rs.getString("poll_id"),
                rs.getString("poll_date"),
                rs.getInt("closed") == 1
            )
        }
        rs.close()
        stmt.close()
        conn.close()
        return polls
    }
}
