# AGENTS.md

AI-oriented repository guide for coding assistants and code-review tools.

## Documentation split

- `README.md` is the human-facing project overview for clients, recruiters, and freelance marketplaces.
- `docs/product-overview.md` is the human-facing product overview.
- `docs/ai-side.md` is the deeper prediction-pipeline note.
- `docs/hdc-deployment.md` is the operational runbook for test/prod bot containers on HDC.
- `src/main/kotlin/**/README.md` and `src/main/kotlin/**/FUNCTIONS.md` are AI-reference files for code navigation.

## Repository map

- `src/main/kotlin/Main.kt`: application bootstrap, Prometheus server startup, Quartz job wiring.
- `src/main/kotlin/FootballBot.kt`: command routing, Telegram messaging, premium flows, scheduled delivery, accuracy messages.
- `src/main/kotlin/service/`: external integrations and service-layer business logic.
- `src/main/kotlin/repository/`: SQLite and Exposed-backed persistence.
- `src/main/kotlin/dto/`: transport models, strategy configs, and API schemas.
- `src/main/resources/`: league, tag, and team metadata.
- `src/test/kotlin/`: unit tests.
- `scripts/deploy-hdc-bots.sh`: canonical test/prod bot-container deployment from a local Docker host.

## Key runtime facts

- Primary match data source: API-Football.
- Primary prediction source: local model on `http://localhost:<local.model.port>/predict` with `7007` as the default port.
- Fallback prediction source: OpenAI via `ChatGPTService`.
- Feedback upload target: `http://localhost:<local.model.port>/uploadLines` with `7007` as the default port.
- Scheduler: Quartz.
- Persistence: SQLite with Exposed.
- Observability: Prometheus HTTP exporter from `Metrics.kt`.

## HDC deployment

- Use `scripts/deploy-hdc-bots.sh` from a clean worktree.
- The script builds `linux/amd64` locally and streams the image to HDC; do not rediscover or replace this path with a server-side Docker build.
- Recreate only `betprediction-test` and `betprediction-prod` with `--no-deps`; the model and bind-mounted SQLite files are outside a normal bot deployment.
- Follow `docs/hdc-deployment.md` for verification, rollback, and database-change precautions.

## Update rules

- If you change the product promise or setup, update `README.md`.
- If you change the AI pipeline or evaluation story, update `docs/ai-side.md`.
- If you change code structure, update the closest AI-reference markdown under `src/main/kotlin/`.
- Keep claims honest: ChatGPT is currently a fallback, not the primary forecasting engine.
