# AI Reference: Repository layer functions

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

## MatchRepository.kt
- League table management: `appendRows()`, `updateMatchResult()`, and helpers keep per-league tables in sync; `createLeagueTableIfNeeded`/`addMissingColumnsForLeague` are invoked upstream when adding rows.
- Prediction fields: `updateMatchPredictions()`, `updateMatchOdds()`, `updateMatchMessageId()`, `updateMatchStrategyMessageId()`, `updateMatchDatetime()`, and `updateMatchTeams()` keep model outputs and Telegram ids current.
- Queries: `getUpcomingMatches*()`, `getOngoingMatches()`, `getMatchesAroundNowWithoutResult()`, `getLastMatches*()`, `getMatchesBy*()` fetch slices for messaging, reconciliation, and stats.
- Statistics: `updateLeaguePredictability()`, `getLeaguePredictabilityData()`, `getStatisticsForPeriod()`, `getDetailedStatisticsForPeriod()`, `getTopPremiumRoiMatchesForPeriod()` aggregate ROI/accuracy metrics and team match counts.
- Utilities: `matchExists()`, `deleteMatchByFixtureId()`, `getMatchInfoByFixtureId()` (global or scoped to one league), and helpers that normalize league naming and team counts.

## ScheduledJobRepository.kt
- Persistence for user-defined jobs: `addJob()`, `getJobsByUser()`, `updateJob()`, `deleteJob()` manage custom schedules; `getDueJobs()` returns runnable jobs and `updateNextRun()` bumps them forward.

## CommandUsageRepository.kt
- Rate limiting: `incrementUsage()` tracks command usage per month and returns the new count; `getUsage()`/`getTotalUsage()` read counters; `clearOldEntries()` prunes stale rows.

## MatchPollRepository.kt
- Poll lifecycle: `addPoll()`, `markPollPosted()`, `markPollClosed()`, `getPendingPolls()`, and `getOpenPostedPolls()` store and reconcile poll metadata; `existsPollForDate()` prevents duplicates; `getPollByFixtureId()` retrieves poll details.

## UserStatsRepository.kt
- User counters: `addUserActivity()` logs interactions with names; `getUserCount()` and `getActiveUserCountLast24Hours()` provide metrics for Prometheus and messaging.

## UserSettingsRepository.kt
- Preferences: `setTimezone()` stores per-user time zones; `getTimezone()` retrieves them for scheduling and message formatting.

## PaymentRepository.kt
- Payment history: `addPayment()` persists successful Telegram payments; `getPayment()`/`getLastPayment()` fetch stored records for validation or refund processing.

## PremiumSubscriptionRepository.kt
- Subscription states: `addOrUpdateSubscription()` and `revokeSubscription()` adjust entitlement windows; `getSubscription()` and `isActive()` check whether a user currently holds access for a given plan.

## RefundRequestRepository.kt
- Refund workflow: `createRequest()` logs a new refund ask; `updateStatus()` and `saveUserComment()` track moderation decisions; `getRequest()`/`getLatestByPaymentId()` fetch status for user-visible updates.

## InviteRepository.kt
- Invite links: `createInviteLink()` issues tracked links; `approveJoinRequest()` and `addJoinRequest()` record membership intents; `cleanupExpiredSubscribers()` removes expired access.
- Lookups: `getPendingJoinRequests()`, `getSubscribersForInviteLink()`, `getExpiredInviteLinks()`, `getActiveInviteLinksWithRemainingSlots()`, and `getLatestLinkForUser()` return link/user relationships for bot flows.
- Maintenance: `removeInviteSubscriber()`, `removeInviteLink()`, `deactivateInviteLink()`, `updateInviteLinkExpiry()`, `removeUserFromChannel()`, `getInviteLinkId()`, `getSubscriberCount()`/`getMaxSubscribersForLink()` handle cleanup and capacity checks.
