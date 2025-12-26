package service

import Config
import dto.MatchInfo
import dto.JsonlMatch
import service.DatabaseService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

object ModelDataUploader {
    private val logger = LoggerFactory.getLogger(ModelDataUploader::class.java)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    suspend fun uploadModelData(): Int {
        val isTest = Config.getProperty("test")?.toBoolean() ?: false
        if (isTest) return -1

        val completedMatches = DatabaseService.matches
            .getAllMatchesForLastYears(2)
            .filter { it.actualOutcome != null }

        if (completedMatches.isEmpty()) {
            logger.info("No completed matches found for upload")
            return -1
        }

        val file = createJsonlFileForModel(completedMatches)
        val status = uploadJsonlToLocalModel(file)
        logger.info("Upload to local model finished with status: $status")
        file.delete()
        return status
    }

    private fun createJsonlFileForModel(matches: List<MatchInfo>): File {
        val file = File("modelData.jsonl")
        file.bufferedWriter().use { writer ->
            matches.forEach { match ->
                val jsonlMatch = JsonlMatch(
                    date = match.datetime,
                    matchType = match.matchType,
                    teams = match.teams,
                    predictedScore = match.predictedScore,
                    actualScore = match.actualScore,
                    predictedOutcome = match.predictedOutcome,
                    actualOutcome = match.actualOutcome,
                    odds = match.odds,
                    bookmakerName = match.bookmakerName,
                    homeWinOdds = match.homeWinOdds,
                    drawOdds = match.drawOdds,
                    awayWinOdds = match.awayWinOdds,
                    modelHomeWinProb = match.modelHomeWinProb,
                    modelDrawProb = match.modelDrawProb,
                    modelAwayWinProb = match.modelAwayWinProb,
                    modelExpectedHomeGoals = match.modelExpectedHomeGoals,
                    modelExpectedAwayGoals = match.modelExpectedAwayGoals,
                    calibratedHomeWinProb = match.calibratedHomeWinProb,
                    calibratedDrawProb = match.calibratedDrawProb,
                    calibratedAwayWinProb = match.calibratedAwayWinProb,
                    calibrationApplied = match.calibrationApplied
                )
                val line = Json.encodeToString(jsonlMatch)
                writer.write(line)
                writer.newLine()
            }
        }
        return file
    }

    private suspend fun uploadJsonlToLocalModel(jsonlFile: File): Int {
        val url = "http://localhost:7007/uploadLines"
        return try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonlFile.readText())
            }.status.value
        } catch (e: Exception) {
            logger.error("Error uploading to local model: ${'$'}{e.message}")
            -1
        }
    }
}
