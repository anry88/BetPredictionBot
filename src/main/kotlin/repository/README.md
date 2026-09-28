# AI Reference: Repositories and models

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

Repositories encapsulate database access (Exposed/SQLite) and provide CRUD/aggregation for the bot and scheduler.

## Core entities
- `Models.kt` — data classes for statistics (`Statistics`), invites (`InviteLink`, `InviteSubscriber`, `JoinRequest`), and subscriptions (`SubscriptionType`, `SubscriptionPlan`, `PremiumSubscription`).
- `MatchRepository.kt` — match storage, odds, and fixture statuses.
- `UserStatsRepository.kt` — user/command metrics, prediction accuracy, and ROI tracking.
- `PaymentRepository.kt` — payments/refunds and transaction states.
- `InviteRepository.kt` — channel/bot invite links, limits, expiry, and validation.
- `CommandUsageRepository.kt` — retained command usage history and pruning; no active command limit depends on it.
- `UserSettingsRepository.kt` — time zones and other per-user settings.
- `PremiumSubscriptionRepository.kt` — active subscriptions, renewals, and access checks.
- `ScheduledJobRepository.kt` — user-defined schedules created/edited from chats.
- `RefundRequestRepository.kt` — refund requests and their states.
- `MatchPollRepository.kt` — top-match polls and associated Telegram message ids.
- `DigestRepository.kt` — marks fixtures already announced in an aggregated league-summary digest so twice-daily posts never repeat matches.

## Update practice
1. When adding a new table, create the DAO/repository in this folder and describe it here.
2. Keep business logic out of repositories: return DTOs/models; place aggregation/filtering in services.
3. When contracts change, synchronize the relevant data classes in `Models.kt`.
