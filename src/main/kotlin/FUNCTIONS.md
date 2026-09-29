# AI Reference: Root Kotlin Files

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

## Config.kt
- `getProperty(key: String)`: Thin wrapper around a preloaded `config.properties` to fetch configuration values by key.
- `reload()`, `getBooleanProperty(...)`, and `getIntProperty(...)`: Helpers for reloading config during tests and reading typed settings.
- `isTestEnvironment()`, `isModelDataUploadEnabled()`, `getLocalModelPort()`, `getLocalModelBaseUrl()`, and `getUploadModelDataCron()`: Runtime helpers for environment-aware scheduling and local-model endpoint construction.

## Metrics.kt
- `startServer(port: Int)`: Boots a Prometheus HTTP exporter once with HotSpot defaults.
- `updateUserMetrics(total: Long, activeLastDay: Long)`: Pushes current user counts into gauges for scraping.

## TagNormalizer.kt
- `toTag(name)`: Builds stable Telegram hashtags from team names, transliterating diacritics to ASCII before removing non-hashtag characters.

## NeutralVenuePolicy.kt
- `isNeutralVenue(...)`: Marks FIFA World Cup fixtures and cup finals as neutral-ground matches so model calls and JSONL feedback can skip home-field advantage.

## Main.kt
- Quartz job classes (`FetchMatchesJob`, `UpdateMatchesJob`, `UpdatePastMatchesJob`, `UpdateLiveMatchesJob`, `UpdateLeaguePredictabilityJob`, `SendAccuracyJob`, `SendWeeklyAccuracyJob`, `SendMonthlyAccuracyJob`, `SendYearlyAccuracyJob`, `UploadModelDataJob`, `InviteLinkCleanupJob`, `CommandUsageCleanupJob`): Each job wraps a specific bot/service call to run on a schedule. Upcoming public summaries run at 08:05 and 20:05 server time over a 16h window.
- `main()`: Creates the bot, exposes metrics, wires Quartz triggers for all jobs, and schedules model-data uploads only outside test mode.

## FootballBot.kt
- Bot identity: `getBotToken()`/`getBotUsername()` return credentials for Telegram registration.
- Messaging helpers: `sendMessageAndGetId()`, `updateMessage()`, `sendMessage()`, `sendMultipartMessage()`, `deleteMatchMessages()`, `updateMatchMessages()` route text and markup to Telegram and manage stored message ids.
- Subscription and payments: `sendPremiumInvoice()`, `showSubscriptionOptions()`, and `cleanupInviteLinks()` retain paid features and invite hygiene behind admin-only commands.
- Prediction reporting: `sendUpcomingMatchesToTelegram()` publishes league/count digests in chunks of ten buttons while continuing quiet strategy-channel delivery; announced fixtures are recorded in `digest_posts` so later runs only pick unposted matches; the 10-minute `updateLiveMatches()` job refreshes recent digests with finished/total progress and ✅/❌ per finished match. Live tracking (`getOngoingMatches()`) includes digest fixtures so results and polls finalize promptly.
- Scheduling helpers: `startScheduledJobs()`, `startPollJobs()`, `executeScheduledJob()` manage user-defined schedules and polls persisted in repositories.
- Command handlers: `onUpdateReceived()` dispatches unlimited public match-detail commands and admin-only `/subscribe`, `/freepremiumlinks`, `/premiummatches`, and `/premiumrecent` flows, plus job setup handlers.
- Formatting and tagging: Helpers (`formatMatchInfo*`, `buildMatchMessages()`, `getTags()`, `formatLeaguePredictabilityData()` etc.) generate human-friendly text with league context, tags, and accuracy stats.
- Strategy and stats: `updateLeaguePredictability()`, `sendAccuracyStats()`, and filtering helpers (`isTopMatch()`, `isPremiumMatch()`, `isMatchFitsStrategy()`) apply strategy rules before messaging.
