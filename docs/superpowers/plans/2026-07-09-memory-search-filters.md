# Memory Search Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply caller ownership and creation-time bounds to memory vector searches.

**Architecture:** Carry `createdBy` and numeric `createdAt` metadata into vector points. Translate the domain filter to Qdrant match and numeric range conditions while preserving the generic payload-vector API used by topic claims.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, Qdrant HTTP API, kotlin.test

---

### Task 1: Carry complete memory search metadata

**Files:**
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/memory/MemoryTools.kt`
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/memory/VectorStore.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/memory/MemoryToolsTest.kt`

- [ ] Add failing tests for caller ownership and timestamp filters.
- [ ] Pass `userId` to search and add `createdBy`/numeric `createdAt` payload fields.
- [ ] Expose timestamp fields in the tool schema.

### Task 2: Serialize Qdrant range filters

**Files:**
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/memory/QdrantVectorStore.kt`
- Create: `domain/src/test/kotlin/com/homeassistant/domain/memory/QdrantVectorStoreTest.kt`

- [ ] Add failing JSON serialization tests for numeric payload and range conditions.
- [ ] Build Qdrant payload/search JSON with numeric metadata.
- [ ] Run domain tests and the full build.
- [ ] Commit the verified change.
