# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# homeAssistant bot
cd homeAssistant && npm install
cd homeAssistant && npm run dev      # ts-node src/app.ts (개발)
cd homeAssistant && npm run build    # tsc → dist/
cd homeAssistant && npm start        # node dist/app.js (프로덕션)
cd homeAssistant && npm test         # jest (ts-jest)
cd homeAssistant && npm run typecheck  # tsc --noEmit

# myStar bot (token-reward bot)
cd myStar && npm install
cd myStar && node app.js
```

## Environment Setup

**`homeAssistant/.env`** (copy from `.env.example`):
```
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
SLACK_APP_TOKEN=xapp-...
ANTHROPIC_API_KEY=sk-ant-...
```

**`myStar/.env`**:
```
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
SLACK_APP_TOKEN=xapp-...
```

## Architecture

The repo holds multiple Slack bots, each in its own subdirectory. Each bot is independently runnable with its own `package.json` and `.env`.

**`myStar/app.js`** is the sole application file. It uses `@slack/bolt` v4 in **Socket Mode** (no HTTP server needed — the app connects out to Slack via WebSocket using `SLACK_APP_TOKEN`).

### Bot behavior

- **`/test` command**: Acknowledges and responds in-channel with a static message.
- **Message listener**: For every non-bot, non-edited message that contains at least one user mention (`<@USER_ID>`) and at least one `:coin:` or `:token_icon:` emoji, sends a DM to each mentioned user informing them of the token award. The sender's user ID is included in the DM.

### homeAssistant — Family Home Server Bot

TypeScript + class-based architecture. Entry point: `src/app.ts`.

- **Architecture**: `ICommand` interface → `BaseCommand` (abstract, DB constructor injection) → `SharedableCommand` / `UpsertCommand` → concrete command classes in `src/commands/`.
- **Public API**: Each command class exposes only `register(app: App): void`. All business logic is `private`.
- **DB**: SQLite via `better-sqlite3` (sync API). File at `db/homeAssistant.sqlite`. Schema in `src/db/migrate.ts` (9 tables, idempotent). DB injected via constructor.
- **Claude API**: Used only for date parsing in `/일정` and `/일정목록`. `src/nlp/claudeClient.ts` exports `parseDate(text)` and `parseDateRange(text)`.
- **`response_type`**: `ephemeral` for personal queries, `in_channel` for shared/family data.
- **Slash commands** must be pre-registered in the Slack app config (api.slack.com) — 21 commands total. See `HelpCommand.ts` for full list.
- **Personal vs shared data**: `is_shared = 1` rows are visible to all family members; personal rows are filtered by `user_id = command.user_id`.
- **Tests**: `tests/commands/*.test.ts` — MockApp pattern (tests via `app.trigger('/command', 'text')`). 65 tests total.

### myStar — Token Reward Bot

- **`/test` command**: Responds in-channel with a static message.
- **Message listener**: Detects user mentions + `:coin:`/`:token_icon:` emojis and DMs the mentioned users.

### Key Slack Bolt patterns

- `await ack()` must be called within 3 seconds of receiving a command/action or Slack will show an error.
- `respond()` replies to the originating channel/conversation.
- `client.chat.postMessage({ channel: userId, ... })` opens a DM by passing a user ID as the channel.
- `socketMode: true` in the App constructor enables WebSocket mode; `appToken` is required for this.
