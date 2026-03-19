# BetPredictionBot Case Study

## Task

Build an AI-powered Telegram product around football predictions, not just a demo bot. The target outcome was a backend that could:

- ingest real external data,
- generate predictions automatically,
- separate free and premium value,
- schedule content without manual work,
- and provide enough analytics to evaluate quality over time.

## Architecture

- `API-Football` provides fixtures and odds.
- `HttpLocalModelService` is the primary prediction source and returns probabilities plus expected goals.
- `ChatGPTService` acts as a fallback when the local model is unavailable.
- `StrategyService` filters premium picks by probability, odds, and expected-goal rules.
- `FootballBot` handles Telegram UX, command routing, paid access, and scheduled delivery.
- `SQLite + Exposed` persist matches, subscriptions, invites, refunds, scheduled jobs, and stats.
- `Quartz` drives recurring fetch/update/reporting jobs.
- `Prometheus` exposes operational metrics.

## Problems solved

- `Prediction reliability`: the bot does not depend on one source only. It prefers the local model and falls back to ChatGPT.
- `Monetization`: premium access is wired into the product with Telegram Stars, invite links, quotas, and refund handling.
- `Operational automation`: recurring jobs keep matches, summaries, and evaluation reports current without manual admin work.
- `Evaluation`: match outcomes are written back into storage, then reused for accuracy and ROI reporting and model-data export.
- `User experience`: users can request forecasts on demand or schedule them in their own time zone.

## Result

The repository demonstrates a shippable AI/backend product shape:

- prediction ingestion,
- hybrid model integration,
- Telegram delivery,
- premium monetization,
- scheduled automation,
- and observability.

It is intentionally stronger as a freelance portfolio piece than a simple "chatbot repo" because it shows product thinking and backend ownership end to end.

## What this shows a client

- Ability to build an AI-powered Telegram product, not only a prompt wrapper.
- Ability to combine deterministic data pipelines with model-driven decision support.
- Ability to ship monetization-ready features such as subscriptions, paid access, and refund workflows.
- Ability to operate a backend with scheduling, persistence, and monitoring.
- Ability to design feedback loops so a model-backed product can improve over time.

## How to position it on freelance marketplaces

Use this repository as:

- `AI-powered Telegram product`
- `data pipeline + model integration`
- `scheduled backend automation`
- `monetization-ready bot`
- `analytics / observability aware product`
