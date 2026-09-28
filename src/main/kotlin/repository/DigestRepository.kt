package repository

import java.sql.DriverManager

/**
 * Tracks which fixtures have already been announced in an aggregated
 * league-summary digest post. A digest covers up to ten leagues in a single
 * Telegram message, so the per-match telegramMessageId mechanism cannot be
 * reused here: setting it would make live-update jobs rewrite the aggregated
 * post with a single league's detailed format.
 */
data class DigestEntry(
    val fixtureId: String,
    val league: String,
    val summaryMessageId: String,
    val postedAt: Long
)

class DigestRepository {
    private fun getConnection() =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun getPostedIds(fixtureIds: List<String>): Set<String> {
        if (fixtureIds.isEmpty()) return emptySet()
        val posted = mutableSetOf<String>()
        val conn = getConnection()
        try {
            fixtureIds.distinct().chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val stmt = conn.prepareStatement(
                    "SELECT fixture_id FROM digest_posts WHERE fixture_id IN ($placeholders)"
                )
                chunk.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    posted.add(rs.getString("fixture_id"))
                }
                rs.close()
                stmt.close()
            }
        } finally {
            conn.close()
        }
        return posted
    }

    fun markPosted(fixtureId: String, league: String, summaryMessageId: String) {
        val conn = getConnection()
        try {
            val stmt = conn.prepareStatement(
                "INSERT OR IGNORE INTO digest_posts (fixture_id, league, posted_at, summary_message_id) VALUES (?, ?, ?, ?)"
            )
            stmt.setString(1, fixtureId)
            stmt.setString(2, league)
            stmt.setLong(3, System.currentTimeMillis() / 1000)
            stmt.setString(4, summaryMessageId)
            stmt.executeUpdate()
            stmt.close()
        } finally {
            conn.close()
        }
    }

    fun getRecentMessageIds(sinceEpochSeconds: Long): List<String> {
        val conn = getConnection()
        try {
            val stmt = conn.prepareStatement(
                "SELECT DISTINCT summary_message_id FROM digest_posts " +
                    "WHERE posted_at >= ? AND summary_message_id IS NOT NULL ORDER BY posted_at"
            )
            stmt.setLong(1, sinceEpochSeconds)
            val rs = stmt.executeQuery()
            val ids = mutableListOf<String>()
            while (rs.next()) {
                ids.add(rs.getString("summary_message_id"))
            }
            rs.close()
            stmt.close()
            return ids
        } finally {
            conn.close()
        }
    }

    fun getEntriesByMessage(summaryMessageId: String): List<DigestEntry> {
        val conn = getConnection()
        try {
            val stmt = conn.prepareStatement(
                "SELECT fixture_id, league, summary_message_id, posted_at FROM digest_posts " +
                    "WHERE summary_message_id = ? ORDER BY rowid"
            )
            stmt.setString(1, summaryMessageId)
            val rs = stmt.executeQuery()
            val entries = mutableListOf<DigestEntry>()
            while (rs.next()) {
                entries.add(
                    DigestEntry(
                        rs.getString("fixture_id"),
                        rs.getString("league"),
                        rs.getString("summary_message_id"),
                        rs.getLong("posted_at")
                    )
                )
            }
            rs.close()
            stmt.close()
            return entries
        } finally {
            conn.close()
        }
    }

    fun pruneEntriesOlderThanDays(days: Long): Int {
        val cutoff = System.currentTimeMillis() / 1000 - days * 24 * 60 * 60
        val conn = getConnection()
        try {
            val stmt = conn.prepareStatement("DELETE FROM digest_posts WHERE posted_at < ?")
            stmt.setLong(1, cutoff)
            val deleted = stmt.executeUpdate()
            stmt.close()
            return deleted
        } finally {
            conn.close()
        }
    }
}
