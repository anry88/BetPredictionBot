import kotlinx.coroutines.runBlocking
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import service.HttpAPIFootballService

class FetchMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        val footballService = HttpAPIFootballService(footballBot)
        runBlocking {
            footballService.fetchMatches()
            footballService.fetchPastMatches()
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
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0,8,16 * * ?"))
        .build()

    val immediateTrigger = TriggerBuilder.newTrigger()
        .withIdentity("fetchMatchesImmediateTrigger", "group1")
        .startNow()
        .build()

    // Добавляем новый job для отправки сообщений с точностью предсказаний
    val accuracyJob = JobBuilder.newJob(SendAccuracyJob::class.java)
        .withIdentity("sendAccuracyJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val accuracyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("sendAccuracyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(7, 30))  // Каждый день в 07:30
        .build()

    // Schedule the jobs
    scheduler.scheduleJob(job, setOf(dailyTrigger, immediateTrigger).toMutableSet(), true)
    scheduler.scheduleJob(accuracyJob, accuracyTrigger)

    logger.info("Scheduled FetchMatchesJob to run three times a day at midnight, 8 AM, and 4 PM")
    logger.info("Scheduled SendAccuracyJob to run daily at 07:30")
    logger.info("Executed FetchMatchesJob immediately upon startup")
}
