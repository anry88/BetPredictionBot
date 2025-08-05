import kotlinx.coroutines.runBlocking
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import service.HttpAPIFootballService
import service.ModelDataUploader

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

class SendWeeklyTopMatchesJob : Job {
    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        runBlocking {
            footballBot.sendWeeklyTopMatches()
        }
    }
}

class UploadModelDataJob : Job {
    private val logger = LoggerFactory.getLogger(UploadModelDataJob::class.java)

    override fun execute(context: JobExecutionContext?) {
        runBlocking {
            val status = ModelDataUploader.uploadModelData()
            if (status > 0) {
                logger.info("Model data upload finished with status: $status")
            } else {
                logger.error("Model data upload failed")
            }
        }
    }
}

class InviteLinkCleanupJob : Job {
    private val logger = LoggerFactory.getLogger(InviteLinkCleanupJob::class.java)

    override fun execute(context: JobExecutionContext?) {
        val footballBot = context!!.mergedJobDataMap["footballBot"] as FootballBot
        val channelId = context!!.mergedJobDataMap["channelChatId"] as String
        runBlocking {
            try {
                footballBot.cleanupInviteLinks(channelId)
            } catch (e: Exception) {
                logger.error("Error in InviteLinkCleanupJob", e)
            }
        }
    }
}

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)

    val telegramBotToken: String =
        Config.getProperty("telegram.bot.token") ?: throw IllegalStateException("Telegram Token not found")
    
    val channelId: String =
        Config.getProperty("strategy.channel.id") ?: throw IllegalStateException("Channel ID not found")

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
        .withSchedule(CronScheduleBuilder.cronSchedule("0 1 0,4,8,12,16,20 * * ?"))
        .build()

    val immediateTrigger = TriggerBuilder.newTrigger()
        .withIdentity("fetchMatchesImmediateTrigger", "group1")
        .startNow()
        .build()

    val updateMatchesJob = JobBuilder.newJob(UpdateMatchesJob::class.java)
        .withIdentity("updateMatchesJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val updateMatchesTrigger = TriggerBuilder.newTrigger()
        .withIdentity("updateMatchesTrigger", "group1")
        .withSchedule(
            CronScheduleBuilder.cronSchedule("0 5 * * * ?")  // На 5-й минуте каждого часа
        )
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

    // SendWeeklyTopMatchesJob setup
    val weeklyTopMatchesJob = JobBuilder.newJob(SendWeeklyTopMatchesJob::class.java)
        .withIdentity("sendWeeklyTopMatchesJob", "group1")
        .usingJobData(jobDataMap)
        .build()

    val weeklyTopMatchesTrigger = TriggerBuilder.newTrigger()
        .withIdentity("sendWeeklyTopMatchesTrigger", "group1")
        .withSchedule(CronScheduleBuilder.weeklyOnDayAndHourAndMinute(DateBuilder.MONDAY, 9, 0))
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

    // Настройка задачи очистки истекших пригласительных ссылок
    val inviteLinkCleanupJob = JobBuilder.newJob(InviteLinkCleanupJob::class.java)
        .withIdentity("inviteLinkCleanupJob", "inviteLinkGroup")
        .usingJobData(JobDataMap().apply {
            put("footballBot", footballBot)
            put("channelChatId", channelId)
        })
        .build()

    val inviteLinkCleanupTrigger = TriggerBuilder.newTrigger()
        .withIdentity("inviteLinkCleanupTrigger", "inviteLinkGroup")
        .withSchedule(SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInHours(1)
            .repeatForever())
        .build()

    // Schedule the jobs
    scheduler.scheduleJob(job, dailyTrigger)
    scheduler.scheduleJob(updateMatchesJob, updateMatchesTrigger)
    scheduler.scheduleJob(updatePastMatchesJob, updatePastMatchesTrigger)
//    scheduler.scheduleJob(updatePastMatchesJob, setOf(updatePastMatchesTrigger, immediateTrigger).toMutableSet(), true)
    scheduler.scheduleJob(updateLeaguePredictabilityJob, updateLeaguePredictabilityTrigger)
//    scheduler.scheduleJob(updateLeaguePredictabilityJob, setOf(updateLeaguePredictabilityTrigger, immediateTrigger).toMutableSet(), true)
    scheduler.scheduleJob(accuracyJob, accuracyTrigger)
    scheduler.scheduleJob(weeklyAccuracyJob, weeklyAccuracyTrigger)
    scheduler.scheduleJob(monthlyAccuracyJob, monthlyAccuracyTrigger)
    scheduler.scheduleJob(yearlyAccuracyJob, yearlyAccuracyTrigger)
    scheduler.scheduleJob(weeklyTopMatchesJob, weeklyTopMatchesTrigger)
    scheduler.scheduleJob(liveUpdateJob, liveUpdateTrigger)
    scheduler.scheduleJob(uploadModelDataJob, uploadModelDataTrigger)
//    scheduler.scheduleJob(uploadModelDataJob, setOf(uploadModelDataTrigger, immediateTrigger).toMutableSet(), true)
    scheduler.scheduleJob(inviteLinkCleanupJob, inviteLinkCleanupTrigger)

    logger.info("Scheduled FetchMatchesJob to run three times a day at midnight, 8 AM, and 4 PM")
    logger.info("Scheduled UpdateMatchesJob to run at every hour")
    logger.info("Scheduled UpdateLeaguePredictabilityJob to run daily at 08:00")
    logger.info("Scheduled SendAccuracyJob to run daily at 08:30")
    logger.info("Scheduled SendWeeklyAccuracyJob to run every Monday at 08:31")
    logger.info("Scheduled SendMonthlyAccuracyJob to run on the 1st of every month at 08:32")
    logger.info("Scheduled SendYearlyAccuracyJob to run on the 1st of January of every year at 08:33")
    logger.info("Scheduled SendWeeklyTopMatchesJob to run every Monday at 09:00")
    logger.info("Executed FetchMatchesJob immediately upon startup")
    logger.info("Executed UpdateLiveMatchesJob immediately upon startup to run every 5 minutes")
    logger.info("Executed UploadModelDataJob every monday at 1:00")
    logger.info("Scheduled InviteLinkCleanupJob to run every hour")
}
