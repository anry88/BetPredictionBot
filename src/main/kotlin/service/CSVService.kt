import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import dto.MatchInfo
import java.io.File
import java.io.FileReader
import java.io.FileWriter
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

    init {
        // Создаем файл и добавляем заголовок, если файл не существует
        val file = File(FILE_NAME)
        if (!file.exists()) {
            file.createNewFile()
            val writer = CSVWriter(FileWriter(file, true))
            writer.use {
                it.writeNext(arrayOf("DateTime", "MatchType", "Teams", "Outcome", "Score", "Odds"))
            }
        }
    }

    fun appendRows(rows: List<MatchInfo>) {
        val writer = CSVWriter(FileWriter(FILE_NAME, true))
        writer.use {
            rows.forEach { row ->
                it.writeNext(arrayOf(row.datetime, row.matchType, row.teams, row.outcome, row.score, row.odds))
            }
        }
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
