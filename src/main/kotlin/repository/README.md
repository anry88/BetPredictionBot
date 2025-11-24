# Repositories and models

Repositories encapsulate database access (Exposed/SQLite) and provide CRUD/aggregation for the bot and scheduler.

## Core entities
- `Models.kt` — data classes for statistics (`Statistics`), invites (`InviteLink`, `InviteSubscriber`, `JoinRequest`), and subscriptions (`SubscriptionType`, `SubscriptionPlan`, `PremiumSubscription`).
- `MatchRepository.kt` — match storage, odds, and fixture statuses.
- `UserStatsRepository.kt` — user/command metrics, prediction accuracy, and ROI tracking.
- `PaymentRepository.kt` — payments/refunds and transaction states.
- `InviteRepository.kt` — channel/bot invite links, limits, expiry, and validation.
- `CommandUsageRepository.kt` — command usage tracking and pruning of old entries.
- `UserSettingsRepository.kt` — time zones and other per-user settings.
- `PremiumSubscriptionRepository.kt` — active subscriptions, renewals, and access checks.
- `ScheduledJobRepository.kt` — user-defined schedules created/edited from chats.
- `RefundRequestRepository.kt` — refund requests and their states.
- `MatchPollRepository.kt` — top-match polls and associated Telegram message ids.

## Update practice
1. When adding a new table, create the DAO/repository in this folder and describe it here.
2. Keep business logic out of repositories: return DTOs/models; place aggregation/filtering in services.
3. When contracts change, synchronize the relevant data classes in `Models.kt`.
