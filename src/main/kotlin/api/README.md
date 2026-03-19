# AI Reference: API layer

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

The `api/` layer describes external interfaces for Telegram and ChatGPT.

## Files
- `TelegramService.kt` — interface implemented by `FootballBot` for sending messages/documents/polls to Telegram (abstraction used by services).
- `ChatGPTApi.kt` — request/response models for the ChatGPT API plus the `api` client with basic configuration.

## Usage
- Services call `TelegramService` methods instead of touching `TelegramBotsApi` directly to simplify testing and decouple responsibilities.
- When adding a new LLM provider or Telegram call, extend the models in this folder and implement them inside the bot/services.
