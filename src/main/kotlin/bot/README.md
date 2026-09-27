# AI Reference: Bot logic

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

Components under `bot/` help `FootballBot` format messages and handle commands.

## Files
- `commands/GeneralCommands.kt` — shared command responses; subscription/link helpers are invoked only from admin-gated routing in `FootballBot`.
- `commands/AdminCommands.kt` — administrative commands: DB export, user stats, refund management, match/model refresh triggers.
- `formatter/MessageFormatter.kt` — builds public, private-strategy, and direct-message match text. Direct replies expose probabilities, expected goals, and odds without strategy eligibility analysis or premium labels.
- `invites/InviteHandler.kt` — invite link workflows for channels/bot, limit checks, creation/cleanup, and validation of requested links.

## How to extend
- New commands: add them to the relevant file, register them in `FootballBot`, and update this README.
- New formatters: group helpers by message type (matches, finance, system notifications) and reuse `TelegramService`.
- Invites: keep business rules here and database operations in `repository/InviteRepository.kt`.
