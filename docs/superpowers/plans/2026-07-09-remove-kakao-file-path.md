# Kakao Analyze Request Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove server-side file-path reading and rename the HTTP request metadata field from `fileName` to `sourceName`.

**Architecture:** Keep the existing HTTP endpoint and topic-analysis use case. Narrow only the route contract so it validates `sourceName` and `text`, then delegates the same `TopicAnalysisRequest`; Slack remains unchanged.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, kotlin.test, Gradle

---

### Task 1: Define the route contract with failing tests

**Files:**
- Modify: `app/src/test/kotlin/com/homeassistant/app/routes/KakaoImportRoutesTest.kt`

- [ ] **Step 1: Update the successful request test**

Change its body to:

```kotlin
setBody("""{"sourceName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
```

- [ ] **Step 2: Add legacy-field regression tests**

Add tests asserting that both requests return `HttpStatusCode.BadRequest` and leave `FakeAnalyzer.previewCalls` at zero:

```kotlin
setBody("""{"filePath":"settings.gradle.kts"}""")
```

```kotlin
setBody("""{"fileName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat :app:test --tests "com.homeassistant.app.routes.KakaoImportRoutesTest" --rerun-tasks
```

Expected: the updated success test fails because `sourceName` is unknown, and at least one legacy request is still accepted.

### Task 2: Remove filesystem input and rename the request field

**Files:**
- Modify: `app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt`
- Modify: `AGENTS.md`

- [ ] **Step 1: Implement the minimal route change**

Remove `java.nio.file.Files` and `java.nio.file.Path`. Replace route input extraction with:

```kotlin
val sourceName = req.sourceName
if (sourceName.isNullOrBlank()) {
    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sourceName is required"))
    return@post
}

val text = req.text
if (text.isNullOrBlank()) {
    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text is required"))
    return@post
}
```

Replace the private request DTO with:

```kotlin
@Serializable
private data class KakaoImportAnalyzeRequest(
    val sourceName: String? = null,
    val text: String? = null,
)
```

Pass `sourceName` to `TopicAnalysisRequest.sourceName`.

- [ ] **Step 2: Correct the route documentation**

Change the route description in `AGENTS.md` to:

```markdown
- `POST /api/kakao/import/analyze` -> analyzes supplied Kakao text content and returns pending topic candidates.
```

- [ ] **Step 3: Run focused tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:test --tests "com.homeassistant.app.routes.KakaoImportRoutesTest" --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

### Task 3: Verify the repository

**Files:**
- Verify all modified files

- [ ] **Step 1: Confirm legacy code is gone**

Run:

```powershell
rg -n "filePath|Files\.readString|Path\.of|fileName or filePath" app/src/main AGENTS.md
```

Expected: no matches related to the analyze route.

- [ ] **Step 2: Run the full build**

Run:

```powershell
.\gradlew.bat build --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Review the final diff**

Run:

```powershell
git diff --check
git diff -- app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt app/src/test/kotlin/com/homeassistant/app/routes/KakaoImportRoutesTest.kt AGENTS.md
```

Expected: no whitespace errors and only the approved request-contract changes.
