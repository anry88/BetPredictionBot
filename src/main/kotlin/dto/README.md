# DTOs and configurations

This directory contains transport models used for data exchange between layers and during API serialization/deserialization.

## Key files
- `ApiFootballModels.kt` — response models for the external football API (leagues, fixtures, team stats, odds). Used by `HttpAPIFootballService`.
- `MatchInfo.kt` and `JsonlMatch.kt` — aggregated match data: date/time, teams, odds, model/ChatGPT predictions, actual outcome.
- `LeagueConfig.kt` and `TagsData.kt` — league and tag configs loaded from resources (`/leagues.json`, `/tags.json`).
- `OutcomeStrategyConfig.kt` and `LeagueStats.kt`/`PeriodStats.kt` — strategy parameters plus aggregated stats by league/period.
- `OddsInfo.kt` and `BookmakerInfo.kt` — odds descriptors and bookmaker details used when requesting odds.

## Working with DTOs
- Keep DTOs flat and serializable (kotlinx.serialization). Place processing logic in services.
- When external contracts change, align fields here and add sample payloads in resources to validate parsing.
