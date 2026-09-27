# AI Reference: Bot helpers

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

## commands/AdminCommands.kt
- Admin-only actions for scheduling and management are defined as extension handlers invoked by `FootballBot` (command parsing lives there).

## commands/GeneralCommands.kt
- Shared command helpers used by `FootballBot`; premium subscription/link helpers remain available but are routed only for the admin chat.

## formatter/MessageFormatter.kt
- Presentation helpers for public, private-strategy, and direct match messages. Direct messages intentionally omit premium labels and extended strategy checks.

## invites/InviteHandler.kt
- Invite link utilities used by `FootballBot` to manage join requests and channel access via `InviteRepository`.
