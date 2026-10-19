# Codex app-server transport

`CodexAppServerTransport` owns the typed Codex app-server protocol. One app-server can serve multiple Codex threads,
so thread- and turn-scoped operations carry their `threadId` in typed protocol requests.

`DefaultCodexAppServerTransport` owns request IDs, response correlation, protocol encoding, and typed event dispatch.
It delegates OS process creation, stdio, and termination to `ProcessTransport`. The production composition is:

Codex app-server wire messages, response DTOs, and JSON configuration live in `transport.protocol`; the conversation
package consumes those typed messages but does not own the wire protocol.

```text
DefaultCodexAppServer               conversation policy, deadlines, restart, thread/turn state
└─ CodexAppServerTransport
   └─ DefaultCodexAppServerTransport   typed Codex protocol
      └─ StdioProcessTransport         OS process lifecycle and line transport
```

App-server callers receive typed futures and events; they do not send or parse JSON lines. `ProcessTransport` knows
nothing about Codex threads, turns, JSON-RPC, or protocol messages.

`DefaultCodexAppServer` calls `start` before trusting its ready flag, then performs the typed initialization handshake
only for a newly started process. Process exit and protocol events return through `CodexAppServerTransport.Listener`.

Request timeouts belong to the caller. Cancelling a returned future removes its request from the transport's pending
response tracking, so an abandoned request is not retained when the app-server never responds.

## Completion

`CompletionClient` uses the existing `CodexAppServerTransport` initialization, thread/start, and turn/start operations.
Each call opens and closes its own app-server, preserving the previous per-request lifetime. Conversation wiring and
ownership are unchanged. Completion uses an ephemeral thread, a caller-provided output schema, and text/local-image
inputs; temporary image files are removed on success, failure, or cancellation.

Analysis retains `gpt-5.6-sol` / `high` and a ten-minute execution timeout, including initialization. App-server
uses normal Codex settings; the previous exec-only `--ignore-user-config` and `--ignore-rules` flags are not supported.
The existing read-only sandbox, no-approval policy, and disabled web search apply.

## Process lifecycle

The following states belong only to `StdioProcessTransport`:

| State | Meaning | `start` |
|---|---|---|
| `Stopped` | No process exists | Starts one process |
| `Running` | Requests may be sent | Returns `true` |
| `Terminating` | Unexpected exit cleanup and callback are running | Returns `false` |
| `Closed` | Transport was permanently closed | Returns `false` |

`ProcessTransport.stop` is an expected, restartable shutdown and does not emit `onProcessExited`. An unexpected exit
invokes `onProcessExited` outside the lifecycle lock and then moves to `Stopped`. It does not permit a replacement
process to start while that callback is running.

`DefaultCodexAppServerTransport` translates `onProcessExited` into `onAppServerExited` after failing its pending
protocol requests.

`onItemCompleted`, `onTurnCompleted`, and `onThreadClosed` are app-server protocol events. `onProtocolError` reports
a malformed known message without conflating it with process termination.
