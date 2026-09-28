package repository

import java.sql.DriverManager

/**
 * Tracks which fixtures have already been announced in an aggregated
 * league-summary digest post. A digest covers up to ten leagues in a single
 * Telegram message, so the per-match telegramMessageId mechanism cannot be
 * reused here: setting it would make live-update jobs rewrite the aggregated
 * post with a single league's detailed format.
 */
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

    fun markPosted(fixtureId: String, league: String) {
        val conn = getConnection()
        try {
            val stmt = conn.prepareStatement(
                "INSERT OR IGNORE INTO digest_posts (fixture_id, league, posted_at) VALUES (?, ?, ?)"
            )
            stmt.setString(1, fixtureId)
            stmt.setString(2, league)
            stmt.setLong(3, System.currentTimeMillis() / 1000)
            stmt.executeUpdate()
            stmt.close()
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
