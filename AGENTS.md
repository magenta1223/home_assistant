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

Topic analysis always uses the Codex CLI resolved from `PATH` (`codex.cmd` on Windows). Hosted
LLM providers and provider-selection environment variables are not supported.

| Variable | Default | Notes |
|---|---|---|
| `EMBEDDING_MODEL` | `qllama/multilingual-e5-base` | Model prepared by `setupEmbedding` and verified as 768-dimensional at application startup |
| `QDRANT_URL` | `http://localhost:6333` | Required only when wiring vector search |
| `QDRANT_COLLECTION` | `canonical_memories` | Must use 768-dimensional vectors for the default e5-base embedding model |
| `SLACK_TEAM_ID` | - | Required Slack workspace ID |
| `SLACK_MEMBER_SCOPES_JSON` | - | Server-owned mapping of Slack members to immutable application `userId` values |
| `HTTP_MEMBER_API_KEYS_JSON` | - | Optional JSON array of `{userId, token}` records for HTTP Bearer authentication; tokens must be high-entropy and never committed |
| `CODEX_EXECUTABLE` | - | Absolute path to the version-specific service Codex executable |
| `CODEX_EXPECTED_VERSION` | `0.144.5` | Exact service CLI version validated at startup |
| `CODEX_WORK_DIR` | - | Dedicated minimal service workspace; must not contain the application DB |
| `CODEX_HOME` | - | Persistent service-only Codex home, separate from the operator CLI |
| `CODEX_API_KEY` | - | Service secret mapped to `OPENAI_API_KEY` only in the Codex child process |
| `CODEX_TIMEOUT_SECONDS` | `120` | Positive per-turn process timeout |

Server port and DB path are configured in `AppConfig` and Ktor application config.

On Windows, `setupEmbedding` downloads the pinned official standalone Ollama distribution, verifies
its SHA-256, installs it under the gitignored `runtime/ollama/` directory, and prepares the embedding
model. The application starts and stops this project-owned `ollama serve` process on
`127.0.0.1:11435`; do not start a separate Ollama server on that port. Normal application startup
never downloads binaries or models and fails with a setup instruction when the managed runtime is
missing or unhealthy.

## Project Direction

The project is now a **home second brain**, not a general chat assistant. The primary flow is:

1. Import source records, currently Kakao exports.
2. Analyze source records and immediately save the resulting topics and memories as canonical records.
3. Search and retrieve canonical memories with their source evidence and optional topic context.
4. Answer household questions through authenticated Slack DMs using short-lived Codex threads.

Slack DM conversation state is intentionally narrow: a member's Codex thread may continue only while its ten-minute idle lease is active. Once the lease expires, the old thread is ended from the application's perspective and must never be resumed, listed, or manually reactivated; the next DM starts a new thread.

Do not reintroduce `/api/chat`, platform-neutral conversation sessions, intent-analysis pipelines, chat-response DTOs, or a separate topic-analysis preview/review stage. Keep Slack identity, authorization, memory retrieval, idempotency, and session expiry inside the Kotlin application.

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

- `port/input/` - use-case entry contracts plus their request and result models, grouped by feature.
- `port/output/` - technology-neutral capabilities required from persistence, semantic search,
  extraction, placement, and conversation adapters.
- `usecase/` - technology-independent orchestration that implements input ports and uses output ports.
- `port/input/memory/` and `usecase/memory/` - memory analysis, search, answer, and placement flows.
- `port/input/slackconversation/` and `usecase/slackconversation/` - authorized Slack conversation flow.

Application exception types are use-case failure contracts. Declare them beside the corresponding
input port with an `internal` constructor, and let the use-case implementation translate collaborator
failures into that contract. Output ports and adapters must not construct application exceptions.

### adapter-inbound

- `http/` - Ktor routes and HTTP request/response DTO mapping.
- `kakao/` - Kakao export parsing at the source-format boundary.
- `slack/` - Slack Socket Mode, event listeners, blocks, modals, queueing, and message delivery mapping.

### adapter-outbound

- `codex/` - Codex CLI transport and conversation-turn implementations.
- `topicanalysis/` - Codex-backed topic extraction implementations.
- `embedding/ollama/` - pinned Windows Ollama installation, managed server lifecycle, and local text embedding.
- `persistence/` - SQLite/Exposed repositories for source records, topics, canonical memories, indexing outbox, and Slack sessions.
- `vector/qdrant/` - Qdrant vector storage implementation.
- `vector/memory/` - canonical-memory semantic index implementation.

### common and configuration

- `common/json/` - adapter-independent JSON serialization utility.
- `configuration/` - environment lookup and runtime/server configuration constants.

### domain

- `identity/` - single-household user identity and authorization policy. There is no family/subgroup scope.
- `source/` - source-agnostic imported records, analysis documents, and the source-record persistence port.
- `topicanalysis/` - topic grouping and proposal models.
- `memory/` - canonical memory and FAMILY/PRIVATE visibility policy. FAMILY is globally visible to authorized users; PRIVATE requires the requesting `userId` to match `createdByUserId`.

### app

Ktor + Netty server. Current routes:

- `GET /health` -> `{"status":"ok"}`
- `POST /api/kakao/import/analyze` -> analyzes supplied Kakao text and immediately saves the resulting topics and canonical memories.
- `POST /api/memories/answer` -> retrieves visible canonical memories and builds a direct answer.

HTTP write/read routes require a user-specific Bearer token from `HTTP_MEMBER_API_KEYS_JSON`; the caller must not send `userId` in the request body. `/health` remains unauthenticated.

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
