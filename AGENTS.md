# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run the server (port 8080)
./gradlew :app:run

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :application:test
./gradlew :adapter:test
./gradlew :domain:test
./gradlew :app:test
```

## Environment Setup

Copy `.env` to the project root (already present; gitignored). Key variables:

Topic analysis always uses the Codex CLI resolved from `PATH` (`codex.cmd` on Windows). Hosted
LLM providers and provider-selection environment variables are not supported.

| Variable | Default | Notes |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Used only by local embedding calls |
| `EMBEDDING_MODEL` | `qllama/multilingual-e5-base` | Ollama embedding model; run `ollama pull qllama/multilingual-e5-base` before vector indexing |
| `QDRANT_URL` | `http://localhost:6333` | Required only when wiring vector search |
| `QDRANT_COLLECTION` | `family_memories` | Must use 768-dimensional vectors for the default e5-base embedding model |
| `SLACK_TEAM_ID` | - | Required Slack workspace ID |
| `SLACK_MEMBER_SCOPES_JSON` | - | Server-owned mapping of Slack members to immutable `userId`/`familyId` scopes |
| `CODEX_EXECUTABLE` | - | Absolute path to the version-specific service Codex executable |
| `CODEX_EXPECTED_VERSION` | `0.144.5` | Exact service CLI version validated at startup |
| `CODEX_WORK_DIR` | - | Dedicated minimal service workspace; must not contain the application DB |
| `CODEX_HOME` | - | Persistent service-only Codex home, separate from the operator CLI |
| `CODEX_API_KEY` | - | Service secret mapped to `OPENAI_API_KEY` only in the Codex child process |
| `CODEX_TIMEOUT_SECONDS` | `120` | Positive per-turn process timeout |

Server port and DB path are configured in `AppConfig` and Ktor application config.

## Project Direction

The project is now a **home second brain**, not a general chat assistant. The primary flow is:

1. Import source records, currently Kakao exports.
2. Analyze source records into topic candidates with an LLM.
3. Store pending memory candidates and evidence.
4. Approve, reject, search, and retrieve memories through domain tools or future purpose-built APIs.
5. Answer household questions through authenticated Slack DMs using short-lived Codex threads.

Slack DM conversation state is intentionally narrow: a member's Codex thread may continue only while its ten-minute idle lease is active. Once the lease expires, the old thread is ended from the application's perspective and must never be resumed, listed, or manually reactivated; the next DM starts a new thread.

Do not reintroduce `/api/chat`, platform-neutral conversation sessions, intent-analysis pipelines, or chat-response DTOs. Keep Slack identity, authorization, memory retrieval, idempotency, and session expiry inside the Kotlin application.

## Module Architecture

```text
domain/     - domain models, ports, and business/application policies
application/ - target home for vertically sliced use cases and their ports
adapter/     - target home for inbound and outbound technology adapters
app/        - composition root and Ktor server startup
```

The dependency direction is `app -> adapter -> application -> domain`.
Within `application`, keep commands, results, use-case orchestration, and use-case-specific output
ports together by use case. Within `adapter`, classify external entry points under `inbound` and
application-driven integrations under `outbound`.

### application

- `topicanalysis/` - vertically sliced analysis and save use cases with their output ports.
- `topicanswer/answer/` - topic-answer input, output, use case, and claim-search port.
- `memory/{create,list,approve,reject,search}/` - memory use cases grouped with their inputs and outputs.
- `slackconversation/handle/` - authorized Slack conversation/session orchestration and its ports.

### adapter

- `inbound/http/` - Ktor routes and HTTP request/response DTO mapping.
- `inbound/kakao/` - Kakao export parsing at the source-format boundary.
- `inbound/slack/` - Slack Socket Mode, event listeners, blocks, modals, queueing, and message delivery mapping.
- `inbound/tool/` - memory tool schemas, JSON mapping, dispatch, and result formatting.
- `outbound/codex/` - Codex topic extraction and conversation-turn implementations.
- `outbound/embedding/ollama/` - local Ollama text embedding implementation.
- `outbound/persistence/` - SQLite/Exposed repositories and schema implementations.
- `outbound/vector/qdrant/` - Qdrant vector storage implementation.
- `outbound/vector/topicclaim/` - topic-claim semantic index implementation.
- `shared/config/` and `shared/json/` - runtime-only adapter/composition support; never domain APIs.

### domain

- `identity/` - household identity, authorization scope, and access policy.
- `kakao/` - Kakao source models, import policy, and persistence ports.
- `source/` - source-agnostic analysis documents and records.
- `topicanalysis/` - topic domain models and persistence ports.
- `memory/` - memory domain models plus embedding and vector-store ports.

### app

Ktor + Netty server. Current routes:

- `GET /health` -> `{"status":"ok"}`
- `POST /api/kakao/import/analyze` -> analyzes supplied Kakao text content and returns pending topic candidates.

`Application.kt` wires repositories, Codex topic extraction, Kakao parsing/import, topic analysis, Slack, and HTTP routes.

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
