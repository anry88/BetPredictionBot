# AI Reference: Service layer

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

Services encapsulate network calls, business rules, and repository interactions. They are consumed by the bot and the Quartz scheduler.

## Files and responsibilities
- `HttpAPIFootballService.kt` — talks to the external football API to collect upcoming/past matches, refresh odds and statuses, pull live data, and fetch fixture odds.
- `HttpLocalModelService.kt` — queries the local ML service for outcome probabilities and expected goals.
- `ChatGPTService.kt` and `HttpChatGPTService.kt` — build ChatGPT requests, parse responses into `MatchInfo`, and log errors.
- `StrategyService.kt` — evaluates whether a match satisfies strategy parameters (`OutcomeStrategyConfig`), including premium filters and model probabilities.
- `StarsPaymentService.kt` — processes Telegram Stars payments/refunds, updates subscriptions, and notifies users.
- `DatabaseService.kt` — single entry point for database initialization plus access to repositories (users, matches, payments, polls, subscriptions, commands, etc.).
- `ModelDataUploader.kt` — aggregates model data and uploads it for the `UploadModelDataJob` Quartz task (scheduled for Monday/Wednesday/Friday at 03:00).

## Usage
Services live under the `service` scope. Call them directly from bot commands, Quartz jobs, or other services. When adding a new integration:
1. Create a file in `service/` with isolated call/handling logic.
2. Work through DTOs and repositories rather than calling the Telegram API directly.
3. Document public functions and dependencies in the new file.
