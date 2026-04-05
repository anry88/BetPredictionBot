# AI Side

This note explains the current prediction pipeline in product terms and clarifies what is already implemented versus what is still a next-step opportunity.

## Data sources

- `API-Football` supplies upcoming fixtures, historical results, and odds data.
- `Telegram` supplies user actions, premium access events, and payment/refund signals.
- `SQLite` stores historical labeled outcomes and enriched prediction records for later evaluation.

## Where the local model is used

- `HttpLocalModelService` calls `http://localhost:7007/predict`.
- The local model is the primary prediction source.
- It returns win probabilities, expected goals, calibration-related fields, and optional match-count context for both teams.
- Premium strategy filtering depends on these model outputs, which makes the local model central to the strongest product features.
- The current premium filter is intentionally narrow: draw picks require low xG imbalance/total, while away-win picks also require a clear away-minus-home xG edge in addition to odds/probability bounds.

## Where ChatGPT is used

- `ChatGPTService` is a fallback, not the primary engine.
- It is called only when the local model does not produce a usable prediction.
- The response is parsed into the same `MatchInfo` DTO so the rest of the pipeline can keep working.
- This is useful operationally because the bot can still produce a prediction even if the local model is unavailable.

## How accuracy is calculated today

Current evaluation in the repository is outcome-focused:

- `accuracy`: percent of correct predicted outcomes over a selected period,
- `ROI`: return on a fixed notional stake using stored odds,
- `strategy accuracy / ROI`: the same metrics for premium-strategy picks only,
- `breakdown by outcome`: home-win, draw, and away-win performance,
- `league predictability`: per-league aggregated stats stored in the database.

Daily, weekly, monthly, and yearly accuracy messages are already scheduled in `Main.kt`.

## How accuracy is planned to evolve

The current code already proves the reporting loop. The natural next upgrades are:

- calibration metrics such as Brier score or log loss,
- separate reporting for local-model predictions versus ChatGPT fallbacks,
- drift detection by league or season segment,
- dashboarding calibration and ROI together instead of only message-based reports.

## Feedback loop

The repository already contains a real feedback loop:

1. New matches are fetched and predictions are stored.
2. After matches finish, actual outcomes are pulled back into SQLite.
3. Accuracy and ROI reports reuse those labeled rows.
4. `ModelDataUploader` exports completed matches to JSONL.
5. That JSONL is uploaded to the local model service through `http://localhost:7007/uploadLines`.

The important nuance: this repo prepares and ships labeled data, but the retraining logic itself lives on the local model service side, not inside this Kotlin codebase.
