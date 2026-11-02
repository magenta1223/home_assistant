package com.homeassistant.adapter.outbound.componentextraction

import com.homeassistant.application.port.output.component.PendingEpisode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.llm.completion.CompletionImage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelComponentKnowledgeExtractorTest {
    private val episode = PendingEpisode(
        id = 1,
        title = "임신기간 단축근로 시간 정하기",
        content = "홍승민은 다음 주부터 임신기간 단축근로를 신청하기로 했다.",
        access = EpisodeAccess.PUBLIC,
        messages = listOf("홍승민: 임신기간 단축 근로 다음주부터 신청하기로 햇는데", "동훈: 그러면 5퇴"),
    )

    @Test
    fun `without known knowledge the episode itself is read once`() {
        val client = RecordingClient(mutableListOf("""{"statements":["홍승민은 임신기간 단축근로를 한다"]}"""))

        val statements = ModelComponentKnowledgeExtractor(client).extract(episode, emptyList())

        assertEquals(listOf("홍승민은 임신기간 단축근로를 한다"), statements)
        assertContains(client.calls.single().userMessage, episode.content)
        assertContains(client.calls.single().system, "6개월 뒤에도 사실")
    }

    @Test
    fun `with known knowledge the prediction from the title is compared with the messages`() {
        val client = RecordingClient(mutableListOf(
            """{"prediction":"홍승민이 단축근로를 시작한다"}""",
            """{"statements":["홍승민 회사의 정기 리더 회의는 월요일 3시다"]}""",
        ))

        val statements = ModelComponentKnowledgeExtractor(client).extract(episode, listOf("홍승민은 임신 중이다"))

        assertEquals(listOf("홍승민 회사의 정기 리더 회의는 월요일 3시다"), statements)
        val (prediction, comparison) = client.calls
        assertContains(prediction.userMessage, episode.title)
        assertContains(prediction.userMessage, "홍승민은 임신 중이다")
        assertFalse(prediction.userMessage.contains("5퇴"))
        assertContains(comparison.userMessage, "동훈: 그러면 5퇴")
        assertContains(comparison.userMessage, "홍승민이 단축근로를 시작한다")
    }

    private data class Call(val system: String, val userMessage: String)

    private class RecordingClient(private val responses: MutableList<String>) : CompletionClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(system: String, userMessage: String, outputSchema: String, images: List<CompletionImage>): String {
            calls += Call(system, userMessage)
            return responses.removeFirst()
        }
    }
}
