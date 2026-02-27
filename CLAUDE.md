# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# homeAssistant bot
cd homeAssistant && npm install
cd homeAssistant && node app.js   # or: npm start

# myStar bot (token-reward bot)
cd myStar && npm install
cd myStar && node app.js
```

No build step or test suite is configured.

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

Slash-command based bot. Each command module in `commands/` exports `register(app, db)` and registers its own `app.command()` handlers. All modules are loaded in `app.js`.

- **DB**: SQLite via `better-sqlite3` (sync API). File at `db/homeAssistant.sqlite`, auto-created on first run. Schema in `db/migrate.js` (9 tables, idempotent).
- **Claude API**: Used only for date parsing in `/일정` and `/일정목록`. `nlp/claudeClient.js` exports `parseDate(text)` and `parseDateRange(text)`.
- **`response_type`**: `ephemeral` for personal queries, `in_channel` for shared/family data.
- **Slash commands** must be pre-registered in the Slack app config (api.slack.com) — 21 commands total. See `/도움말` handler for full list.
- **Personal vs shared data**: `is_shared = 1` rows are visible to all family members; personal rows are filtered by `user_id = command.user_id`.

### myStar — Token Reward Bot

- **`/test` command**: Responds in-channel with a static message.
- **Message listener**: Detects user mentions + `:coin:`/`:token_icon:` emojis and DMs the mentioned users.

### Key Slack Bolt patterns

- `await ack()` must be called within 3 seconds of receiving a command/action or Slack will show an error.
- `respond()` replies to the originating channel/conversation.
- `client.chat.postMessage({ channel: userId, ... })` opens a DM by passing a user ID as the channel.
- `socketMode: true` in the App constructor enables WebSocket mode; `appToken` is required for this.
