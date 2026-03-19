# AI Reference: Bot helpers

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

## commands/AdminCommands.kt
- Admin-only actions for scheduling and management are defined as extension handlers invoked by `FootballBot` (command parsing lives there).

## commands/GeneralCommands.kt
- Shared command helpers for regular users (e.g., timezone setup, toggles) used by `FootballBot` when parsing updates.

## formatter/MessageFormatter.kt
- Presentation helpers that format match lines, accuracy stats, and premium highlights for Telegram output.

## invites/InviteHandler.kt
- Invite link utilities used by `FootballBot` to manage join requests and channel access via `InviteRepository`.
