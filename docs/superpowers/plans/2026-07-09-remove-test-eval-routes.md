# Remove Test Evaluation Routes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unauthenticated test-data and model-evaluation capabilities from the production server.

**Architecture:** Delete the HTTP routes and their application wiring, then remove the now-unreachable evaluation API/service types. Keep production Kakao analysis behavior unchanged.

**Tech Stack:** Kotlin, Ktor, kotlin.test

---

### Task 1: Remove production evaluation surface

**Files:**
- Modify: `app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Modify: `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/api/TopicAnalysisModels.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/api/TopicAnalysisUseCase.kt`
- Delete: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/impl/TopicAnalysisModelEvalService.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/routes/KakaoImportRoutesTest.kt`
- Delete: `nlp/src/test/kotlin/com/homeassistant/nlp/analysis/TopicAnalysisModelEvalServiceTest.kt`

- [ ] Change route tests to require 404 for both `/api/test` endpoints.
- [ ] Run the focused route tests and confirm they fail.
- [ ] Remove test routes, constants, wiring, and evaluation-only types.
- [ ] Run focused tests and the full build.
- [ ] Search production sources for the removed route/type names.
- [ ] Commit the verified change.
