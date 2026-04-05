# AI Reference: Service layer functions

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

## HttpAPIFootballService.kt
- `fetchMatches()`: Pulls near-term fixtures from API-Football, persists new matches, enriches with predictions/odds, and skips duplicates.
- `getModelBasedLeaguesFromConfig()`: Returns leagues flagged for premium model selection in `leagues.json`.
- `updatePastMatches()`: Refreshes completed fixtures with actual outcomes and scores, cleaning stale entries when predictions fail.
- `getLiveMatchInfo(fixtureId)` / `getFixtureInfo(fixtureId)`: Fetches live or static fixture snapshots for formatting and result tracking.
- `getOddsForFixture(...)`: Pulls bookmaker odds for a given fixture and outcome, preferring configured bookmakers.
- `getPastMatches(...)` / `getUpcomingMatches(...)`: Low-level fetchers for fixtures in a date window used by higher-level flows.

## HttpLocalModelService.kt
- `getModelPrediction(...)`: Calls the local model HTTP endpoint on the configured localhost port to score a fixture and map results into `MatchInfo`.

## ChatGPTService.kt
- `getMatchPrediction(matchInfo)`: Builds a chat prompt for a single fixture and parses the ChatGPT response into `MatchInfo`.
- `parseSingleMatchInfo(text, fixtureId)`: Regex-based extractor that validates and maps ChatGPT output fields.

## HttpChatGPTService.kt
- `createHttpClient(apiKey)`: Configures the OkHttp client with auth headers and generous timeouts for OpenAI traffic.
- `api`: Retrofit-backed `ChatGPTApi` instance shared by ChatGPTService.

## StrategyService.kt
- `isMatchFitsStrategy(match, config)`: Applies ROI/accuracy strategy thresholds from `outcomeStrategyConfigs` to filter premium picks.

## StarsPaymentService.kt
- `getPrice(plan)`: Returns plan cost in Telegram Stars.
- `sendPremiumInvoice(chatId, plan)`: Sends a formatted invoice via `FootballBot` with pay button for the given plan.
- `refundStars(userId, telegramPaymentChargeId)`: Initiates a refund for a payment identified by Telegram's charge id.

## ModelDataUploader.kt
- `uploadModelData()`: Extracts recent matches, writes them to JSONL, posts to the local model API, and returns the status code in both prod and test environments.
- `createJsonlFileForModel(matches)` / `uploadJsonlToLocalModel(file)`: Helpers that serialize matches and perform the HTTP upload.

## DatabaseService.kt
- `initDatabase(dbPath)` / `execSql(sql)`: Initialize and execute migrations against the SQLite store.
- `createLeagueTableIfNeeded(tableName)` / `addMissingColumnsForLeague(tableName)`: Ensure per-league match tables exist with required columns.
- `addColumnIfNotExists(tableName, columnName, columnDefinition)`: Utility invoked by migrations to backfill schema gaps.
- Service singletons: `matches`, `jobs`, `users`, `settings`, `payments`, `subscriptions`, `refundRequests`, `invites`, `commandUsage`, `matchPolls` expose repository instances prewired to the shared database.
