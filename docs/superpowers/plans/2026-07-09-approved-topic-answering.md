# Approved Topic Answering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a purpose-built API that answers questions from approved `topic_candidates` and their stored claims.

**Architecture:** Do not reintroduce `/api/chat` or conversation sessions. Add a deterministic topic-answering use case that searches `APPROVED` topics, selects relevant claims, and returns an evidence-backed answer. Keep the first version extractive and DB-grounded; do not call an LLM until the retrieval contract is tested and stable.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, Exposed, SQLite, Gradle tests.

---

## File Structure

- Modify `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`
  - Add `ROUTE_TOPIC_ANSWER = "/api/topics/answer"`.
- Modify `domain/src/main/kotlin/com/homeassistant/domain/topicanalysis/TopicAnalysisStore.kt`
  - Add a read method for approved topics: `searchApprovedTopics(query: String, limit: Int): List<Topic>`.
- Create `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerModels.kt`
  - Request/response models for the answering API.
- Create `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerUseCase.kt`
  - Interface for app routing.
- Create `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerService.kt`
  - Deterministic answer builder using `TopicAnalysisStore`.
- Modify `repository/src/main/kotlin/com/homeassistant/repository/repo/topicanalysis/TopicAnalysisRepository.kt`
  - Implement approved-topic search.
- Modify `app/src/main/kotlin/com/homeassistant/app/Application.kt`
  - Wire `TopicAnswerService`.
- Modify `app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt`
  - Add `POST /api/topics/answer`.
- Add tests:
  - `repository/src/test/kotlin/com/homeassistant/repository/topicanalysis/TopicAnalysisRepositorySearchTest.kt`
  - `domain/src/test/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerServiceTest.kt`
  - `app/src/test/kotlin/com/homeassistant/app/routes/TopicAnswerRoutesTest.kt`

---

## Behavior Contract

The endpoint answers only from approved topic data.

Request:

```json
{
  "question": "주차장 차단기 리모컨 어디 있어?",
  "limit": 5
}
```

Response when matches exist:

```json
{
  "question": "주차장 차단기 리모컨 어디 있어?",
  "answer": "저장된 기억 기준으로는 주차장 차단기 리모컨은 벽장 제일 위칸에 있고, 천장등 리모컨도 함께 있습니다.",
  "matches": [
    {
      "topicId": 17,
      "title": "집 물건 위치·정리 체크리스트·생활용품",
      "summary": "리모컨과 개 이동장 위치, 집안일 체크리스트, 팬트리 최종안, 생활용품 구매 요청이 집 정리와 물품 관리 주제로 묶입니다.",
      "claims": [
        "주차장 차단기 리모컨은 벽장 제일 위칸에 올려두었고, 천장등 리모컨도 함께 있다고 홍승민이 말했다."
      ],
      "evidenceRefs": [2048]
    }
  ]
}
```

Response when no approved match exists:

```json
{
  "question": "없는 질문",
  "answer": "승인된 기억에서 관련 내용을 찾지 못했습니다.",
  "matches": []
}
```

Validation:

- Blank `question` returns `400 Bad Request`.
- `limit` is clamped to `1..10`.
- Only `CandidateStatus.APPROVED` topics are eligible.
- `PENDING` and `REJECTED` topics are never returned.

---

## Task 1: Repository Search For Approved Topics

**Files:**
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/topicanalysis/TopicAnalysisStore.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/topicanalysis/TopicAnalysisRepository.kt`
- Test: `repository/src/test/kotlin/com/homeassistant/repository/topicanalysis/TopicAnalysisRepositorySearchTest.kt`

- [ ] **Step 1: Write the failing repository test**

Create `repository/src/test/kotlin/com/homeassistant/repository/topicanalysis/TopicAnalysisRepositorySearchTest.kt`:

```kotlin
package com.homeassistant.repository.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.repository.db.tables.TopicCandidateTable
import com.homeassistant.repository.repo.topicanalysis.TopicAnalysisRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TopicAnalysisRepositorySearchTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicAnalysisRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(TopicCandidateTable)
        }
        repository = TopicAnalysisRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `search approved topics returns matching approved topic claims`() {
        val remote = repository.createTopic(topic("주차장 리모컨 위치", "주차장 차단기 리모컨은 벽장 제일 위칸에 있다.", 10))
        repository.createTopic(topic("점심 기록", "점심으로 쭈꾸미 덮밥을 먹었다.", 20))

        val results = repository.searchApprovedTopics("차단기 리모컨 어디", limit = 5)

        assertEquals(listOf(remote.id), results.map { it.id })
        assertEquals("주차장 리모컨 위치", results.single().title)
        assertEquals("주차장 차단기 리모컨은 벽장 제일 위칸에 있다.", results.single().claims.single().text)
    }

    @Test
    fun `search approved topics excludes pending topics`() {
        val pending = repository.createTopic(topic("세콤 경비 규칙", "개가 있으면 세콤 경비상태에서 움직임 감지가 될 수 있다.", 30))
        transaction(db) {
            TopicCandidateTable.update({ TopicCandidateTable.id eq pending.id }) {
                it[status] = CandidateStatus.PENDING.name
            }
        }

        val results = repository.searchApprovedTopics("세콤 경비", limit = 5)

        assertEquals(emptyList(), results)
    }

    @Test
    fun `search approved topics clamps limit to ten`() {
        repeat(12) { index ->
            repository.createTopic(topic("리모컨 후보 $index", "리모컨 관련 claim $index", index + 1))
        }

        val results = repository.searchApprovedTopics("리모컨", limit = 50)

        assertEquals(10, results.size)
    }

    private fun topic(title: String, claimText: String, evidenceRef: Int) =
        TopicCandidate(
            sourceType = "kakao",
            sourceName = "family-kakao.txt",
            title = title,
            summary = "$title 요약",
            memoryTypes = listOf(MemoryType.REFERENCE),
            domains = listOf("home"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(
                TopicClaimCandidate(
                    text = claimText,
                    subject = title,
                    memoryType = MemoryType.REFERENCE,
                    certainty = ClaimCertainty.SAID,
                    evidenceRefs = listOf(evidenceRef),
                ),
            ),
        )
}
```

- [ ] **Step 2: Run the failing repository test**

Run:

```powershell
.\gradlew.bat :repository:test --tests "com.homeassistant.repository.topicanalysis.TopicAnalysisRepositorySearchTest"
```

Expected: compile failure because `TopicAnalysisStore.searchApprovedTopics` does not exist.

- [ ] **Step 3: Extend the store interface**

Modify `domain/src/main/kotlin/com/homeassistant/domain/topicanalysis/TopicAnalysisStore.kt`:

```kotlin
package com.homeassistant.domain.topicanalysis

import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate

interface TopicAnalysisStore {
    fun createTopic(candidate: TopicCandidate): Topic
    fun searchApprovedTopics(query: String, limit: Int): List<Topic>
}
```

- [ ] **Step 4: Implement repository search**

Add to `repository/src/main/kotlin/com/homeassistant/repository/repo/topicanalysis/TopicAnalysisRepository.kt` inside `TopicAnalysisRepository`:

```kotlin
    override fun searchApprovedTopics(query: String, limit: Int): List<Topic> = transaction(db) {
        val boundedLimit = limit.coerceIn(1, 10)
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return@transaction emptyList()

        TopicCandidateTable.selectAll()
            .where { TopicCandidateTable.status eq CandidateStatus.APPROVED.name }
            .map { row -> getTopic(row[TopicCandidateTable.id]) }
            .mapNotNull { topic ->
                val score = scoreTopic(topic, queryTokens)
                if (score <= 0) null else ScoredTopic(topic, score)
            }
            .sortedWith(compareByDescending<ScoredTopic> { it.score }.thenBy { it.topic.id })
            .take(boundedLimit)
            .map { it.topic }
    }

    private fun tokenize(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()

    private fun scoreTopic(topic: Topic, queryTokens: Set<String>): Int {
        val title = topic.title.lowercase()
        val summary = topic.summary.lowercase()
        val claims = topic.claims.joinToString(" ") { it.text }.lowercase()
        return queryTokens.sumOf { token ->
            var score = 0
            if (title.contains(token)) score += 4
            if (summary.contains(token)) score += 2
            if (claims.contains(token)) score += 3
            score
        }
    }

    private data class ScoredTopic(val topic: Topic, val score: Int)
```

- [ ] **Step 5: Update fake implementations**

Modify fake `TopicAnalysisStore` implementations in tests that now fail to compile, especially:

- `nlp/src/test/kotlin/com/homeassistant/nlp/analysis/KakaoMessageTopicAnalysisServiceTest.kt`

Add this method to `FakeTopicStore`:

```kotlin
    override fun searchApprovedTopics(query: String, limit: Int): List<Topic> =
        emptyList()
```

- [ ] **Step 6: Run repository test**

Run:

```powershell
.\gradlew.bat :repository:test --tests "com.homeassistant.repository.topicanalysis.TopicAnalysisRepositorySearchTest"
```

Expected: pass.

- [ ] **Step 7: Commit**

Run:

```powershell
git add domain/src/main/kotlin/com/homeassistant/domain/topicanalysis/TopicAnalysisStore.kt repository/src/main/kotlin/com/homeassistant/repository/repo/topicanalysis/TopicAnalysisRepository.kt repository/src/test/kotlin/com/homeassistant/repository/topicanalysis/TopicAnalysisRepositorySearchTest.kt nlp/src/test/kotlin/com/homeassistant/nlp/analysis/KakaoMessageTopicAnalysisServiceTest.kt
git commit -m "Search approved topic candidates"
```

---

## Task 2: Domain Answer Service

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerModels.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerUseCase.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerService.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerServiceTest.kt`

- [ ] **Step 1: Write the failing service test**

Create `domain/src/test/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerServiceTest.kt`:

```kotlin
package com.homeassistant.domain.topicanswer

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicAnswerServiceTest {
    @Test
    fun `answers from approved topic claims`() {
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(
                listOf(topic(id = 7, title = "집 물건 위치", claimText = "주차장 차단기 리모컨은 벽장 제일 위칸에 있다."))
            )
        )

        val result = service.answer(TopicAnswerRequest(question = "차단기 리모컨 어디 있어?", limit = 5))

        assertTrue(result.answer.contains("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."))
        assertEquals(1, result.matches.size)
        assertEquals(7, result.matches.single().topicId)
        assertEquals(listOf("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."), result.matches.single().claims)
    }

    @Test
    fun `returns no match answer when approved topics do not match`() {
        val service = TopicAnswerService(topicStore = FakeTopicStore(emptyList()))

        val result = service.answer(TopicAnswerRequest(question = "없는 질문", limit = 5))

        assertEquals("승인된 기억에서 관련 내용을 찾지 못했습니다.", result.answer)
        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `clamps requested limit`() {
        val service = TopicAnswerService(topicStore = FakeTopicStore(List(12) { topic(it + 1, "후보 $it", "리모컨 claim $it") }))

        val result = service.answer(TopicAnswerRequest(question = "리모컨", limit = 50))

        assertEquals(10, result.matches.size)
    }
}

private class FakeTopicStore(private val topics: List<Topic>) : TopicAnalysisStore {
    override fun createTopic(candidate: TopicCandidate): Topic =
        error("not used")

    override fun searchApprovedTopics(query: String, limit: Int): List<Topic> =
        topics.take(limit.coerceIn(1, 10))
}

private fun topic(id: Int, title: String, claimText: String) =
    Topic(
        id = id,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = title,
        summary = "$title 요약",
        memoryTypes = listOf(MemoryType.REFERENCE),
        domains = listOf("home"),
        evidenceRefs = listOf(id * 10),
        claims = listOf(
            TopicClaim(
                id = 1,
                text = claimText,
                subject = title,
                memoryType = MemoryType.REFERENCE,
                certainty = ClaimCertainty.SAID,
                evidenceRefs = listOf(id * 10),
            ),
        ),
        status = CandidateStatus.APPROVED,
    )
```

- [ ] **Step 2: Run the failing service test**

Run:

```powershell
.\gradlew.bat :domain:test --tests "com.homeassistant.domain.topicanswer.TopicAnswerServiceTest"
```

Expected: compile failure because answer models and service do not exist.

- [ ] **Step 3: Add answer models**

Create `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerModels.kt`:

```kotlin
package com.homeassistant.domain.topicanswer

import kotlinx.serialization.Serializable

@Serializable
data class TopicAnswerRequest(
    val question: String,
    val limit: Int = 5,
)

@Serializable
data class TopicAnswerResult(
    val question: String,
    val answer: String,
    val matches: List<TopicAnswerMatch>,
)

@Serializable
data class TopicAnswerMatch(
    val topicId: Int,
    val title: String,
    val summary: String,
    val claims: List<String>,
    val evidenceRefs: List<Int>,
)
```

- [ ] **Step 4: Add use case interface**

Create `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerUseCase.kt`:

```kotlin
package com.homeassistant.domain.topicanswer

interface TopicAnswerUseCase {
    fun answer(request: TopicAnswerRequest): TopicAnswerResult
}
```

- [ ] **Step 5: Add answer service**

Create `domain/src/main/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerService.kt`:

```kotlin
package com.homeassistant.domain.topicanswer

import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore

class TopicAnswerService(
    private val topicStore: TopicAnalysisStore,
) : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult {
        val question = request.question.trim()
        val limit = request.limit.coerceIn(1, 10)
        val topics = topicStore.searchApprovedTopics(question, limit)
        val matches = topics.map { it.toMatch() }

        return TopicAnswerResult(
            question = question,
            answer = buildAnswer(matches),
            matches = matches,
        )
    }

    private fun Topic.toMatch(): TopicAnswerMatch =
        TopicAnswerMatch(
            topicId = id,
            title = title,
            summary = summary,
            claims = claims.map { it.text }.distinct(),
            evidenceRefs = evidenceRefs.distinct(),
        )

    private fun buildAnswer(matches: List<TopicAnswerMatch>): String {
        if (matches.isEmpty()) return "승인된 기억에서 관련 내용을 찾지 못했습니다."

        val claims = matches
            .flatMap { it.claims }
            .distinct()
            .take(3)
        return "저장된 기억 기준으로는 " + claims.joinToString(" ")
    }
}
```

- [ ] **Step 6: Run service test**

Run:

```powershell
.\gradlew.bat :domain:test --tests "com.homeassistant.domain.topicanswer.TopicAnswerServiceTest"
```

Expected: pass.

- [ ] **Step 7: Commit**

Run:

```powershell
git add domain/src/main/kotlin/com/homeassistant/domain/topicanswer domain/src/test/kotlin/com/homeassistant/domain/topicanswer/TopicAnswerServiceTest.kt
git commit -m "Answer from approved topic claims"
```

---

## Task 3: Ktor Topic Answer Route

**Files:**
- Modify: `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/routes/TopicAnswerRoutesTest.kt`

- [ ] **Step 1: Write the failing route test**

Create `app/src/test/kotlin/com/homeassistant/app/routes/TopicAnswerRoutesTest.kt`:

```kotlin
package com.homeassistant.app.routes

import com.homeassistant.domain.topicanswer.TopicAnswerMatch
import com.homeassistant.domain.topicanswer.TopicAnswerRequest
import com.homeassistant.domain.topicanswer.TopicAnswerResult
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class TopicAnswerRoutesTest {
    @Test
    fun `topic answer route returns answer from approved topics`() = testApplication {
        application {
            configureRoutes(
                kakaoImportAnalyze = UnusedTopicAnalysis,
                topicAnswer = FakeTopicAnswer,
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.post("/api/topics/answer") {
            contentType(ContentType.Application.Json)
            setBody(TopicAnswerRequest(question = "리모컨 어디 있어?", limit = 5))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<TopicAnswerResult>()
        assertEquals("저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.", body.answer)
        assertEquals(1, body.matches.size)
    }

    @Test
    fun `topic answer route rejects blank question`() = testApplication {
        application {
            configureRoutes(
                kakaoImportAnalyze = UnusedTopicAnalysis,
                topicAnswer = FakeTopicAnswer,
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.post("/api/topics/answer") {
            contentType(ContentType.Application.Json)
            setBody(TopicAnswerRequest(question = "   ", limit = 5))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

private object FakeTopicAnswer : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult =
        TopicAnswerResult(
            question = request.question.trim(),
            answer = "저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.",
            matches = listOf(
                TopicAnswerMatch(
                    topicId = 1,
                    title = "집 물건 위치",
                    summary = "리모컨 위치",
                    claims = listOf("리모컨은 벽장 제일 위칸에 있다."),
                    evidenceRefs = listOf(10),
                ),
            ),
        )
}

private object UnusedTopicAnalysis : TopicAnalysisUseCase() {
    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("not used")

    override suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult =
        error("not used")
}
```

- [ ] **Step 2: Run the failing route test**

Run:

```powershell
.\gradlew.bat :app:test --tests "com.homeassistant.app.routes.TopicAnswerRoutesTest"
```

Expected: compile failure because `configureRoutes` does not accept `topicAnswer`.

- [ ] **Step 3: Add route constant**

Modify `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`:

```kotlin
    const val ROUTE_TOPIC_ANSWER = "/api/topics/answer"
```

- [ ] **Step 4: Wire service in Application**

Modify `app/src/main/kotlin/com/homeassistant/app/Application.kt`.

Add import:

```kotlin
import com.homeassistant.domain.topicanswer.TopicAnswerService
```

After `kakaoTopicAnalysis`, add:

```kotlin
    val topicAnswer = TopicAnswerService(repositories.topicAnalysis)
```

Change the final route wiring:

```kotlin
    configureRoutes(kakaoTopicAnalysis, topicAnalysisModelEval, topicAnswer)
```

- [ ] **Step 5: Add route parameter and route**

Modify `app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt`.

Add imports:

```kotlin
import com.homeassistant.domain.topicanswer.TopicAnswerRequest
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
```

Change signature:

```kotlin
fun Application.configureRoutes(
    kakaoImportAnalyze: TopicAnalysisUseCase,
    modelEval: TopicAnalysisModelEvalUseCase? = null,
    topicAnswer: TopicAnswerUseCase? = null,
)
```

Add route:

```kotlin
        post(AppConfig.ROUTE_TOPIC_ANSWER) {
            if (topicAnswer == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "topic answer is not configured"))
                return@post
            }

            val req = call.receive<TopicAnswerRequest>()
            if (req.question.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "question is required"))
                return@post
            }

            call.respond(HttpStatusCode.OK, topicAnswer.answer(req))
        }
```

- [ ] **Step 6: Run route test**

Run:

```powershell
.\gradlew.bat :app:test --tests "com.homeassistant.app.routes.TopicAnswerRoutesTest"
```

Expected: pass.

- [ ] **Step 7: Commit**

Run:

```powershell
git add core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt app/src/main/kotlin/com/homeassistant/app/Application.kt app/src/main/kotlin/com/homeassistant/app/routes/AppRoutes.kt app/src/test/kotlin/com/homeassistant/app/routes/TopicAnswerRoutesTest.kt
git commit -m "Expose approved topic answer endpoint"
```

---

## Task 4: End-To-End Verification Against Existing DB

**Files:**
- No source files.
- Uses existing `db/homeAssistant.sqlite`.
- Must not delete or modify `topic_analysis_previews`.

- [ ] **Step 1: Run all tests**

Run:

```powershell
.\gradlew.bat test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Restart server**

Run:

```powershell
$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($conn) {
  $conn | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }
  Start-Sleep -Seconds 3
}
$out = Join-Path (Get-Location) 'app-run.out.log'
$err = Join-Path (Get-Location) 'app-run.err.log'
Start-Process -FilePath (Join-Path (Get-Location) 'gradlew.bat') -ArgumentList ':app:run' -WorkingDirectory (Get-Location) -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
Start-Sleep -Seconds 8
Invoke-RestMethod -Uri 'http://localhost:8080/health' -TimeoutSec 5
```

Expected:

```json
{"status":"ok"}
```

- [ ] **Step 3: Verify approved topics exist and preview remains**

Run:

```powershell
@'
import sqlite3
conn = sqlite3.connect('file:db/homeAssistant.sqlite?mode=ro', uri=True)
try:
    cur = conn.cursor()
    print('topic_statuses:', cur.execute("select status, count(*) from topic_candidates where source_name='KakaoTalkChats-1.txt' group by status order by status").fetchall())
    print('preview_exists:', cur.execute("select count(*) from topic_analysis_previews where preview_id='91aade62-4cc5-4a63-99da-be396a4e8fdc'").fetchone()[0])
finally:
    conn.close()
'@ | python -
```

Expected:

```text
topic_statuses: [('APPROVED', 19)]
preview_exists: 1
```

- [ ] **Step 4: Query the new endpoint**

Run:

```powershell
$body = @{ question = '주차장 차단기 리모컨 어디 있어?'; limit = 5 } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri 'http://localhost:8080/api/topics/answer' -Method Post -ContentType 'application/json' -Body $body | ConvertTo-Json -Depth 8
```

Expected:

- HTTP 200.
- `answer` mentions the stored claim about the parking barrier remote.
- `matches` is non-empty.
- `matches[0].evidenceRefs` is non-empty.

- [ ] **Step 5: Verify unknown question behavior**

Run:

```powershell
$body = @{ question = '이 데이터에 전혀 없는 질문 zzzzz'; limit = 5 } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri 'http://localhost:8080/api/topics/answer' -Method Post -ContentType 'application/json' -Body $body | ConvertTo-Json -Depth 8
```

Expected:

```json
{
  "question": "이 데이터에 전혀 없는 질문 zzzzz",
  "answer": "승인된 기억에서 관련 내용을 찾지 못했습니다.",
  "matches": []
}
```

- [ ] **Step 6: Commit verification notes if any docs changed**

No commit is needed if no files changed in Task 4.

---

## Self-Review

- Spec coverage: The plan adds a purpose-built endpoint over approved stored topic candidates, keeps preview rows untouched, and avoids `/api/chat`.
- Placeholder scan: No `TBD`, `TODO`, or open implementation steps remain.
- Type consistency: `TopicAnswerRequest`, `TopicAnswerResult`, `TopicAnswerMatch`, `TopicAnswerUseCase`, and `TopicAnalysisStore.searchApprovedTopics` are introduced before use.
- Risk: The first retrieval is lexical. Korean queries without shared exact terms can miss relevant topics. This is acceptable for the first version because it is deterministic, testable, and does not hallucinate. Embedding search can be added later behind the same `TopicAnswerUseCase`.

