# Slack Codex Conversation Design

## Goal

Use Slack direct messages as the conversational entry point for the household second brain. Codex CLI owns conversational context through persistent threads, while the Kotlin application owns Slack identity, authorization, household-memory retrieval, and session selection.

The user should normally send plain DM messages without thinking about sessions. Session controls are available only when a user wants to start over or resume an older conversation.

## Scope

The first version supports:

- One configured Slack workspace for the household.
- One independent active conversation per Slack member.
- Plain-text DM questions and follow-up messages.
- Persistent Codex threads across application restarts.
- Starting a new conversation.
- Listing and resuming a previous conversation.
- Injecting relevant approved household memories into each Codex turn.
- Existing Kakao file analysis and topic-confirmation behavior without changes.

The first version does not let Codex access SQLite, Qdrant, repository files, or memory mutation tools directly.

## User Experience

### Normal conversation

A member sends a top-level DM to the Slack app. If the member has an active Codex thread, the application resumes it. Otherwise, the application starts a new Codex thread and makes it active.

The DM timeline remains a normal continuous messenger experience. Slack threads are not used as conversation boundaries.

### Session controls

One Slack command, `/brain`, accepts these subcommands:

- `/brain new`: deactivate the current session. The next plain DM starts a new Codex thread.
- `/brain resume`: open a modal listing the member's recent sessions, newest first.
- `/brain current`: show the active session title and last-active time.

Selecting a session in the resume modal atomically replaces the member's active-session pointer. The app posts a short confirmation containing the selected title and last-active time. It does not replay the old conversation into Slack.

Session titles are derived deterministically from the first user message and truncated to a display-safe length. Creating a title does not make a second LLM call.

## Identity And Authorization

Slack identity is determined before Codex runs.

```text
(teamId, slackUserId) -> household member -> active Codex session
```

- `teamId` is the configured household Slack workspace ID.
- `slackUserId` is the stable member ID supplied by Slack events and interactions.
- Every event must match the configured `teamId`.
- The Kotlin boundary derives the internal identity deterministically as `UserId("slack:<teamId>:<slackUserId>")`; neither configuration nor an LLM chooses the mapping.
- A member can list, activate, and resume only sessions owned by the same `(teamId, slackUserId)` pair.
- Codex never receives or selects an internal `UserId`.
- Codex never receives database credentials or a database path.

Approved topic retrieval remains inside the Kotlin application. The application retrieves household-scoped matches through the existing `TopicAnswerUseCase` boundary and serializes only the relevant titles, summaries, and claims into the Codex turn context.

## Persistence Model

### `slack_codex_sessions`

```text
id                  internal session ID
team_id             owning Slack workspace
slack_user_id       owning Slack member
codex_thread_id     persistent Codex thread ID, unique
title               first-message-derived display title
created_at          creation time
last_active_at      latest completed or failed turn time
unavailable_at      set when the Codex thread cannot be resumed
unavailable_reason  internal diagnostic category, never raw process output
```

### `slack_codex_active_sessions`

```text
team_id             Slack workspace
slack_user_id       Slack member
session_id          active slack_codex_sessions row
```

The `(team_id, slack_user_id)` pair is the primary key. Keeping the active pointer in a separate table makes the one-active-session invariant explicit without restricting the number of archived sessions.

### `slack_message_receipts`

```text
channel_id          Slack DM channel
message_ts          Slack message timestamp
session_id          resolved local session when available
status              PROCESSING, ANSWER_READY, COMPLETED, or FAILED
answer_text         final Codex answer once generated, otherwise null
response_ts         optional Slack response timestamp
created_at          receipt time
updated_at          latest state change
```

`(channel_id, message_ts)` is unique. This prevents Slack retries from invoking Codex twice for the same DM.

Codex's own session files are stored under a dedicated persistent `CODEX_HOME`. The application database stores the thread mapping and selection state, not a duplicate copy of the full Codex conversation.

## Components

### `SlackDirectMessageHandler`

Validates workspace and DM events, derives the internal `UserId`, acknowledges Slack immediately, claims the message receipt, and submits work outside the Slack acknowledgement path. It ignores bot-authored messages, message edits, deletes, and unsupported subtypes.

### `SlackConversationService`

Coordinates identity lookup, active-session selection, approved-memory retrieval, Codex invocation, receipt state, and Slack response delivery. It serializes turns per `(teamId, slackUserId)` so a Codex thread never processes concurrent prompts.

### `SlackCodexSessionStore`

Owns session rows, active-session pointers, ownership checks, recent-session listing, and message receipts. Active-pointer changes and ownership validation occur in database transactions.

### `CodexConversationClient`

Wraps `codex exec` with `ProcessBuilder`; it never builds a shell command string. Prompts are written through standard input to avoid quoting errors and command injection.

For a new conversation it runs non-interactively with JSONL output and parses the `thread.started` event. For an existing conversation it runs `codex exec resume <threadId>` and parses the final agent message. The interface returns structured success or failure values rather than exposing raw process output to Slack handlers.

### `HouseholdContextProvider`

Calls the existing approved-topic answer/search boundary using the authenticated request context. It converts matches into a bounded, clearly delimited context block. Imported memory text is treated as reference data, not as agent instructions.

## Message Flow

### New session

1. Slack delivers a human-authored DM event.
2. The handler validates `teamId`, acknowledges the event, and claims `(channelId, messageTs)`.
3. The conversation service serializes work for the Slack member.
4. No active-session pointer is found.
5. Kotlin retrieves relevant approved household memories for the question.
6. `CodexConversationClient` starts `codex exec --json` in the dedicated workspace.
7. On `thread.started`, the application immediately persists the Codex thread, local session, and active pointer.
8. On the final agent message, the application stores the answer and marks the receipt `ANSWER_READY`.
9. The application posts the stored answer to the DM and marks the receipt completed.

Persisting on `thread.started` minimizes orphaned Codex threads if the process fails after startup but before producing an answer.

### Existing session

1. Steps 1-3 are the same as a new session.
2. The active session is loaded and ownership is revalidated.
3. Kotlin retrieves relevant approved memories for the new question.
4. `CodexConversationClient` runs `codex exec resume <codexThreadId> --json` with the new turn.
5. The final agent message is stored with `ANSWER_READY`, session activity is updated, the stored answer is posted to Slack, and the receipt is completed.

### Resume selection

1. `/brain resume` opens a modal containing only available sessions owned by the invoking member.
2. The modal submission includes the local session ID, not a raw Codex thread ID.
3. The server reloads the session, revalidates ownership, and atomically replaces the active pointer.
4. A confirmation is posted to the member's DM.

## Codex Runtime Boundary

Codex runs in a dedicated minimal workspace that contains only agent instructions required for the household assistant. It does not run in the `homeServers` repository or a directory containing household files. The Codex child process must run inside an OS-level isolation boundary that cannot read the application database, Qdrant storage, repository, service secrets, or household files. A read-only Codex sandbox limits writes but is not treated as a confidentiality boundary.

Runtime requirements:

- Read-only sandbox.
- Approval policy that never blocks waiting for terminal input.
- No direct SQLite or Qdrant access.
- A process or container filesystem that exposes only the dedicated workspace and persistent Codex session directory.
- Outbound network access limited to the endpoints required for Codex authentication and model execution; Codex web search is disabled.
- Persistent, server-owned `CODEX_HOME`.
- Authentication supplied only to the Codex child process.
- `--ignore-user-config` and `--ignore-rules` so personal machine configuration cannot change the service runtime.
- A bounded process timeout and process-tree termination on timeout.
- Standard error retained in server logs with secrets redacted; it is never returned verbatim to Slack.

The run must not use `--ephemeral`, because resumable Codex threads require persisted session state.

## Prompt Boundary

Each turn contains:

1. Stable household-assistant instructions from the dedicated Codex workspace.
2. A bounded approved-memory context generated by Kotlin.
3. The member's current Slack message.

The context block is explicitly labeled as untrusted reference content. It cannot grant permissions or override agent instructions. Codex conversation history supplies prior turn context; Kotlin does not duplicate the full message history in its own tables.

## Failure Handling

- Workspace mismatch or a missing Slack user ID: reject without invoking Codex.
- Duplicate Slack message: do not invoke Codex again. If its receipt is `ANSWER_READY`, retry only Slack delivery using the stored answer; otherwise return the recorded state.
- Approved-memory search unavailable: continue without memory context and state that stored-memory lookup was unavailable only when it affects the answer.
- Codex fails before `thread.started`: mark the receipt failed; no local session is created.
- Codex fails after `thread.started`: retain the persisted session so the next message can resume it; mark the receipt failed.
- Resume reports a missing or corrupt Codex thread: do not silently create a new thread. Set `unavailable_at` and `unavailable_reason`, clear the active pointer, and tell the user to start or select another conversation.
- Timeout: terminate the process tree, mark the receipt failed, and post a short retryable error.
- Slack response posting fails after Codex completes: retain the stored answer in `ANSWER_READY`; retry delivery without running Codex again.
- Application restart: stale `PROCESSING` receipts become recoverable failures; they are never automatically re-executed without an explicit retry policy.

## Concurrency

The household deployment runs one application instance. A keyed in-process queue serializes turns per `(teamId, slackUserId)` while allowing different family members to use the bot concurrently.

The database owns durable idempotency and active-pointer invariants. Multi-instance distributed locking is outside the first-version scope.

## Testing

### Unit tests

- First DM starts Codex and persists the parsed `thread_id`.
- Follow-up DM resumes the active Codex thread.
- Different Slack members never share an active pointer or thread.
- Internal `UserId` derivation is deterministic for the Slack workspace and member pair.
- Workspace mismatch is rejected before retrieval or Codex invocation.
- Duplicate `(channelId, messageTs)` does not invoke Codex twice.
- An `ANSWER_READY` duplicate retries Slack delivery with the stored answer and does not invoke Codex.
- `/brain new` clears only the invoking member's active pointer.
- Resume lists and activates only sessions owned by the invoking member.
- A forged modal session ID cannot cross the ownership boundary.
- Unavailable Codex threads cannot be selected or remain active.
- JSONL parsing handles progress events, final messages, failures, and malformed output.
- Process arguments and prompt input do not pass through a shell.
- Concurrent messages from one member execute in order.
- Members can execute concurrently with each other.

### Integration tests

- Repository tests cover session persistence, active-pointer replacement, and receipt uniqueness.
- Slack handler tests verify immediate acknowledgement and asynchronous work submission.
- A fake Codex executable verifies new and resume process protocols without making model calls.
- Existing Slack Kakao analysis and confirmation tests remain green.
- The full Gradle test suite and build pass.

## Non-goals

- Reintroducing the removed general chat, conversation-intent, or chat-response pipeline.
- Giving Codex direct database, vector-store, filesystem, or shell-based household-data access.
- Letting Codex choose a `UserId`, `familyId`, or Slack identity.
- Allowing Codex to create, approve, reject, or delete memories in the first version.
- Slack channel or group-DM conversations.
- Using Slack threads as session boundaries.
- Streaming partial Codex output into Slack.
- Multiple Slack workspaces or multiple application instances.
- Migrating away from the existing configurable LLM backends used by topic analysis.
