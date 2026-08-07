package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.handle.*
import com.homeassistant.application.slackconversation.handle.SlackCodexSession
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore
import com.homeassistant.application.slackconversation.handle.SlackMessageKey
import com.homeassistant.application.slackconversation.handle.SlackMessageReceipt
import com.homeassistant.application.slackconversation.handle.SlackMessageReceiptStatus
import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.memory.search.MemorySearchMatch
import com.homeassistant.application.memory.search.SearchMemoriesRequest
import com.homeassistant.application.memory.search.SearchMemoriesResult
import com.homeassistant.application.memory.search.SearchMemoriesUseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlackConversationServiceTest {
    private val identities = SlackIdentityDirectoryFactory.fromJson(
        "T1",
        """[{"teamId":"T1","slackUserId":"U1","userId":"dad"}]""",
    )
    private val message = SlackConversationMessage("T1", "U1", "D1", "100.1", "리모컨 어디 있어?")
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    @Test
    fun `uses server mapped scope and completes only with the Slack response timestamp`() {
        val store = MemorySessionStore()
        val search = CapturingMemorySearch()
        val service = service(store, search, SuccessfulSlack())

        service.handle(message)

        assertEquals("dad", search.request?.userId)
        assertEquals(SlackMessageReceiptStatus.COMPLETED, store.receipt(key())?.status)
        assertEquals("200.2", store.receipt(key())?.responseTs)
    }

    @Test
    fun `keeps answer ready when Slack rejects delivery`() {
        val store = MemorySessionStore()
        val service = service(store, CapturingMemorySearch(), RejectingSlack)

        service.handle(message)

        assertEquals(SlackMessageReceiptStatus.ANSWER_READY, store.receipt(key())?.status)
        assertEquals("정답", store.receipt(key())?.answerText)
        assertNull(store.receipt(key())?.responseTs)
    }

    @Test
    fun `ignores an unmapped Slack actor before claiming a message`() {
        val store = MemorySessionStore()
        service(store, CapturingMemorySearch(), SuccessfulSlack()).handle(
            message.copy(slackUserId = "ATTACKER"),
        )

        assertNull(store.receipt(key()))
    }

    @Test
    fun `does not invoke Codex when no authorized topic matches`() {
        val store = MemorySessionStore()
        val codex = CountingCodex()
        val noMatches = SearchMemoriesUseCase { request ->
            SearchMemoriesResult(request.query, emptyList())
        }
        val service = SlackConversationService(
            HandleSlackConversation(
                identities,
                store,
                HouseholdContextProvider(noMatches),
                codex,
                SlackConversationAnswerPublisher(SuccessfulSlack()),
                clock,
            ),
        )

        service.handle(message)

        assertEquals(0, codex.turns)
        assertEquals(SlackMessageReceiptStatus.COMPLETED, store.receipt(key())?.status)
        assertEquals(HandleSlackConversation.NO_MATCH_ANSWER, store.receipt(key())?.answerText)
    }

    private fun service(
        store: MemorySessionStore,
        search: CapturingMemorySearch,
        slack: SlackClient,
        codex: ConversationTurnClient = SuccessfulCodex,
    ) = SlackConversationService(
        HandleSlackConversation(
            identities = identities,
            sessions = store,
            contextProvider = HouseholdContextProvider(search),
            conversationClient = codex,
            answerPublisher = SlackConversationAnswerPublisher(slack),
            clock = clock,
        ),
    )

    private fun key() = SlackMessageKey("D1", "100.1")
}

private class CapturingMemorySearch : SearchMemoriesUseCase {
    var request: SearchMemoriesRequest? = null

    override fun search(request: SearchMemoriesRequest): SearchMemoriesResult {
        this.request = request
        return SearchMemoriesResult(
            request.query,
            listOf(
                MemorySearchMatch(
                    memoryId = 1,
                    topicId = 1,
                    topicTitle = "리모컨",
                    topicSummary = "벽장에 있음",
                    content = "위칸",
                    evidenceRefs = listOf(1),
                ),
            ),
        )
    }
}

private object SuccessfulCodex : ConversationTurnClient {
    override fun start(
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): ConversationTurnResult {
        onThreadStarted("019fa391-a538-7531-b719-c20d3d330bdc")
        return ConversationTurnResult.Success("정답")
    }

    override fun resume(threadId: String, prompt: String) =
        ConversationTurnResult.Success("정답")
}

private class CountingCodex : ConversationTurnClient {
    var turns = 0
    override fun start(prompt: String, onThreadStarted: (String) -> Unit): ConversationTurnResult {
        turns++
        return ConversationTurnResult.Success("unexpected")
    }
    override fun resume(threadId: String, prompt: String): ConversationTurnResult {
        turns++
        return ConversationTurnResult.Success("unexpected")
    }
}

private open class SuccessfulSlack : SlackClient {
    override fun fileDownloadUrl(fileId: String) = null
    override fun downloadText(url: String, maxBytes: Long) = error("not used")
    override fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ) = SlackMessageDelivery("200.2")
    override fun postEphemeral(channelId: String, userId: String, text: String) = Unit
}

private object RejectingSlack : SuccessfulSlack() {
    override fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ): SlackMessageDelivery =
        throw SlackMessageDeliveryException("channel_not_found")
}

private class MemorySessionStore : SlackCodexSessionStore {
    private val receipts = mutableMapOf<SlackMessageKey, SlackMessageReceipt>()
    private var active: SlackCodexSession? = null

    override fun claimMessage(key: SlackMessageKey, now: Long): SlackMessageReceipt? {
        if (receipts.containsKey(key)) return null
        return SlackMessageReceipt(
            key,
            SlackMessageReceiptStatus.PROCESSING,
            createdAt = now,
            updatedAt = now,
        ).also { receipts[key] = it }
    }

    override fun receipt(key: SlackMessageKey) = receipts[key]

    override fun attachSession(key: SlackMessageKey, sessionId: Int, now: Long) {
        update(key, now) { it.copy(sessionId = sessionId) }
    }

    override fun markAnswerReady(key: SlackMessageKey, answer: String, now: Long) {
        update(key, now) {
            it.copy(status = SlackMessageReceiptStatus.ANSWER_READY, answerText = answer)
        }
    }

    override fun markCompleted(key: SlackMessageKey, responseTs: String, now: Long) {
        require(responseTs.isNotBlank())
        update(key, now) {
            it.copy(status = SlackMessageReceiptStatus.COMPLETED, responseTs = responseTs)
        }
    }

    override fun markFailed(key: SlackMessageKey, now: Long) {
        update(key, now) { it.copy(status = SlackMessageReceiptStatus.FAILED) }
    }

    override fun createAndActivate(
        principal: SlackPrincipal,
        codexThreadId: String,
        now: Long,
    ) = SlackCodexSession(1, principal, codexThreadId, now, now).also { active = it }

    override fun active(
        principal: SlackPrincipal,
        now: Long,
        idleTimeoutMillis: Long,
    ) = active?.takeIf {
        it.principal == principal && now - it.lastActiveAt < idleTimeoutMillis
    }

    override fun clearActive(principal: SlackPrincipal) {
        if (active?.principal == principal) active = null
    }

    override fun touch(principal: SlackPrincipal, sessionId: Int, now: Long) {
        val current = checkNotNull(active)
        check(current.principal == principal && current.id == sessionId)
        active = current.copy(lastActiveAt = now)
    }

    override fun failStaleProcessing(before: Long, now: Long) = 0

    private fun update(
        key: SlackMessageKey,
        now: Long,
        block: (SlackMessageReceipt) -> SlackMessageReceipt,
    ) {
        receipts[key] = block(checkNotNull(receipts[key])).copy(updatedAt = now)
    }
}
