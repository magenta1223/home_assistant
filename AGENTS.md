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
./gradlew :core:test
./gradlew :nlp:test
./gradlew :domain:test
./gradlew :app:test
```

## Environment Setup

Copy `.env` to the project root (already present; gitignored). Key variables:

| Variable | Default | Notes |
|---|---|---|
| `AI_PROVIDER` | `ollama` | `ollama` or `openrouter` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Used when `AI_PROVIDER=ollama` |
| `OLLAMA_MODEL` | `llama3.2` | |
| `OPENROUTER_API_KEY` | - | Required when `AI_PROVIDER=openrouter` |
| `OPENROUTER_MODEL` | `z-ai/glm-4.5-air:free` | |
| `EMBEDDING_MODEL` | - | Required only when wiring embedding-backed memory features |
| `QDRANT_URL` | `http://localhost:6333` | Required only when wiring vector search |
| `QDRANT_COLLECTION` | `family_memories` | |

Server port and DB path are configured in `AppConfig` and Ktor application config.

## Project Direction

The project is now a **home second brain**, not a general chat assistant. The primary flow is:

1. Import source records, currently Kakao exports.
2. Analyze source records into topic candidates with an LLM.
3. Store pending memory candidates and evidence.
4. Approve, reject, search, and retrieve memories through domain tools or future purpose-built APIs.

Do not reintroduce `/api/chat`, conversation sessions, intent-analysis pipelines, or chat-response DTOs unless the product direction changes again.

## Module Architecture

```text
core/       - shared domain types, LLM interfaces, tool schemas, constants
nlp/        - LLM backends and source/topic analysis
domain/     - DB schema, Kakao import, memory repositories/tools/vector store
app/        - Ktor server wiring and HTTP routes
```

Dependency graph: `app` -> `nlp` + `domain` -> `core`

### core

Pure abstractions and shared types.

- `nlp/LlmBackend` - provider-independent LLM completion interface.
- `nlp/Message`, `MessageRole`, `LlmTypes` - LLM request/response domain types.
- `tools/*` - JSON Schema based tool definitions and executor interface.
- `identity/UserId` - user identity value type used by memory tools.
- `memory/MemoryTypes.kt` - memory categories and candidate status.
- `constants/AppConfig`, `Env` - configuration keys and defaults.

### nlp

- `backend/` - Ollama, OpenRouter, and Anthropic backend implementations.
- `backend/utils/` - tool prompt injection and prompt-injection tool-call parsing.
- `analysis/TopicAnalysisService` - turns source documents into validated topic candidates and stores them through `TopicAnalysisRepository`.

### domain

- `db/` - database initialization and Exposed table definitions.
- `kakao/` - Kakao export parsing, import, and persistence.
- `memory/` - memory candidate/repository logic, tools, embeddings, and vector store.
- `DomainToolRegistry` - single registration point for domain tools.

### app

Ktor + Netty server. Current routes:

- `GET /health` -> `{"status":"ok"}`
- `POST /api/kakao/import/analyze` -> imports Kakao text/file content and returns pending topic candidates.

`Application.kt` wires the database, selected LLM backend, Kakao import service, topic analysis service, and routes.

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
