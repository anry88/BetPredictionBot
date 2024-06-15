import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import dto.MatchInfo
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import org.slf4j.LoggerFactory
import java.time.format.DateTimeParseException

object CSVService {
    private const val FILE_NAME = "predictions.csv"
    private val dateTimeFormatterWithSeconds: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ssX")
        .toFormatter()
    private val dateTimeFormatterWithoutSeconds: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mmX")
        .toFormatter()
    private val logger = LoggerFactory.getLogger(CSVService::class.java)

    // Set to store hashes of existing matches
    private val existingMatchesHashes = mutableSetOf<String>()

    init {
        // Создаем файл и добавляем заголовок, если файл не существует
        val file = File(FILE_NAME)
        if (!file.exists()) {
            file.createNewFile()
            val writer = CSVWriter(FileWriter(file, true))
            writer.use {
                it.writeNext(arrayOf("DateTime", "MatchType", "Teams", "Outcome", "Score", "Odds"))
            }
        } else {
            loadExistingMatchesHashes()
        }
    }

    private fun loadExistingMatchesHashes() {
        val reader = CSVReader(FileReader(FILE_NAME))
        reader.use { csvReader ->
            val lines = csvReader.readAll()
            lines.drop(1).forEach { line -> // Пропускаем первую строку (заголовок)
                if (line.size == 6) {
                    val matchInfo = MatchInfo(line[0], line[1], line[2], line[3], line[4], line[5])
                    existingMatchesHashes.add(generateMatchHash(matchInfo))
                }
            }
        }
    }

    fun appendRows(rows: List<MatchInfo>) {
        val writer = CSVWriter(FileWriter(FILE_NAME, true))
        writer.use {
            rows.forEach { row ->
                val matchHash = generateMatchHash(row)
                if (!existingMatchesHashes.contains(matchHash)) {
                    it.writeNext(arrayOf(normalize(row.datetime), normalize(row.matchType), normalize(row.teams), normalize(row.outcome), normalize(row.score), normalize(row.odds)))
                    existingMatchesHashes.add(matchHash)
                } else {
                    logger.info("Duplicate match found, skipping: ${row.datetime} ${row.teams}")
                }
            }
        }
    }

    private fun generateMatchHash(match: MatchInfo): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val normalizedInput = "${normalize(match.datetime)}-${normalize(match.matchType)}-${normalize(match.teams)}"
        val hash = digest.digest(normalizedInput.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun normalize(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "").replace("?", "e")
    }

    fun matchExists(match: MatchInfo): Boolean {
        val matchHash = generateMatchHash(match)
        return existingMatchesHashes.contains(matchHash)
    }

    fun getUpcomingMatches(): List<MatchInfo> {
        val matches = mutableListOf<MatchInfo>()
        val reader = CSVReader(FileReader(FILE_NAME))
        reader.use { csvReader ->
            val lines = csvReader.readAll()
            val now = OffsetDateTime.now()
            val tomorrow = now.plusDays(1)

            lines.drop(1).forEach { line -> // Пропускаем первую строку (заголовок)
                if (line.size == 6) {
                    try {
                        val matchDateTime = try {
                            OffsetDateTime.parse(line[0] + "Z", dateTimeFormatterWithSeconds)
                        } catch (e: DateTimeParseException) {
                            OffsetDateTime.parse(line[0] + "Z", dateTimeFormatterWithoutSeconds)
                        }
                        if (matchDateTime.isAfter(now) && matchDateTime.isBefore(tomorrow)) {
                            matches.add(MatchInfo(line[0], line[1], line[2], line[3], line[4], line[5]))
                        } else {
                            logger.debug("Match is not upcoming: $line")
                        }
                    } catch (e: Exception) {
                        logger.error("Error parsing date time: ${line[0]}", e)
                    }
                }
            }
        }
        return matches
    }
}
