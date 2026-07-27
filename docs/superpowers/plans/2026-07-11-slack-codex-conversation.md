# Slack Codex Conversation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route household Slack DMs through Codex CLI threads that remain active for ten minutes while Kotlin retains identity, memory retrieval, idempotency, and session-expiry ownership.

**Architecture:** Add a Slack-specific session port to `domain`, implement it with Exposed in `repository`, and keep Codex process control and Slack interaction in `app/slack`. Each Slack member has at most one active-session pointer. A pointer is valid for ten minutes after `lastActiveAt`; an expired pointer is removed, the old thread is never resumed again, and the next DM starts a new Codex thread. Kotlin retrieves approved household context and passes it to an isolated non-interactive Codex process without exposing database access.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Exposed 0.57.0, SQLite 3.47.1.0, Slack Bolt/Socket Mode 1.49.0, Codex CLI 0.144.5 non-interactive JSONL protocol for the service, kotlin.test, MockK

## Global Constraints

- Accept only human-authored Slack direct messages from the configured `SLACK_TEAM_ID`.
- Resolve `(teamId, slackUserId)` through the server-owned `SLACK_MEMBER_SCOPES_JSON` mapping to an immutable `(userId, familyId)` access scope before any topic retrieval or Codex invocation. Slack message text, request payload fields, persisted Codex history, and Codex output must never choose or override either ID.
- Reject unmapped Slack members. Persist all four identity fields on a session and require an exact match when loading its active pointer so a changed mapping cannot inherit an older scope.
- Store `familyId` and `createdByUserId` on every topic. Filter both the vector search payload and the authoritative SQL hydration by `familyId`; authorize `userId` for that family before either search.
- Do not expose SQLite, Qdrant, repository files, service secrets, or household files to Codex.
- Run Codex with read-only sandbox, no interactive approvals, ignored personal config/rules, disabled web search, a dedicated working directory, and persistent `CODEX_HOME`.
- Never use `--ephemeral`; resumable Codex threads require persisted rollout state.
- Resume only the current member's active thread when `now - lastActiveAt < 10 minutes`; otherwise remove the active pointer and start a new thread.
- Automatic continuation inside the active ten-minute lease is the only allowed resume path. Never resume an expired, failed, historical, manually selected, or stale-recovery thread.
- Do not add `/brain` commands, session lists, manual session activation, or cleanup of expired thread files under `CODEX_HOME` in the MVP.
- Keep the service Codex CLI pinned at 0.144.5 in a version-specific installation directory. `CODEX_EXECUTABLE` must point to that absolute executable or isolated launcher; it must never resolve the operator's latest `codex` through `PATH`.
- The operator may install and update a separate latest Codex CLI for interactive use. It must use a different executable path and `CODEX_HOME` from the service.
- Serialize turns per Slack principal and deduplicate `(channelId, messageTs)` before Codex invocation.
- Persist `thread.started` before waiting for the final agent message.
- Store final answers as `ANSWER_READY` before Slack delivery so delivery can retry without rerunning Codex.
- Treat Slack delivery as successful only when `chat.postMessage` returns `ok=true` and a nonblank response timestamp. Keep the receipt in `ANSWER_READY` for all transport errors, `ok=false` responses, or missing timestamps.
- Preserve existing Kakao file analysis and topic-confirmation behavior.
- Do not add memory mutation tools, direct Codex database access, Slack channel conversations, or streaming output.

---

## File Structure

- `domain/.../slackconversation/SlackCodexSessionStore.kt`: persistence port and Slack principal/session/receipt domain types.
- `repository/.../tables/SlackCodexConversationTables.kt`: Exposed session, active-pointer, and receipt tables.
- `repository/.../slackconversation/SlackCodexSessionRepository.kt`: transactional store implementation.
- `app/.../slack/CodexConversationConfig.kt`: validated environment-backed runtime configuration.
- `app/.../slack/CodexConversationClient.kt`: process boundary and JSONL parser.
- `app/.../slack/HouseholdContextProvider.kt`: bounded approved-topic context formatting.
- `app/.../slack/SlackIdentityDirectory.kt`: server-owned Slack-to-household access mapping and authorization boundary.
- `app/.../slack/SlackConversationService.kt`: per-user serialization and message orchestration.
- `app/.../slack/SlackClient.kt` and `SlackWebApiClient.kt`: verified Slack message-delivery result.
- `app/.../slack/SlackDirectMessageIngress.kt`: pure Slack message filtering/mapping.
- `app/.../slack/SlackSocketRuntime.kt`: register the new listeners without changing existing file listeners.
- `app/.../Application.kt`: wire repositories, Codex client, context provider, and Slack conversation services.

---

### Task 1: Define The Session Port

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/slackconversation/SlackCodexSessionStore.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/slackconversation/SlackPrincipalTest.kt`

**Interfaces:**
- Consumes: `com.homeassistant.core.identity.UserId`.
- Produces: `SlackPrincipal`, `SlackCodexSession`, `SlackMessageKey`, `SlackMessageReceipt`, `SlackMessageReceiptStatus`, and `SlackCodexSessionStore`.

- [ ] **Step 1: Write the failing identity tests**

```kotlin
package com.homeassistant.domain.slackconversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlackPrincipalTest {
    @Test
    fun `principal derives deterministic internal user id`() {
        val principal = SlackPrincipal("T1", "U1")
        assertEquals("slack:T1:U1", principal.userId.value)
    }

    @Test
    fun `principal rejects blank identifiers`() {
        assertFailsWith<IllegalArgumentException> { SlackPrincipal("", "U1") }
        assertFailsWith<IllegalArgumentException> { SlackPrincipal("T1", "") }
    }
}
```

- [ ] **Step 2: Run the tests and verify the missing types fail compilation**

Run: `./gradlew :domain:test --tests '*SlackPrincipalTest'`

Expected: FAIL because `SlackPrincipal` does not exist.

- [ ] **Step 3: Add the exact domain types and store contract**

```kotlin
package com.homeassistant.domain.slackconversation

import com.homeassistant.core.identity.UserId

data class SlackPrincipal(
    val teamId: String,
    val slackUserId: String,
    val userId: UserId,
    val familyId: FamilyId,
) {
    init {
        require(teamId.isNotBlank()) { "teamId is required" }
        require(slackUserId.isNotBlank()) { "slackUserId is required" }
    }
}

data class SlackMessageKey(val channelId: String, val messageTs: String)

enum class SlackMessageReceiptStatus { PROCESSING, ANSWER_READY, COMPLETED, FAILED }

data class SlackCodexSession(
    val id: Int,
    val principal: SlackPrincipal,
    val codexThreadId: String,
    val createdAt: Long,
    val lastActiveAt: Long,
)

data class SlackMessageReceipt(
    val key: SlackMessageKey,
    val status: SlackMessageReceiptStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val responseTs: String? = null,
)

interface SlackCodexSessionStore {
    fun claimMessage(key: SlackMessageKey, now: Long): SlackMessageReceipt?
    fun receipt(key: SlackMessageKey): SlackMessageReceipt?
    fun attachSession(key: SlackMessageKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: SlackMessageKey, answer: String, now: Long)
    fun markCompleted(key: SlackMessageKey, responseTs: String?, now: Long)
    fun markFailed(key: SlackMessageKey, now: Long)
    fun createAndActivate(principal: SlackPrincipal, codexThreadId: String, now: Long): SlackCodexSession
    fun active(principal: SlackPrincipal, now: Long, idleTimeoutMillis: Long): SlackCodexSession?
    fun clearActive(principal: SlackPrincipal)
    fun touch(principal: SlackPrincipal, sessionId: Int, now: Long)
}
```

- [ ] **Step 4: Run the focused domain test**

Run: `./gradlew :domain:test --tests '*SlackPrincipalTest'`

Expected: PASS.

- [ ] **Step 5: Commit the port**

```bash
git add domain/src/main/kotlin/com/homeassistant/domain/slackconversation domain/src/test/kotlin/com/homeassistant/domain/slackconversation
git commit -m "feat: define Slack Codex session contract"
```

---

### Task 2: Persist Expiring Active Sessions And Receipts

**Files:**
- Create: `repository/src/main/kotlin/com/homeassistant/repository/db/tables/SlackCodexConversationTables.kt`
- Create: `repository/src/main/kotlin/com/homeassistant/repository/repo/slackconversation/SlackCodexSessionRepository.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/db/DatabaseFactory.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/RepositoryFactory.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/RepositoryStores.kt`
- Test: `repository/src/test/kotlin/com/homeassistant/repository/slackconversation/SlackCodexSessionRepositoryTest.kt`

**Interfaces:**
- Consumes: all Task 1 domain types.
- Produces: `SlackCodexSessionRepository(db: Database) : SlackCodexSessionStore` and `RepositoryStores.slackCodexSessions`.

- [ ] **Step 1: Write repository tests for ownership, active replacement, and idempotency**

```kotlin
@Test
fun `create replaces only the same principals active pointer`() {
    val dad = SlackPrincipal("T1", "U1")
    val mom = SlackPrincipal("T1", "U2")
    repo.createAndActivate(dad, "thread-1", 1)
    repo.createAndActivate(mom, "thread-2", 2)
    val newDad = repo.createAndActivate(dad, "thread-3", 3)

    assertEquals(newDad.id, repo.active(dad, 3, 600_000)?.id)
    assertEquals("thread-2", repo.active(mom, 3, 600_000)?.codexThreadId)
}

@Test
fun `duplicate message claim returns null and answer remains deliverable`() {
    val key = SlackMessageKey("D1", "100.1")
    assertNotNull(repo.claimMessage(key, 1))
    assertNull(repo.claimMessage(key, 2))
    repo.markAnswerReady(key, "answer", 3)
    assertEquals(SlackMessageReceiptStatus.ANSWER_READY, repo.receipt(key)?.status)
    assertEquals("answer", repo.receipt(key)?.answerText)
}

@Test
fun `active expires and removes a pointer after ten minutes`() {
    val principal = SlackPrincipal("T1", "U1")
    repo.createAndActivate(principal, "thread-1", 1)
    assertNotNull(repo.active(principal, 600_000, 600_000))
    assertNull(repo.active(principal, 600_001, 600_000))
    assertNull(repo.active(principal, 2, 600_000))
}
```

- [ ] **Step 2: Run the repository test and verify it fails**

Run: `./gradlew :repository:test --tests '*SlackCodexSessionRepositoryTest'`

Expected: FAIL because the repository and tables do not exist.

- [ ] **Step 3: Add the three Exposed tables**

```kotlin
internal object SlackCodexSessionTable : Table("slack_codex_sessions") {
    val id = integer("id").autoIncrement()
    val teamId = text("team_id")
    val slackUserId = text("slack_user_id")
    val userId = text("user_id")
    val familyId = text("family_id")
    val codexThreadId = text("codex_thread_id").uniqueIndex()
    val createdAt = long("created_at")
    val lastActiveAt = long("last_active_at")
    override val primaryKey = PrimaryKey(id)
}

internal object SlackCodexActiveSessionTable : Table("slack_codex_active_sessions") {
    val teamId = text("team_id")
    val slackUserId = text("slack_user_id")
    val sessionId = integer("session_id").references(SlackCodexSessionTable.id)
    override val primaryKey = PrimaryKey(teamId, slackUserId)
}

internal object SlackMessageReceiptTable : Table("slack_message_receipts") {
    val channelId = text("channel_id")
    val messageTs = text("message_ts")
    val sessionId = integer("session_id").references(SlackCodexSessionTable.id).nullable()
    val status = text("status")
    val answerText = text("answer_text").nullable()
    val responseTs = text("response_ts").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(channelId, messageTs)
}
```

- [ ] **Step 4: Implement repository transactions and register the tables/store**

Use `transaction(db)` for every method. `createAndActivate` must insert the complete immutable principal scope, delete the principal's previous pointer, and insert the new pointer in one transaction. `active` must select by pointer and all four principal fields; a row created under a different `userId` or `familyId` mapping is never resumed. When `now - last_active_at >= idleTimeoutMillis`, it must delete the pointer in the same transaction and return `null`; it does not delete the session row or files under `CODEX_HOME`. `claimMessage` must catch only the unique-key collision and return `null`; other SQL failures propagate.

Add all three tables to `DatabaseFactory.init`, expose `slackCodexSessions: SlackCodexSessionStore` from `RepositoryStores`, and construct `SlackCodexSessionRepository(db)` in `RepositoryFactory`.

- [ ] **Step 5: Run repository tests**

Run: `./gradlew :repository:test --tests '*SlackCodexSessionRepositoryTest'`

Expected: PASS for active replacement, ten-minute expiry, ownership isolation, receipt transitions, and duplicate claims.

- [ ] **Step 6: Commit persistence**

```bash
git add repository/src/main/kotlin repository/src/test/kotlin/com/homeassistant/repository/slackconversation
git commit -m "feat: persist Slack Codex sessions"
```

---

### Task 3: Add The Codex Non-Interactive Process Client

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/CodexConversationConfig.kt`
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/CodexConversationClient.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/CodexConversationConfigTest.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/CodexConversationClientTest.kt`

**Interfaces:**
- Produces: `CodexConversationConfig`, `CodexConversationClient`, `ProcessCodexConversationClient`, and `CodexTurnResult`.

- [ ] **Step 1: Write failing configuration and JSONL protocol tests**

```kotlin
@Test
fun `start reports thread before final answer`() {
    val events = mutableListOf<String>()
    val client = ProcessCodexConversationClient(config(fakeCodex("success")))
    val result = client.start("hello") { events += it }
    assertEquals(listOf("thread-123"), events)
    assertEquals(CodexTurnResult.Success("answer"), result)
}

@Test
fun `resume passes an exact thread id and prompt through stdin`() {
    val client = ProcessCodexConversationClient(config(fakeCodex("record-args")))
    assertEquals(CodexTurnResult.Success("resumed"), client.resume("thread-123", "next"))
    assertEquals("next", recordedStdin())
    assertContains(recordedArgs(), "resume")
    assertContains(recordedArgs(), "thread-123")
}

@Test
fun `malformed output returns protocol failure`() {
    val client = ProcessCodexConversationClient(config(fakeCodex("malformed")))
    assertIs<CodexTurnResult.Failure>(client.start("hello") {})
}
```

Also verify that the configured service version is accepted, a different executable version disables conversation startup, and neither case resolves an executable through `PATH`.

- [ ] **Step 2: Run the focused tests and verify missing types fail compilation**

Run: `./gradlew :app:test --tests '*CodexConversationConfigTest' --tests '*CodexConversationClientTest'`

Expected: FAIL because the Codex client does not exist.

- [ ] **Step 3: Implement validated config and result contract**

```kotlin
data class CodexConversationConfig(
    val executable: String,
    val expectedVersion: String,
    val workDir: Path,
    val codexHome: Path,
    val apiKey: String,
    val timeout: Duration,
) {
    companion object {
        fun from(readEnv: (String) -> String? = { Env[it] }): CodexConversationConfig?
    }
}

sealed interface CodexTurnResult {
    data class Success(val answer: String) : CodexTurnResult
    data class Failure(val category: String) : CodexTurnResult
}

interface CodexConversationClient {
    fun start(prompt: String, onThreadStarted: (String) -> Unit): CodexTurnResult
    fun resume(threadId: String, prompt: String): CodexTurnResult
}
```

`CodexConversationConfig.fromEnv()` must require `CODEX_EXECUTABLE`, `CODEX_EXPECTED_VERSION`, `CODEX_API_KEY`, `CODEX_WORK_DIR`, and `CODEX_HOME`; default only `CODEX_TIMEOUT_SECONDS` to `120`. For the initial deployment, `CODEX_EXPECTED_VERSION` is `0.144.5`. `CODEX_EXECUTABLE` is the operator-provided version-specific isolated launcher, not an implicit `codex` binary resolved through `PATH`. Configuration must reject a missing executable, missing directories, a work directory containing `db/homeAssistant.sqlite`, and non-positive timeouts so conversation startup fails closed. Before enabling Slack conversation listeners, invoke `<CODEX_EXECUTABLE> --version` without a shell and require the parsed version to equal `CODEX_EXPECTED_VERSION`; a mismatch disables only the conversation listener with a version-mismatch reason.

The interactive operator CLI is outside this configuration. It may track the latest stable release independently and must use a separate executable path and `CODEX_HOME`.

- [ ] **Step 4: Implement `ProcessCodexConversationClient` without a shell**

For new sessions, construct this argument list with `ProcessBuilder`:

```text
<CODEX_EXECUTABLE> exec --json --sandbox read-only --skip-git-repo-check
--ignore-user-config --ignore-rules
-c approval_policy="never" -c web_search="disabled" -C <workDir> -
```

For resume, construct:

```text
<CODEX_EXECUTABLE> exec resume --json --skip-git-repo-check
--ignore-user-config --ignore-rules
-c approval_policy="never" -c sandbox_mode="read-only" -c web_search="disabled"
<threadId> -
```

Map the service's `CODEX_API_KEY` secret to `OPENAI_API_KEY` only in the child process, set the child process's `CODEX_HOME`, write the prompt to stdin, read stdout one JSON object per line with `JsonSerializer.json`, invoke `onThreadStarted` immediately for `thread.started`, retain the last completed `agent_message`, and return only failure categories such as `START_FAILED`, `TIMEOUT`, `EXIT_<code>`, or `INVALID_JSONL`. Drain stderr concurrently into a bounded in-memory buffer, log only a category without its contents, and never return it to Slack. On timeout, call `process.descendants().forEach(ProcessHandle::destroyForcibly)` and then `process.destroyForcibly()`.

- [ ] **Step 5: Run the Codex client tests**

Run: `./gradlew :app:test --tests '*CodexConversationConfigTest' --tests '*CodexConversationClientTest'`

Expected: PASS without contacting OpenAI.

- [ ] **Step 6: Commit the process boundary**

```bash
git add app/src/main/kotlin/com/homeassistant/app/slack/CodexConversationConfig.kt app/src/main/kotlin/com/homeassistant/app/slack/CodexConversationClient.kt app/src/test/kotlin/com/homeassistant/app/slack/CodexConversationConfigTest.kt app/src/test/kotlin/com/homeassistant/app/slack/CodexConversationClientTest.kt
git commit -m "feat: run resumable Codex conversations"
```

---

### Task 4: Orchestrate Context, Idempotency, And Delivery

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackIdentityDirectory.kt`
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/HouseholdContextProvider.kt`
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackConversationService.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackClient.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackWebApiClient.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackIdentityDirectoryTest.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackWebApiClientTest.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/HouseholdContextProviderTest.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackConversationServiceTest.kt`

**Interfaces:**
- Consumes: `SlackIdentityDirectory`, `TopicAnswerUseCase`, `SlackCodexSessionStore`, `CodexConversationClient`, and `SlackClient`.
- Produces: `SlackConversationMessage` and `SlackConversationService.handle(message)`.

- [ ] **Step 1: Write failing orchestration tests**

```kotlin
@Test
fun `first message persists thread and answer before Slack delivery`() {
    val service = service(codex = FakeCodex(startThread = "thread-1", answer = "답변"))
    service.handle(message("100.1"))
    assertEquals("thread-1", store.active(principal, now(), SESSION_IDLE_TIMEOUT_MILLIS)?.codexThreadId)
    assertEquals(SlackMessageReceiptStatus.COMPLETED, store.receipt(key("100.1"))?.status)
    assertEquals("답변", slack.messages.single().text)
}

@Test
fun `follow up within ten minutes resumes active thread`() {
    store.createAndActivate(principal, "thread-1", now() - 599_999)
    service().handle(message("100.2"))
    assertEquals("thread-1", codex.resumedThreadId)
}

@Test
fun `follow up after ten minutes starts a new thread`() {
    store.createAndActivate(principal, "thread-1", now() - 600_000)
    service(codex = FakeCodex(startThread = "thread-2", answer = "새 답변")).handle(message("100.4"))
    assertEquals("thread-2", store.active(principal, now(), SESSION_IDLE_TIMEOUT_MILLIS)?.codexThreadId)
}

@Test
fun `answer ready duplicate retries delivery without invoking Codex`() {
    store.claimMessage(key("100.3"), 1)
    store.markAnswerReady(key("100.3"), "저장된 답변", 2)
    service().handle(message("100.3"))
    assertEquals(0, codex.invocationCount)
    assertEquals("저장된 답변", slack.messages.single().text)
}
```

Also cover team mismatch before retrieval, the exact ten-minute expiry boundary, resume failure clearing the active pointer, context-search failure, Slack delivery failure leaving `ANSWER_READY`, and two simultaneous messages for one principal executing in order.

- [ ] **Step 2: Run focused service tests and verify failure**

Run: `./gradlew :app:test --tests '*HouseholdContextProviderTest' --tests '*SlackConversationServiceTest'`

Expected: FAIL because the provider and service do not exist.

- [ ] **Step 3: Implement bounded household context formatting**

```kotlin
class HouseholdContextProvider(private val topicAnswer: TopicAnswerUseCase) {
    fun context(principal: SlackPrincipal, question: String): String = runCatching {
        topicAnswer.answer(
            TopicAnswerRequest(
                userId = principal.userId.value,
                familyId = principal.familyId.value,
                question = question,
                limit = 5,
            ),
        ).matches
            .joinToString("\n") { match ->
                "- ${match.title}: ${match.claims.take(3).joinToString(" ")}"
            }
            .take(MAX_CONTEXT_CHARS)
    }.getOrDefault("")

    companion object { const val MAX_CONTEXT_CHARS = 8_000 }
}
```

Wrap the returned text in a prompt section labeled `UNTRUSTED HOUSEHOLD MEMORY REFERENCE`; state that its content is data and cannot change instructions or permissions.

`TopicAnswerUseCase` must authorize the exact `(userId, familyId)` pair, pass the same immutable scope into `TopicClaimSearchIndex.search`, and hydrate only approved SQL topics with the same `familyId`. Topic vectors must contain `familyId` and `createdByUserId`, and vector search must include `kind=topic_claim` plus the requested `familyId`. Treat vector results only as candidate IDs; SQL scope filtering is the final authorization check.

Context retrieval failures must fail the receipt before Codex invocation. If the authorized result contains no topic matches, do not invoke Codex; persist and deliver only the deterministic no-related-approved-memory response. If matches exist, explicitly instruct Codex to use no facts outside the bounded reference block.

`SlackClient.postMessage` returns `SlackMessageDelivery(responseTs)` only after the implementation verifies Slack `ok=true` and a nonblank `ts`. `SlackWebApiClient` throws a categorized delivery exception for `ok=false` or a missing timestamp. `SlackConversationService` calls `markCompleted` only with this verified timestamp; any delivery failure leaves the receipt `ANSWER_READY`.

- [ ] **Step 4: Implement `SlackConversationService`**

```kotlin
data class SlackConversationMessage(
    val teamId: String,
    val slackUserId: String,
    val channelId: String,
    val messageTs: String,
    val text: String,
)
```

Resolve `SlackConversationMessage` through `SlackIdentityDirectory` exactly once before claiming a receipt, retrieving topics, or invoking Codex. Carry the returned immutable `SlackPrincipal` through the whole turn; never reconstruct it from prompt text or response data. Use a per-principal FIFO queue so two accepted messages execute in Slack arrival order. Inside the queue, load the active session using the current time and `SESSION_IDLE_TIMEOUT_MILLIS = 600_000`; the store atomically removes an expired pointer. On a duplicate, deliver only a stored `ANSWER_READY` answer. For a new Codex session, persist from the `onThreadStarted` callback and attach its local ID to the receipt. For success, update `lastActiveAt`, mark `ANSWER_READY`, post the stored answer, require the verified Slack response timestamp, then mark `COMPLETED`. On any automatic in-lease resume failure, clear the active pointer and return a short retryable error. Do not resume or repair that thread; the next DM starts a new thread. When the ten-minute lease expires, clear the active pointer and treat the old thread as permanently ended from the application's perspective.

- [ ] **Step 5: Run focused service tests**

Run: `./gradlew :app:test --tests '*HouseholdContextProviderTest' --tests '*SlackConversationServiceTest'`

Expected: PASS.

- [ ] **Step 6: Commit orchestration**

```bash
git add app/src/main/kotlin/com/homeassistant/app/slack/HouseholdContextProvider.kt app/src/main/kotlin/com/homeassistant/app/slack/SlackConversationService.kt app/src/test/kotlin/com/homeassistant/app/slack/HouseholdContextProviderTest.kt app/src/test/kotlin/com/homeassistant/app/slack/SlackConversationServiceTest.kt
git commit -m "feat: orchestrate Slack Codex turns"
```

---

### Task 5: Register DM Listeners

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackDirectMessageIngress.kt`
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackConversationListeners.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackSocketRuntime.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackDirectMessageIngressTest.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackConversationListenersTest.kt`

**Interfaces:**
- Consumes: Slack `MessageEvent` and `SlackConversationService`.
- Produces: listener registration through `SlackConversationListeners.register(app)`.

- [ ] **Step 1: Write failing ingress tests**

```kotlin
@Test
fun `maps a human dm into a conversation message`() {
    val event = MessageEvent().apply {
        user = "U1"; channel = "D1"; channelType = "im"
        ts = "100.1"; text = "차단기 리모컨 어디 있어?"
    }
    val message = SlackDirectMessageIngress.from("T1", event)
    assertEquals(SlackPrincipal("T1", "U1"), message?.principal)
}

@Test
fun `ignores bot channel edit delete and blank messages`() {
    assertNull(SlackDirectMessageIngress.from("T1", botMessage()))
    assertNull(SlackDirectMessageIngress.from("T1", channelMessage()))
    assertNull(SlackDirectMessageIngress.from("T1", editedMessage()))
    assertNull(SlackDirectMessageIngress.from("T1", blankMessage()))
}
```

- [ ] **Step 2: Run ingress/listener tests and verify failure**

Run: `./gradlew :app:test --tests '*SlackDirectMessageIngressTest' --tests '*SlackConversationListenersTest'`

Expected: FAIL because ingress and listeners do not exist.

- [ ] **Step 3: Implement pure message filtering and listener registration**

`SlackDirectMessageIngress.from(teamId, event)` must require `channelType == "im"`, nonblank user/channel/ts/text, blank `botId`, and no subtype other than a normal human message. It must not accept file-share events, which remain handled by `MessageFileShareEvent`.

`SlackConversationListeners.register(app)` must register:

```kotlin
app.event(MessageEvent::class.java) { payload, ctx ->
    SlackDirectMessageIngress.from(payload.teamId, payload.event)?.let { message ->
        executor.submit { conversationService.handle(message) }
    }
    ctx.ack()
}

```

Handlers must acknowledge before waiting for Codex, database retrieval, or Slack Web API calls. Extend `SlackSocketRuntime` by constructing/registering this listener object; leave current file-share and topic-confirmation listeners unchanged.

- [ ] **Step 4: Run listener tests and existing Slack tests**

Run: `./gradlew :app:test --tests '*SlackDirectMessageIngressTest' --tests '*SlackConversationListenersTest' --tests '*SlackFileIngressTest' --tests '*SlackKakaoAnalysisWorkflowTest' --tests '*SlackConfirmationHandlersTest'`

Expected: PASS and no duplicate handling of file-share events.

- [ ] **Step 5: Commit Slack listeners**

```bash
git add app/src/main/kotlin/com/homeassistant/app/slack/SlackDirectMessageIngress.kt app/src/main/kotlin/com/homeassistant/app/slack/SlackConversationListeners.kt app/src/main/kotlin/com/homeassistant/app/slack/SlackSocketRuntime.kt app/src/test/kotlin/com/homeassistant/app/slack/SlackDirectMessageIngressTest.kt app/src/test/kotlin/com/homeassistant/app/slack/SlackConversationListenersTest.kt
git commit -m "feat: receive conversational Slack DMs"
```

---

### Task 6: Wire Configuration And Verify The End-To-End Boundary

**Files:**
- Modify: `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Modify: `AGENTS.md`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackConversationWiringTest.kt`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: optional conversation startup when Slack and Codex configuration are complete.

- [ ] **Step 1: Write a failing wiring test**

The test must build the Slack conversation graph with fake `SlackCodexSessionStore`, `CodexConversationClient`, `TopicAnswerUseCase`, and `SlackClient`. Two messages less than ten minutes apart for one principal must use the same parsed thread; a message at least ten minutes later must start a new thread. A second principal must start a different thread. No real Slack, Qdrant, Codex, or OpenAI call is allowed.

- [ ] **Step 2: Run the wiring test and verify failure**

Run: `./gradlew :app:test --tests '*SlackConversationWiringTest'`

Expected: FAIL because application wiring and configuration constants are incomplete.

- [ ] **Step 3: Add configuration constants and application wiring**

Add these exact environment keys to `AppConfig`:

```kotlin
const val ENV_VAR_SLACK_TEAM_ID = "SLACK_TEAM_ID"
const val ENV_VAR_SLACK_MEMBER_SCOPES_JSON = "SLACK_MEMBER_SCOPES_JSON"
const val ENV_VAR_CODEX_EXECUTABLE = "CODEX_EXECUTABLE"
const val ENV_VAR_CODEX_EXPECTED_VERSION = "CODEX_EXPECTED_VERSION"
const val ENV_VAR_CODEX_WORK_DIR = "CODEX_WORK_DIR"
const val ENV_VAR_CODEX_HOME = "CODEX_HOME"
const val ENV_VAR_CODEX_API_KEY = "CODEX_API_KEY"
const val ENV_VAR_CODEX_TIMEOUT_SECONDS = "CODEX_TIMEOUT_SECONDS"
const val DEFAULT_CODEX_TIMEOUT_SECONDS = 120L
```

`SLACK_MEMBER_SCOPES_JSON` is a server-owned JSON array of `{teamId, slackUserId, userId, familyId}` records. Reject duplicates, blanks, team mismatches, and an empty mapping. Fail closed for all Slack workflows unless the app token, bot token, configured team, and member mapping validate; reject file analysis from unmapped members and stamp created topics with the resolved immutable scope. Enable conversational listeners only when the pinned Codex version check and all required Codex values also validate. Log a single reason category when conversation startup is disabled; never log tokens, mappings, prompts, household context, or raw Codex stderr.

- [ ] **Step 4: Document runtime setup in `AGENTS.md`**

Add the eight variables above to the environment table. State that `CODEX_EXECUTABLE` is an absolute version-specific service executable, `CODEX_EXPECTED_VERSION` pins the tested service protocol, `CODEX_WORK_DIR` must be a dedicated minimal directory, `CODEX_HOME` must be persistent and service-only, `CODEX_API_KEY` is passed only to the child process, and production must place the configured executable behind an OS/container isolation boundary that cannot read the application DB or household files. Document that the operator's interactive latest CLI uses a different path and `CODEX_HOME`.

- [ ] **Step 5: Run focused wiring and all Slack tests**

Run: `./gradlew :app:test --tests '*SlackConversationWiringTest' --tests 'com.homeassistant.app.slack.*'`

Expected: PASS.

- [ ] **Step 6: Run full verification**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL with all module tests passing.

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL with compilation, tests, and packaging passing.

- [ ] **Step 7: Inspect the final diff for scope and secrets**

Run: `git diff --check`

Expected: no output.

Run: `git status --short`

Expected: only the files named in this plan are modified or untracked; no `.env`, token, Codex session, SQLite, or Qdrant data file appears.

- [ ] **Step 8: Commit the wiring and documentation**

```bash
git add core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt app/src/main/kotlin/com/homeassistant/app/Application.kt app/src/test/kotlin/com/homeassistant/app/slack/SlackConversationWiringTest.kt AGENTS.md
git commit -m "feat: wire Slack Codex conversations"
```
