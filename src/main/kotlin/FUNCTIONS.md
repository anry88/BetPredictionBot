# Function Guide (Root Kotlin Files)

## Config.kt
- `getProperty(key: String)`: Thin wrapper around a preloaded `config.properties` to fetch configuration values by key.

## Metrics.kt
- `startServer(port: Int)`: Boots a Prometheus HTTP exporter once with HotSpot defaults.
- `updateUserMetrics(total: Long, activeLastDay: Long)`: Pushes current user counts into gauges for scraping.

## Main.kt
- Quartz job classes (`FetchMatchesJob`, `UpdateMatchesJob`, `UpdatePastMatchesJob`, `UpdateLiveMatchesJob`, `UpdateLeaguePredictabilityJob`, `SendAccuracyJob`, `SendWeeklyAccuracyJob`, `SendMonthlyAccuracyJob`, `SendYearlyAccuracyJob`, `SendWeeklyTopMatchesJob`, `SendDailyPremiumSummaryJob`, `UploadModelDataJob`, `InviteLinkCleanupJob`, `CommandUsageCleanupJob`): Each job wraps a specific bot/service call to run on a schedule.
- `main()`: Creates the bot, exposes metrics, wires Quartz triggers for all jobs, and logs the planned cadence.

## FootballBot.kt
- Bot identity: `getBotToken()`/`getBotUsername()` return credentials for Telegram registration.
- Messaging helpers: `sendMessageAndGetId()`, `updateMessage()`, `sendMessage()`, `sendMultipartMessage()`, `deleteMatchMessages()`, `updateMatchMessages()` route text and markup to Telegram and manage stored message ids.
- Subscription and payments: `sendPremiumInvoice()`, `showSubscriptionOptions()`, `sendDailyPremiumSummary()`, `cleanupInviteLinks()` orchestrate paid features and invite hygiene.
- Prediction reporting: `sendUpcomingMatchesToTelegram()`, `updateLiveMatches()`, `sendPredictionAccuracyMessage()` (plus weekly/monthly/yearly variants), `sendWeeklyTopMatches()` format and deliver match info with model data.
- Scheduling helpers: `startScheduledJobs()`, `startPollJobs()`, `executeScheduledJob()` manage user-defined schedules and polls persisted in repositories.
- Command handlers: `onUpdateReceived()` dispatches incoming updates into handlers like `handleUpcomingMatchesCommand()`, `handleRecentMatchesCommand()`, `handleGetAccuracyCommand()`, `handleGetStrategyEfficiencyCommand()`, `handleMatchDetailsCommand()`, and job setup flows (`handleSchedule*`, `handleConfirmJob()`, `handleCancelJob()`).
- Formatting and tagging: Helpers (`formatMatchInfo*`, `buildMatchMessages()`, `getTags()`, `formatLeaguePredictabilityData()` etc.) generate human-friendly text with league context, tags, and accuracy stats.
- Strategy and stats: `updateLeaguePredictability()`, `sendAccuracyStats()`, and filtering helpers (`isTopMatch()`, `isPremiumMatch()`, `isMatchFitsStrategy()`) apply strategy rules before messaging.
