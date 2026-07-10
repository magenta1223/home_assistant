# Preview Not Found Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return 404 only when a requested analysis preview does not exist.

**Architecture:** Introduce a typed exception at the topic-analysis API boundary. The Ktor route catches only that exception; unexpected storage, indexing, and programming failures remain server errors.

**Tech Stack:** Kotlin, Ktor, kotlin.test

---

### Task 1: Narrow HTTP exception mapping

**Files:**
- Create: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/api/TopicAnalysisExceptions.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/impl/KakaoMessageTopicAnalysisService.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/routes/KakaoImportRoutesTest.kt`

- [ ] Add a failing test proving unexpected save failures are not returned as 404.
- [ ] Make the missing-preview fake throw the typed exception.
- [ ] Throw the typed exception from both save methods.
- [ ] Catch only the typed exception in the route.
- [ ] Run focused tests and the full build.
- [ ] Commit the verified change.
