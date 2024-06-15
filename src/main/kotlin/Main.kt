import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import service.HttpFootBallDataService

class FetchMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballDataService = HttpFootBallDataService()
        footballDataService.fetchMatches()
    }
}

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)

    val telegramBotToken: String =
        Config.getProperty("telegram.bot.token") ?: throw IllegalStateException("Telegram Token not found")

    try {
        botsApi.registerBot(FootballBot(telegramBotToken))
        logger.info("Football bot started successfully")
    } catch (e: Exception) {
        logger.error("Failed to start football bot", e)
    }

    // Setup and start Quartz scheduler
    val scheduler = StdSchedulerFactory().scheduler
    scheduler.start()

    val job = JobBuilder.newJob(FetchMatchesJob::class.java)
        .withIdentity("fetchMatchesJob", "group1")
        .build()

    // Trigger for scheduled execution three times a day
    val dailyTrigger = TriggerBuilder.newTrigger()
        .withIdentity("fetchMatchesDailyTrigger", "group1")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0,8,16 * * ?")) // At 00:00, 08:00, and 16:00 every day
        .build()

    // Trigger for immediate execution at startup
    val immediateTrigger = TriggerBuilder.newTrigger()
        .withIdentity("fetchMatchesImmediateTrigger", "group1")
        .startNow()
        .build()

    // Schedule the job with both triggers
    scheduler.scheduleJob(job, setOf(dailyTrigger, immediateTrigger).toMutableSet(), true)

    logger.info("Scheduled FetchMatchesJob to run three times a day at midnight, 8 AM, and 4 PM")
    logger.info("Executed FetchMatchesJob immediately upon startup")
}
