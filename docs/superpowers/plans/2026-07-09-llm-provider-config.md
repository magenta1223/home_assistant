# LLM Provider Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make runtime provider defaults, API-key selection, and Ollama token limits consistent with configuration.

**Architecture:** Define provider-related environment names and defaults in `AppConfig`, inject environment lookup into the backend factory for deterministic tests, and centralize Ollama's effective prediction limit in its config.

**Tech Stack:** Kotlin, Ktor client, kotlin.test

---

### Task 1: Correct provider configuration

**Files:**
- Modify: `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/backend/LmBackendFactory.kt`
- Test: `core/src/test/kotlin/com/homeassistant/core/constants/AppConfigTest.kt`
- Create: `nlp/src/test/kotlin/com/homeassistant/nlp/backend/LmBackendFactoryTest.kt`

- [ ] Add failing tests for the Ollama default and Anthropic key name.
- [ ] Add `DEFAULT_AI_PROVIDER` and `ANTHROPIC_API_KEY`.
- [ ] Use the constants in application wiring and backend creation.

### Task 2: Honor Ollama numPredict

**Files:**
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/backend/ollama/OllamaConfig.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/backend/ollama/OllamaBackend.kt`
- Create: `nlp/src/test/kotlin/com/homeassistant/nlp/backend/ollama/OllamaConfigTest.kt`
- Modify: `AGENTS.md`

- [ ] Add failing precedence/fallback tests.
- [ ] Use `numPredict` before `maxTokens`.
- [ ] Run focused tests and the full build.
- [ ] Commit the verified change.
