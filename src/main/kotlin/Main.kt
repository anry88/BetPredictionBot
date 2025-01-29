import dto.JsonlMatch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import service.DatabaseService
import service.HttpAPIFootballService
import java.io.File

class FetchMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        val footballService = HttpAPIFootballService(footballBot)
        runBlocking {
            footballService.fetchMatches()
        }
    }
}

class UpdateMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
//        val footballService = HttpAPIFootballService(footballBot)
        runBlocking {
            footballBot.sendUpcomingMatchesToTelegram()
        }
    }
}

class UpdatePastMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        val footballService = HttpAPIFootballService(footballBot)
        runBlocking {
            footballService.updatePastMatches()
        }
    }
}


class UpdateLiveMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.updateLiveMatches()
        }
    }
}

class UpdateLeaguePredictabilityJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.updateLeaguePredictability()
        }
    }
}

class SendAccuracyJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.sendPredictionAccuracyMessage()
        }
    }
}
class SendWeeklyAccuracyJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.sendWeeklyPredictionAccuracyMessage()
        }
    }
}
class SendMonthlyAccuracyJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.sendMonthlyPredictionAccuracyMessage()
        }
    }
}
class SendYearlyAccuracyJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.sendYearlyPredictionAccuracyMessage()
        }
    }
}

class UploadModelDataJob : Job {
    private val logger = LoggerFactory.getLogger(UploadModelDataJob::class.java)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        val footballService = HttpAPIFootballService(footballBot)
        runBlocking {
            // 1. Выбираем матчи только из lиг, где modelBased = true,
            //    и только сыгранные (actualOutcome != null).
            val modelBasedLeagues = footballService.getModelBasedLeaguesFromConfig()
            val completedMatches = DatabaseService.getAllMatches().filter { match ->
                modelBasedLeagues.any { leagueConfig ->
                    leagueConfig.modelBased
//                            &&
//                            match.matchType == footballService.combineLeagueName(match)
                } && match.actualOutcome != null
            }

            if (completedMatches.isEmpty()) {
                logger.info("No completed matches found for model-based leagues")
                return@runBlocking
            }

            // 2. Формируем .jsonl
            val file = createJsonlFileForModel(completedMatches)
            // 3. Отправляем файл на http://localhost:7007/uploadLines
            val responseStatus = uploadJsonlToLocalModel(file)
            logger.info("Upload to local model finished with status: $responseStatus")

            // При необходимости можно удалить временный файл:
            file.delete()
        }
    }

    private fun createJsonlFileForModel(matches: List<dto.MatchInfo>): File {
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
                    awayWinOdds = match.awayWinOdds
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
                // Если сервер принимает как "application/json" c сырым текстом:
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(jsonlFile.readText())
            }.status.value
        } catch (e: Exception) {
            logger.error("Error uploading to local model: ${e.message}")
            -1
        }
    }

}

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)

    val telegramBotToken: String =
        Config.getProperty("telegram.bot.token") ?: throw IllegalStateException("Telegram Token not found")

    val footballBot = FootballBot(telegramBotToken)
    botsApi.registerBot(footballBot)
    logger.info("Football bot started successfully")

    // Setup and start Quartz scheduler
    val scheduler = StdSchedulerFactory().scheduler
    scheduler.start()

    val jobDataMap = JobDataMap()
    jobDataMap["footballBot"] = footballBot

    val job = JobBuilder.newJob(FetchMatchesJob::class.java)
        .withIdentity("fetchMatchesJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val dailyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("fetchMatchesDailyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 8,16 * * ?"))
        .build()

    val immediateTrigger = TriggerBuilder.newTrigger()
        .withIdentity("fetchMatchesImmediateTrigger", "group1")
        .startNow()
        .build()

    val updateJob = JobBuilder.newJob(UpdateMatchesJob::class.java)
        .withIdentity("updateMatchesJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val dailyUpdateTrigger = TriggerBuilder.newTrigger()
        .withIdentity("updateMatchesDailyTrigger", "group1")
        .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMinutes(1).repeatForever())
        .build()

    val updateLeaguePredictabilityJob = JobBuilder.newJob(UpdateLeaguePredictabilityJob::class.java)
        .withIdentity("updateLeaguePredictabilityJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val updateLeaguePredictabilityTrigger = TriggerBuilder.newTrigger()
        .withIdentity("updateLeaguePredictabilityTrigger", "group1")
        .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(8, 0))  // Каждый день в 08:00
        .build()

    val updatePastMatchesJob = JobBuilder.newJob(UpdatePastMatchesJob::class.java)
        .withIdentity("updatePastMatchesJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val updatePastMatchesTrigger = TriggerBuilder.newTrigger()
        .withIdentity("updatePastMatchesTrigger", "group1")
        .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(7, 0))  // Every day at 07:00 AM
        .build()

    // Добавляем новый job для отправки сообщений с точностью предсказаний
    val accuracyJob = JobBuilder.newJob(SendAccuracyJob::class.java)
        .withIdentity("sendAccuracyJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val accuracyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("sendAccuracyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(8, 30))  // Каждый день в 08:30
        .build()

    // SendWeeklyAccuracyJob setup
    val weeklyAccuracyJob = JobBuilder.newJob(SendWeeklyAccuracyJob::class.java)
        .withIdentity("sendWeeklyAccuracyJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val weeklyAccuracyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("sendWeeklyAccuracyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.weeklyOnDayAndHourAndMinute(2, 8, 31))
        .build()

    // SendMonthlyAccuracyJob setup
    val monthlyAccuracyJob = JobBuilder.newJob(SendMonthlyAccuracyJob::class.java)
        .withIdentity("sendMonthlyAccuracyJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val monthlyAccuracyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("sendMonthlyAccuracyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.monthlyOnDayAndHourAndMinute(1, 8, 32))  // Первого числа каждого месяца в 08:00
        .build()

    // SendYearlyAccuracyJob setup
    val yearlyAccuracyJob = JobBuilder.newJob(SendYearlyAccuracyJob::class.java)
        .withIdentity("sendYearlyAccuracyJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val yearlyAccuracyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("sendYearlyAccuracyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 33 8 1 1 ?"))
        .build()

    val liveUpdateJob = JobBuilder.newJob(UpdateLiveMatchesJob::class.java)
        .withIdentity("updateLiveMatchesJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val liveUpdateTrigger = TriggerBuilder.newTrigger()
        .withIdentity("updateLiveMatchesTrigger", "group1")
        .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMinutes(10).repeatForever())
        .build()

    val uploadModelDataJob = JobBuilder.newJob(UploadModelDataJob::class.java)
        .withIdentity("uploadModelDataJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    // CRON: запускаем каждую неделю по понедельникам в 01:00
    val uploadModelDataTrigger = TriggerBuilder.newTrigger()
        .withIdentity("uploadModelDataTrigger", "group1")
        .withSchedule(CronScheduleBuilder.weeklyOnDayAndHourAndMinute(DateBuilder.MONDAY, 1, 0))
        .build()

    // Schedule the jobs
    scheduler.scheduleJob(job, setOf(dailyTrigger, immediateTrigger).toMutableSet(), true)
    scheduler.scheduleJob(updateJob, dailyUpdateTrigger)
    scheduler.scheduleJob(updatePastMatchesJob, updatePastMatchesTrigger)
    scheduler.scheduleJob(updateLeaguePredictabilityJob, updateLeaguePredictabilityTrigger)
    scheduler.scheduleJob(accuracyJob, accuracyTrigger)
    scheduler.scheduleJob(weeklyAccuracyJob, weeklyAccuracyTrigger)
    scheduler.scheduleJob(monthlyAccuracyJob, monthlyAccuracyTrigger)
    scheduler.scheduleJob(yearlyAccuracyJob, yearlyAccuracyTrigger)
    scheduler.scheduleJob(liveUpdateJob, liveUpdateTrigger)
    scheduler.scheduleJob(uploadModelDataJob, uploadModelDataTrigger)

    logger.info("Scheduled FetchMatchesJob to run three times a day at midnight, 8 AM, and 4 PM")
    logger.info("Scheduled UpdateMatchesJob to run at every hour")
    logger.info("Scheduled UpdateLeaguePredictabilityJob to run daily at 08:00")
    logger.info("Scheduled SendAccuracyJob to run daily at 08:30")
    logger.info("Scheduled SendWeeklyAccuracyJob to run every Monday at 08:31")
    logger.info("Scheduled SendMonthlyAccuracyJob to run on the 1st of every month at 08:32")
    logger.info("Scheduled SendYearlyAccuracyJob to run on the 1st of January of every year at 08:33")
    logger.info("Executed FetchMatchesJob immediately upon startup")
    logger.info("Executed UpdateLiveMatchesJob immediately upon startup to run every 5 minutes")
    logger.info("Executed UploadModelDataJob every monday at 1:00")
}
