# BetPredictionBot Product Overview

`BetPredictionBot` is a production-style Telegram bot for football predictions. It combines external sports data, a local prediction model, ChatGPT fallback logic, premium access flows, scheduled background jobs, and operational metrics in one Kotlin codebase.

The goal of the project was not to make a thin chat wrapper, but to build a working product that can continuously ingest data, generate forecasts, deliver them to users, evaluate outcomes, and support paid access.

## Product scope

The bot includes:

- match ingestion from API-Football,
- prediction generation through a local model,
- fallback prediction generation through ChatGPT,
- free and premium delivery paths in Telegram,
- subscription and invite-link flows,
- refund handling,
- scheduled reporting and maintenance jobs,
- accuracy and ROI tracking,
- Prometheus metrics for observability.

## Architecture

- `API-Football` provides fixtures, match status updates, and bookmaker odds.
- `HttpLocalModelService` is the primary prediction integration and returns outcome probabilities plus expected goals.
- `ChatGPTService` is used as a fallback when the local model does not return a usable prediction.
- `StrategyService` filters matches for premium delivery based on probabilities, odds, and expected-goal constraints.
- `FootballBot` owns Telegram interactions: commands, messaging, paid flows, invite logic, and scheduled user-facing delivery.
- `SQLite + Exposed` store matches, subscriptions, payments, refunds, polls, jobs, and user settings.
- `Quartz` runs recurring fetch, update, cleanup, and reporting jobs.
- `Prometheus` exposes runtime counters and gauges.

## Core flows

### Prediction flow

1. The scheduler loads upcoming matches from API-Football.
2. New matches are stored in SQLite.
3. The bot requests a prediction from the local model.
4. If that fails, it retries through ChatGPT.
5. Predictions and odds are persisted and then formatted for Telegram delivery.

### Premium flow

1. The bot exposes paid plans through Telegram Stars.
2. Premium access is stored in the database.
3. Personal invite links and feature access are granted after payment.
4. Refund requests can be created, reviewed, and processed through the bot/admin workflow.

### Feedback and evaluation flow

1. Finished matches are updated with actual results.
2. Historical records are reused for accuracy and ROI reporting.
3. Completed matches are exported to JSONL.
4. That JSONL is uploaded back to the local model service as fresh labeled data.

## Operational model

The repository is structured around continuous background work, not only on-demand commands:

- fetching new fixtures every few hours,
- updating recent and live matches,
- recalculating league predictability,
- sending daily/weekly/monthly/yearly accuracy summaries,
- sending premium summaries,
- uploading model data on schedule,
- cleaning invite links and old usage records,
- executing user-defined scheduled tasks in stored time zones.

## Why this repository matters

This project shows a complete AI-backed product surface:

- external data pipeline,
- model integration with fallback behavior,
- production-like bot delivery,
- monetization-ready flows,
- persistent storage,
- evaluation loop,
- and observability.

That is the main value of the repository: it documents a working system with real product concerns, not only an isolated prediction script or an LLM wrapper.
