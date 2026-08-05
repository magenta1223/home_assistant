package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.slackconversation.SlackCodexSession
import com.homeassistant.domain.slackconversation.SlackCodexSessionStore
import com.homeassistant.domain.slackconversation.SlackMessageKey
import com.homeassistant.domain.slackconversation.SlackMessageReceipt
import com.homeassistant.domain.slackconversation.SlackMessageReceiptStatus
import com.homeassistant.domain.slackconversation.SlackPrincipal
import com.homeassistant.domain.topicanswer.TopicAnswerRequest
import com.homeassistant.domain.topicanswer.TopicAnswerResult
import com.homeassistant.domain.topicanswer.TopicAnswerMatch
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlackConversationServiceTest {
    private val identities = SlackIdentityDirectoryFactory.fromJson(
        "T1",
        """[{"teamId":"T1","slackUserId":"U1","userId":"dad","familyId":"family-1"}]""",
    )
    private val message = SlackConversationMessage("T1", "U1", "D1", "100.1", "리모컨 어디 있어?")
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    @Test
    fun `uses server mapped scope and completes only with the Slack response timestamp`() {
        val store = MemorySessionStore()
        val answer = CapturingTopicAnswer()
        val service = service(store, answer, SuccessfulSlack())

        service.handle(message)

        assertEquals("dad", answer.request?.userId)
        assertEquals("family-1", answer.request?.familyId)
        assertEquals(SlackMessageReceiptStatus.COMPLETED, store.receipt(key())?.status)
        assertEquals("200.2", store.receipt(key())?.responseTs)
    }

    @Test
    fun `keeps answer ready when Slack rejects delivery`() {
        val store = MemorySessionStore()
        val service = service(store, CapturingTopicAnswer(), RejectingSlack)

        service.handle(message)

        assertEquals(SlackMessageReceiptStatus.ANSWER_READY, store.receipt(key())?.status)
        assertEquals("정답", store.receipt(key())?.answerText)
        assertNull(store.receipt(key())?.responseTs)
    }

    @Test
    fun `ignores an unmapped Slack actor before claiming a message`() {
        val store = MemorySessionStore()
        service(store, CapturingTopicAnswer(), SuccessfulSlack()).handle(
            message.copy(slackUserId = "ATTACKER"),
        )

        assertNull(store.receipt(key()))
    }

    @Test
    fun `does not invoke Codex when no authorized topic matches`() {
        val store = MemorySessionStore()
        val codex = CountingCodex()
        val noMatches = object : TopicAnswerUseCase {
            override fun answer(request: TopicAnswerRequest) =
                TopicAnswerResult(request.question, "", emptyList())
        }
        val service = SlackConversationService(
            identities,
            store,
            HouseholdContextProvider(noMatches),
            codex,
            SuccessfulSlack(),
            clock,
        )

        service.handle(message)

        assertEquals(0, codex.turns)
        assertEquals(SlackMessageReceiptStatus.COMPLETED, store.receipt(key())?.status)
        assertEquals(SlackConversationService.NO_MATCH_ANSWER, store.receipt(key())?.answerText)
    }

    private fun service(
        store: MemorySessionStore,
        answer: CapturingTopicAnswer,
        slack: SlackClient,
        codex: CodexConversationClient = SuccessfulCodex,
    ) = SlackConversationService(
        identities = identities,
        sessions = store,
        contextProvider = HouseholdContextProvider(answer),
        codex = codex,
        slack = slack,
        clock = clock,
    )

    private fun key() = SlackMessageKey("D1", "100.1")
}

private class CapturingTopicAnswer : TopicAnswerUseCase {
    var request: TopicAnswerRequest? = null

    override fun answer(request: TopicAnswerRequest): TopicAnswerResult {
        this.request = request
        return TopicAnswerResult(
            request.question,
            "",
            listOf(TopicAnswerMatch(1, "리모컨", "벽장에 있음", listOf("위칸"), listOf(1))),
        )
    }
}

private object SuccessfulCodex : CodexConversationClient {
    override fun validateVersion() = true

    override fun start(
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): CodexTurnResult {
        onThreadStarted("019fa391-a538-7531-b719-c20d3d330bdc")
        return CodexTurnResult.Success("정답")
    }

    override fun resume(threadId: String, prompt: String) =
        CodexTurnResult.Success("정답")
}

private class CountingCodex : CodexConversationClient {
    var turns = 0
    override fun validateVersion() = true
    override fun start(prompt: String, onThreadStarted: (String) -> Unit): CodexTurnResult {
        turns++
        return CodexTurnResult.Success("unexpected")
    }
    override fun resume(threadId: String, prompt: String): CodexTurnResult {
        turns++
        return CodexTurnResult.Success("unexpected")
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
    override fun openModal(triggerId: String, view: Map<String, Any>) = Unit
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
