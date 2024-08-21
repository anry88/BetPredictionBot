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
            footballBot.sendUpcomingMatchesToTelegram()
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

    // Schedule the jobs
    scheduler.scheduleJob(job, setOf(dailyTrigger, immediateTrigger).toMutableSet(), true)
    scheduler.scheduleJob(accuracyJob, accuracyTrigger)
    scheduler.scheduleJob(weeklyAccuracyJob, weeklyAccuracyTrigger)
    scheduler.scheduleJob(monthlyAccuracyJob, monthlyAccuracyTrigger)

    logger.info("Scheduled FetchMatchesJob to run three times a day at midnight, 8 AM, and 4 PM")
    logger.info("Scheduled SendAccuracyJob to run daily at 08:30")
    logger.info("Scheduled SendWeeklyAccuracyJob to run every Monday at 08:31")
    logger.info("Scheduled SendMonthlyAccuracyJob to run on the 1st of every month at 08:32")
    logger.info("Executed FetchMatchesJob immediately upon startup")
}
