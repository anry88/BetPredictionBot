# AI Reference: API layer functions

> AI-oriented reference file for code assistants and code review tools. Human-facing project overview lives in the repository root `README.md`.

## ChatGPTApi.kt
- `getChatGPTRequest(request: ChatGPTRequest)`: Retrofit endpoint for OpenAI chat completions.
- Data shapes: `ChatGPTRequest`, `Message`, `ChatGPTResponse`, and `Choice` mirror the chat-completions contract.

## TelegramService.kt
- `sendMessageAndGetId(...)`: Abstraction for sending a Telegram message with optional inline keyboard and getting its id.
- `updateMessage(...)`: Updates existing Telegram messages with optional markup.
