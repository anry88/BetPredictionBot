package repository

import java.sql.Connection
import java.sql.DriverManager

data class ScheduledJob(
    val id: Long,
    val userId: String,
    val command: String,
    val params: String?,
    val nextRun: Long,
    val intervalSeconds: Long
)

class ScheduledJobRepository {
    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun addJob(job: ScheduledJob): Long {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "INSERT INTO scheduled_jobs (user_id, command, params, next_run, interval_seconds) VALUES (?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        stmt.setString(1, job.userId)
        stmt.setString(2, job.command)
        stmt.setString(3, job.params)
        stmt.setLong(4, job.nextRun)
        stmt.setLong(5, job.intervalSeconds)
        stmt.executeUpdate()
        val rs = stmt.generatedKeys
        val id = if (rs.next()) rs.getLong(1) else 0L
        rs.close()
        stmt.close()
        connection.close()
        return id
    }

    fun getDueJobs(currentTime: Long = System.currentTimeMillis() / 1000): List<ScheduledJob> {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "SELECT id, user_id, command, params, next_run, interval_seconds FROM scheduled_jobs WHERE next_run <= ?"
        )
        stmt.setLong(1, currentTime)
        val rs = stmt.executeQuery()
        val jobs = mutableListOf<ScheduledJob>()
        while (rs.next()) {
            jobs += ScheduledJob(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("command"),
                rs.getString("params"),
                rs.getLong("next_run"),
                rs.getLong("interval_seconds")
            )
        }
        rs.close()
        stmt.close()
        connection.close()
        return jobs
    }

    fun updateNextRun(id: Long, nextRun: Long) {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "UPDATE scheduled_jobs SET next_run = ? WHERE id = ?"
        )
        stmt.setLong(1, nextRun)
        stmt.setLong(2, id)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }

    fun deleteJob(id: Long) {
        val connection = getConnection()
        val stmt = connection.prepareStatement("DELETE FROM scheduled_jobs WHERE id = ?")
        stmt.setLong(1, id)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }

    fun getJobsByUser(userId: String): List<ScheduledJob> {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "SELECT id, user_id, command, params, next_run, interval_seconds FROM scheduled_jobs WHERE user_id = ?"
        )
        stmt.setString(1, userId)
        val rs = stmt.executeQuery()
        val jobs = mutableListOf<ScheduledJob>()
        while (rs.next()) {
            jobs += ScheduledJob(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("command"),
                rs.getString("params"),
                rs.getLong("next_run"),
                rs.getLong("interval_seconds")
            )
        }
        rs.close()
        stmt.close()
        connection.close()
        return jobs
    }

    fun updateJob(job: ScheduledJob) {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "UPDATE scheduled_jobs SET command = ?, params = ?, next_run = ?, interval_seconds = ? WHERE id = ?"
        )
        stmt.setString(1, job.command)
        stmt.setString(2, job.params)
        stmt.setLong(3, job.nextRun)
        stmt.setLong(4, job.intervalSeconds)
        stmt.setLong(5, job.id)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }
}

