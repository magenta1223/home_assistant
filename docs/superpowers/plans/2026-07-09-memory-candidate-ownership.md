# Memory Candidate Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permit candidate approval and rejection only by the creator while the candidate is pending.

**Architecture:** Enforce ownership and lifecycle predicates inside the repository transaction. Failed transitions must not mutate candidate state, create memories, or append audit records.

**Tech Stack:** Kotlin, Exposed, SQLite, kotlin.test

---

### Task 1: Guard candidate transitions

**Files:**
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/memory/MemoryRepository.kt`
- Test: `repository/src/test/kotlin/com/homeassistant/repository/memory/MemoryRepositoryTest.kt`

- [ ] Add failing tests for cross-user approval and rejection.
- [ ] Add a failing test for rejecting an already approved candidate.
- [ ] Run `.\gradlew.bat :repository:test` and confirm the new tests fail for the unsafe behavior.
- [ ] Add creator and `PENDING` predicates to approval/rejection.
- [ ] Re-run repository tests and the full build.
- [ ] Commit the verified change.
