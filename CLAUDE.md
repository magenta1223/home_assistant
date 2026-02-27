# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Install dependencies
cd myStar && npm install

# Run the bot
cd myStar && node app.js
```

No build step or test suite is configured.

## Environment Setup

The bot requires a `myStar/.env` file with:
```
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
SLACK_APP_TOKEN=xapp-...
```

## Architecture

The repo is structured to hold multiple Slack bots, each in its own subdirectory. Currently only `myStar/` exists.

**`myStar/app.js`** is the sole application file. It uses `@slack/bolt` v4 in **Socket Mode** (no HTTP server needed — the app connects out to Slack via WebSocket using `SLACK_APP_TOKEN`).

### Bot behavior

- **`/test` command**: Acknowledges and responds in-channel with a static message.
- **Message listener**: For every non-bot, non-edited message that contains at least one user mention (`<@USER_ID>`) and at least one `:coin:` or `:token_icon:` emoji, sends a DM to each mentioned user informing them of the token award. The sender's user ID is included in the DM.

### Key Slack Bolt patterns

- `await ack()` must be called within 3 seconds of receiving a command/action or Slack will show an error.
- `respond()` replies to the originating channel/conversation.
- `client.chat.postMessage({ channel: userId, ... })` opens a DM by passing a user ID as the channel.
- `socketMode: true` in the App constructor enables WebSocket mode; `appToken` is required for this.
