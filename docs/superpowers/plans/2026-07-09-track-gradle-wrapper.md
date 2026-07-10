# Track Gradle Wrapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a fresh Git checkout able to resolve the Gradle wrapper and version catalog.

**Architecture:** Keep build-system inputs in Git while continuing to ignore Gradle's generated `.gradle/` cache. Verify from files exported directly from Git so local ignored files cannot mask omissions.

**Tech Stack:** Gradle wrapper, Kotlin DSL, Git

---

### Task 1: Track Gradle bootstrap files

**Files:**
- Modify: `.gitignore`
- Add: `gradle/wrapper/gradle-wrapper.jar`
- Add: `gradle/wrapper/gradle-wrapper.properties`
- Add: `gradle/libs.versions.toml`

- [ ] Remove the `gradle/` ignore rule while retaining `.gradle/`.
- [ ] Add the wrapper JAR, wrapper properties, and version catalog to Git.
- [ ] Run `git ls-files gradle` and verify all three files are listed.
- [ ] Export `HEAD` to a temporary directory and run `.\gradlew.bat help`.
- [ ] Commit the verified change.
