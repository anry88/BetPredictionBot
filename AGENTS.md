# AGENTS.md

AI-oriented repository guide for coding assistants and code-review tools.

## Documentation split

- `README.md` is the human-facing project overview for clients, recruiters, and freelance marketplaces.
- `docs/case-study.md` is the human-facing case study.
- `docs/ai-side.md` is the deeper prediction-pipeline note.
- `src/main/kotlin/**/README.md` and `src/main/kotlin/**/FUNCTIONS.md` are AI-reference files for code navigation.

## Repository map

- `src/main/kotlin/Main.kt`: application bootstrap, Prometheus server startup, Quartz job wiring.
- `src/main/kotlin/FootballBot.kt`: command routing, Telegram messaging, premium flows, scheduled delivery, accuracy messages.
- `src/main/kotlin/service/`: external integrations and service-layer business logic.
- `src/main/kotlin/repository/`: SQLite and Exposed-backed persistence.
- `src/main/kotlin/dto/`: transport models, strategy configs, and API schemas.
- `src/main/resources/`: league, tag, and team metadata.
- `src/test/kotlin/`: unit tests.

## Key runtime facts

- Primary match data source: API-Football.
- Primary prediction source: local model on `http://localhost:7007/predict`.
- Fallback prediction source: OpenAI via `ChatGPTService`.
- Feedback upload target: `http://localhost:7007/uploadLines`.
- Scheduler: Quartz.
- Persistence: SQLite with Exposed.
- Observability: Prometheus HTTP exporter from `Metrics.kt`.

## Update rules

- If you change the product promise or setup, update `README.md`.
- If you change the AI pipeline or evaluation story, update `docs/ai-side.md`.
- If you change code structure, update the closest AI-reference markdown under `src/main/kotlin/`.
- Keep claims honest: ChatGPT is currently a fallback, not the primary forecasting engine.
