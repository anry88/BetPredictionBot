# Bot logic

Components under `bot/` help `FootballBot` format messages and handle commands.

## Files
- `commands/GeneralCommands.kt` — public commands (/start, /help, subscriptions, match queries, timezone setup, etc.).
- `commands/AdminCommands.kt` — administrative commands: DB export, user stats, refund management, match/model refresh triggers.
- `formatter/MessageFormatter.kt` — builds text for matches, stats, and notifications, aligning premium outcomes with strategy selection and adjusting predicted scores using expected goals when available.
- `invites/InviteHandler.kt` — invite link workflows for channels/bot, limit checks, creation/cleanup, and validation of requested links.

## How to extend
- New commands: add them to the relevant file, register them in `FootballBot`, and update this README.
- New formatters: group helpers by message type (matches, finance, system notifications) and reuse `TelegramService`.
- Invites: keep business rules here and database operations in `repository/InviteRepository.kt`.
