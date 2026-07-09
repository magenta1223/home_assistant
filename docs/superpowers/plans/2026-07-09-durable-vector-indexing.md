# Durable Vector Indexing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve committed memories/topics and durably track vector indexing when Qdrant is unavailable.

**Architecture:** Insert an indexing-outbox row in the same SQLite transaction that creates a memory or topic. Dispatch after commit; mark success as `INDEXED`, record failures as retryable `INDEX_PENDING`, and retry pending entries during later approval/save operations.

**Tech Stack:** Kotlin, Exposed, SQLite, Qdrant adapter, kotlin.test

---

### Task 1: Add a durable indexing outbox

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/indexing/IndexingOutboxStore.kt`
- Create: `repository/src/main/kotlin/com/homeassistant/repository/db/tables/IndexingOutboxTable.kt`
- Create: `repository/src/main/kotlin/com/homeassistant/repository/repo/indexing/IndexingOutboxRepository.kt`
- Create: `repository/src/main/kotlin/com/homeassistant/repository/repo/indexing/IndexingOutboxQueries.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/db/DatabaseFactory.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/RepositoryStores.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/RepositoryFactory.kt`

- [ ] Add repository tests that require an outbox row for committed memories and topics.
- [ ] Implement the outbox table, transaction helpers, and store.

### Task 2: Dispatch memory indexing safely

**Files:**
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/memory/MemoryRepository.kt`
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/memory/MemoryTools.kt`
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/DomainToolRegistry.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/memory/MemoryToolsTest.kt`

- [ ] Add a failing test proving vector failure does not turn a committed approval into an error.
- [ ] Mark success/failure in the outbox and retry older pending memories.

### Task 3: Dispatch topic indexing safely

**Files:**
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/topicanalysis/TopicAnalysisRepository.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/impl/KakaoMessageTopicAnalysisService.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Test: `nlp/src/test/kotlin/com/homeassistant/nlp/analysis/KakaoMessageTopicAnalysisServiceTest.kt`

- [ ] Add a failing test proving topic save survives index failure and records pending work.
- [ ] Dispatch current and older pending topics without failing the save response.
- [ ] Run focused tests and the full build.
- [ ] Commit the verified change.
