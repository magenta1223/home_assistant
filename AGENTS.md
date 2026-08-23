# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run the server (port 8080)
./gradlew :app:run

# One-time Windows setup for the managed Ollama runtime and embedding model
.\gradlew.bat setupEmbedding

# One-time Windows setup for every project-managed runtime, including Qdrant
.\gradlew.bat setupRuntime

# Validate and update the Slack app from slack-app-manifest.json
.\gradlew.bat updateSlackManifest

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :application:test
./gradlew :adapter-inbound:test
./gradlew :adapter-outbound:test
./gradlew :common:test
./gradlew :configuration:test
./gradlew :domain:test
./gradlew :app:test
```

## Environment Setup

Copy `.env` to the project root (already present; gitignored). Key variables:

Topic analysis and Slack memory answers use the locally installed and authenticated Codex CLI
resolved from `PATH` (`codex.cmd` on Windows). Run `codex login` as the server user before startup.
Hosted LLM providers and provider-selection environment variables are not supported.

| Variable | Default | Notes |
|---|---|---|
| `EMBEDDING_MODEL` | `qllama/multilingual-e5-base` | Model prepared by `setupEmbedding` and verified as 768-dimensional at application startup |
| `QDRANT_URL` | `http://127.0.0.1:6333` | Managed Qdrant loopback endpoint; non-local URLs are rejected |
| `QDRANT_COLLECTION` | `canonical_memories` | Must use 768-dimensional vectors for the default e5-base embedding model |
| `SLACK_TEAM_ID` | - | Required Slack workspace ID |
| `SLACK_MEMBER_SCOPES_JSON` | - | Deprecated one-time migration input; existing mappings reserve their old `userId` until the member completes Slack registration, then the variable may be removed |
| `HTTP_MEMBER_API_KEYS_JSON` | - | Optional JSON array of `{userId, token}` records for HTTP Bearer authentication; tokens must be high-entropy and never committed |
| `CODEX_TIMEOUT_SECONDS` | `600` | Optional positive timeout for each local Codex conversation turn |

Server port and DB path are configured in `AppConfig` and Ktor application config.

On Windows, `setupEmbedding` downloads the pinned official standalone Ollama distribution, verifies
its SHA-256, installs it under the gitignored `runtime/ollama/` directory, and prepares the embedding
model. The application starts and stops this project-owned `ollama serve` process on
`127.0.0.1:11435`; do not start a separate Ollama server on that port. Normal application startup
never downloads binaries or models and fails with a setup instruction when the managed runtime is
missing or unhealthy.

`setupQdrant` downloads and verifies the pinned official Windows Qdrant executable under the
gitignored `runtime/qdrant/` directory. Normal application startup owns that process on
`127.0.0.1:6333`, stores durable vector data under `runtime/qdrant/storage/`, disables telemetry,
and stops Qdrant with the server. Docker is not used. `setupRuntime` prepares both managed runtimes.

## Project Direction

The project is now a **home second brain**, not a general chat assistant. The primary flow is:

1. Explicitly inject plain text or Kakao source records through the local knowledge page or the
   registered-user Slack `/knowedge` modal.
2. Select PUBLIC access or an immutable set of authorized application user IDs for each source.
3. Analyze source records and immediately save the resulting topics and memories as canonical records.
4. Search and retrieve canonical memories with their source evidence and optional topic context.
5. Register application users from their first Slack DM using a name-entry modal.
6. Answer memory-backed questions through registered Slack DMs using short-lived Codex threads.

Slack supports member registration, memory-backed answers, and explicit knowledge injection through
the `/knowedge` slash-command modal. An unknown member's first DM receives a registration button; its
modal collects a display name, persists the authenticated Slack identity, and then resumes the
original question. Canonical memory editing remains on the local knowledge page. Slack Interactivity
must be enabled for registration and knowledge modals; Socket Mode carries those interactions.

Slack DM conversation state is intentionally narrow: a member's Codex thread may continue only while its ten-minute idle lease is active. Once the lease expires, the old thread is ended from the application's perspective and must never be resumed, listed, or manually reactivated; the next DM starts a new thread.

Do not reintroduce `/api/chat`, HTTP memory-answer routes, intent-analysis pipelines, chat-response DTOs, or a separate topic-analysis preview/review stage. Slack maps signed events and interactions to a technology-neutral conversation identity and only relays registration, answer, and knowledge-injection requests. The application layer resolves that identity through the persisted user registry and owns registration state, pending-question resumption, authorization, memory analysis and retrieval, idempotency, and session expiry.

## Module Architecture

```text
domain/            - domain concepts, invariants, and domain-owned ports
application/       - input/output ports and technology-independent use-case orchestration
common/            - adapter-independent shared utilities such as JSON serialization
configuration/     - runtime environment and server configuration
adapter-inbound/   - HTTP, Slack, and source-format inbound adapters
adapter-outbound/  - Codex, persistence, embedding, and vector outbound adapters
app/               - composition root and Ktor server startup
```

The dependency direction is `app -> adapter-inbound/adapter-outbound -> application -> domain`.
Both adapter modules may depend on `common` and `configuration`; inbound and outbound must not
depend on each other.
Within `application`, separate inbound contracts, outbound requirements, and orchestration into
`port/input`, `port/output`, and `usecase`. Organize each layer by feature area. Inbound adapters
invoke input ports without importing use-case implementations; outbound adapters implement output
ports. Within the adapter modules, keep external entry points in `adapter-inbound` and
application-driven integrations in `adapter-outbound`.

### application

- `port/input/` - use-case entry contracts plus their request and result models, grouped by feature. `MemoryAnswerWorkflow` owns user resolution, registration, memory-answer routing, and channel delivery state; `KnowledgeInjectionWorkflow` resolves channel identities before memory analysis.
- `port/output/` - technology-neutral capabilities required from persistence, semantic search,
  extraction, placement, and conversation adapters.
- `usecase/` - technology-independent orchestration that implements input ports and uses output ports.
- `port/input/memory/` and `usecase/memory/` - memory analysis, search, conversation, answer context, and placement flows.

`application/usecase/README.md` is the comprehensive use-case map. Every leaf package containing
concrete use-case implementations must also contain a `README.md` with at least one Mermaid
`sequenceDiagram` covering its normal flow and important branches. Update the corresponding README
in the same change whenever input/output ports, orchestration order, or failure behavior changes.

Application exception types are use-case failure contracts. Declare them beside the corresponding
input port with an `internal` constructor, and let the use-case implementation translate collaborator
failures into that contract. Output ports and adapters must not construct application exceptions.

### adapter-inbound

- `http/` - Ktor routes and HTTP request/response DTO mapping.
- `kakao/` - Kakao export parsing at the source-format boundary.
- `slack/` - Slack Socket Mode, slash-command registry, registration/knowledge modals, DM event mapping, transport queueing, and application-result delivery. It does not own user, memory-answer, or knowledge-analysis business state.
- `text/` - direct-text source parsing at the inbound boundary.

### adapter-outbound

- `codex/` - Codex CLI transport and conversation-turn implementations.
- `topicanalysis/` - Codex-backed topic extraction implementations.
- `embedding/ollama/` - pinned Windows Ollama installation, managed server lifecycle, and local text embedding.
- `persistence/` - SQLite/Exposed repositories for registered users, pending registration questions, source records, topics, canonical memories, indexing outbox, and memory conversation sessions.
- `vector/qdrant/` - pinned Windows Qdrant installation, managed server lifecycle, and vector storage.
- `vector/memory/` - canonical-memory semantic index implementation.

### common and configuration

- `common/json/` - adapter-independent JSON serialization utility.
- `configuration/` - environment lookup and runtime/server configuration constants.

### domain

- `identity/` - persisted application user identity, display name, and authorization policy. Slack IDs identify accounts; user-entered names are display values only. There is no group scope.
- `source/` - source-agnostic imported records, analysis documents, and the source-record persistence port.
- `topicanalysis/` - topic grouping and proposal models.
- `memory/` - canonical memory access policy. PUBLIC is visible to every authorized user; RESTRICTED
  is visible only to its explicit `allowedUserIds`. Extracted memories inherit source access, and
  evidence with different restricted scopes uses their viewer intersection.

### app

Ktor + Netty server bound to `127.0.0.1`. Current routes:

- `GET /health` -> `{"status":"ok"}`
- `GET /knowledge` -> local knowledge injection page.
- `GET /api/knowledge/users` -> returns registered Slack members with selectable application user IDs and display names.
- `POST /api/knowledge/import/analyze` -> imports text or Kakao data with an explicit audience and immediately saves canonical memories.

HTTP write/read routes require a user-specific Bearer token from `HTTP_MEMBER_API_KEYS_JSON`; the caller must not send `userId` in the request body. `/health` and the data-free `/knowledge` shell remain unauthenticated.

`ApplicationServices.kt` is the composition root for repositories, use cases, Codex extraction, embeddings, vector search, Slack, and HTTP adapters.

## Coding Principles

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Strictly Adhere Standard & Modern SW Programming

Follow standard and modern software engineering principles before inventing local solutions.

- **KISS (Keep It Simple, Stupid):** prefer simple, explicit, maintainable code over clever or surprising code.
- **YAGNI (You Aren't Gonna Need It):** do not add speculative features, flexibility, abstractions, configuration, or indirection.
- **DRY (Don't Repeat Yourself):** remove meaningful duplication of knowledge, rules, and behavior; do not force abstraction over coincidental similarity.
- **SOLID:** keep responsibilities focused, interfaces small, dependencies stable, and behavior extensible without invasive changes.
- **SoC / High Cohesion, Low Coupling:** keep related behavior together, separate unrelated concerns, and minimize unnecessary dependencies.
- **Encapsulation / Information Hiding:** expose the smallest useful surface area and keep internal representation details private.
- **Least Astonishment:** names, fields, behavior, and module placement should match what a competent reader would expect.
- **Domain-Driven Design:** model domain concepts explicitly; names, fields, identity, lifecycle, and invariants should match the concept being represented.
- **Clean Architecture / Hexagonal Architecture:** keep business rules independent from frameworks, databases, UI, and external services; convert deliberately at boundaries.
- **Test Pyramid / Regression Testing:** prefer focused automated tests and add checks that would fail if the same bug or boundary mistake is reintroduced.

## 2. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 3. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 4. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 5. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
