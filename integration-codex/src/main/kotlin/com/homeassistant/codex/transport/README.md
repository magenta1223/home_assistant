# Codex app-server transport

`CodexAppServerTransport` owns the typed Codex app-server protocol. One app-server can serve multiple Codex threads,
so thread- and turn-scoped operations carry their `threadId` in typed protocol requests.

`DefaultCodexAppServerTransport` owns request IDs, response correlation, protocol encoding, and typed event dispatch.
It delegates OS process creation, stdio, and termination to `ProcessTransport`. The production composition is:

Codex app-server wire messages, response DTOs, and JSON configuration live in `transport.protocol`; the conversation
package consumes those typed messages but does not own the wire protocol.

```text
CodexAppServerTransport
└─ DefaultCodexAppServerTransport   typed Codex protocol
   └─ StdioProcessTransport         OS process lifecycle and line transport
```

App-server callers receive typed futures and events; they do not send or parse JSON lines. `ProcessTransport` knows
nothing about Codex threads, turns, JSON-RPC, or protocol messages.

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
