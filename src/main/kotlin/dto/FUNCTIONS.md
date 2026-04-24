# AI Reference: Data model notes

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

These files are data-only; they define the shapes used by services and repositories.

- `ApiFootballModels.kt`: Serialization models mirroring API-Football responses (fixtures, leagues, odds, teams, scores).
- `MatchInfo.kt`: Core match DTO exchanged between services, repositories, and bot formatting (ids, datetime, predicted/actual results, prediction timestamp, odds, Telegram message references, and model probabilities).
- `JsonlMatch.kt`: Minimal match projection for exporting training data and prediction metadata to the local model.
- `PeriodStats.kt`, `LeagueStats.kt`, `OutcomeStrategyConfig.kt`, `TagsData.kt`, `LeagueConfig.kt`, `OddsInfo.kt`, `BookmakerInfo.kt`: Support structures for stats aggregation, strategy filtering, tagging, league metadata, and bookmaker odds.
