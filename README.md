# BetPredictionBot

Telegram bot for football predictions. It aggregates match data, prepares recommendations, and delivers updates via chat. The project is written in Kotlin, uses Quartz for scheduling, and Gradle for builds.

## Architecture overview
- **Inputs**: HTTP requests to an external football API, a local model, and ChatGPT; configuration files such as `config.properties` and resources under `src/main/resources`.
- **Bot**: `FootballBot` handles Telegram updates, commands, and payments while orchestrating broadcasts and data refreshes.
- **Services**: the `service/` layer encapsulates network calls, database updates, model integrations, and payment flows.
- **Persistence**: `repository/` manages SQLite tables via Exposed along with domain models and subscription state.
- **Scheduler**: `Main.kt` boots the bot, exposes Prometheus metrics, and registers recurring Quartz jobs for match updates, accuracy messages, premium summaries, model data uploads, and cleanup tasks.
- **Formatters/commands**: `bot/` provides command handlers, message formatting, and invite management.

## File map
- `README.md` — this overview.
- `src/main/kotlin/Main.kt` — entry point that registers the bot and Quartz jobs.
- `src/main/kotlin/FootballBot.kt` — Telegram logic, command routing, and service wiring.
- `src/main/kotlin/service/` — HTTP/business logic (see [service/README.md](src/main/kotlin/service/README.md)).
- `src/main/kotlin/repository/` — database tables, models, and repositories (see [repository/README.md](src/main/kotlin/repository/README.md)).
- `src/main/kotlin/api/` — Telegram/ChatGPT interfaces (see [api/README.md](src/main/kotlin/api/README.md)).
- `src/main/kotlin/dto/` — transport models and configs (see [dto/README.md](src/main/kotlin/dto/README.md)).
- `src/main/kotlin/bot/` — commands, formatters, and invite logic (see [bot/README.md](src/main/kotlin/bot/README.md)).
- `src/main/resources/` — league configs, team metadata, and message templates.

## Data flow (compressed)
1. The scheduler (`Main.kt`) calls `HttpAPIFootballService` to load upcoming/past matches and refresh odds/statuses.
2. Results and statistics are persisted via `DatabaseService` repositories.
3. `FootballBot` reacts to commands or schedules to select matches/strategies through `StrategyService`, formats messages with `MessageFormatter`, and sends them through the Telegram API.
4. `UploadModelDataJob` triggers `ModelDataUploader` to aggregate model outputs and ship them on the configured cron cadence.
5. Prometheus metrics are exposed from `Metrics` and updated after user/message operations.

## Running and checks
- **Configuration**: create `config.properties` with the keys used in `Config` (bot token, chat ids, API keys, etc.).
- **Build/run**: `./gradlew run` starts the bot, metrics, and Quartz scheduler.
- **Tests**: `./gradlew test` for any available unit tests.

## Contributing changes
- New business logic: place it in a dedicated service and describe it in `service/README.md`.
- New tables/models: add a data class in `repository/Models.kt`, create the table/repository, and document it in `repository/README.md`.
- Broadcasts/commands: update code in `bot/` and refresh the relevant section in `bot/README.md`.
- External data sources or DTOs: synchronize schemas in `dto/README.md` and note the API used.
