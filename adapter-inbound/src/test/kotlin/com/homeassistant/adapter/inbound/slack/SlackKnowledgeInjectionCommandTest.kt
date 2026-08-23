package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionPreparation
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRequest
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionWorkflow
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryVisibility
import com.slack.api.model.view.ViewState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackKnowledgeInjectionCommandTest {
    @Test
    fun `repository manifest declares the implemented command`() {
        val manifestPath = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("slack-app-manifest.json") }
            .first(Files::exists)
        val manifest = JsonSerializer.json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val commands = manifest["features"]!!.jsonObject["slash_commands"]!!.jsonArray
            .map { it.jsonObject["command"]!!.jsonPrimitive.content }

        assertTrue(SlackKnowledgeInjectionCommand.COMMAND_NAME in commands)
    }

    @Test
    fun `registered command opens modal and submits restricted knowledge to workflow`() {
        val requester = RegisteredUser(UserId("member-1"), "첫째")
        val viewer = RegisteredUser(UserId("member-2"), "둘째")
        val workflow = RecordingKnowledgeInjectionWorkflow(
            KnowledgeInjectionPreparation.Ready(requester, listOf(requester, viewer)),
        )
        val slack = RecordingSlackClient()
        val command = SlackKnowledgeInjectionCommand("team-1", workflow, slack, Runnable::run)
        val invocation = SlackKnowledgeCommandInvocation(
            teamId = "team-1",
            slackUserId = "slack-1",
            channelId = "channel-1",
            triggerId = "trigger-1",
            responseUrl = "https://hooks.slack.test/response",
        )

        assertNull(command.open(invocation))
        val modal = slack.modals.single().second
        assertEquals(SlackKnowledgeInjectionCommand.VIEW_CALLBACK_ID, modal["callback_id"])

        val errors = command.submit(
            teamId = "team-1",
            slackUserId = "slack-1",
            privateMetadata = modal["private_metadata"] as String,
            values = validValues(viewer.userId.value),
        )

        assertTrue(errors.isEmpty())
        val request = workflow.requests.single()
        assertEquals("가족 규칙", request.source.source.name)
        assertEquals("text", request.source.source.type)
        assertEquals(setOf("member-2"), request.access.allowedUserIds)
        assertEquals(listOf("지식을 분석하고 있습니다…", "완료: 소스 레코드 1개, 메모리 1개를 저장했습니다."), slack.responses.map { it.second })
    }

    @Test
    fun `unregistered command is rejected before opening modal`() {
        val slack = RecordingSlackClient()
        val command = SlackKnowledgeInjectionCommand(
            "team-1",
            RecordingKnowledgeInjectionWorkflow(KnowledgeInjectionPreparation.RegistrationRequired),
            slack,
            Runnable::run,
        )

        val message = command.open(
            SlackKnowledgeCommandInvocation(
                "team-1",
                "slack-1",
                "channel-1",
                "trigger-1",
                "https://hooks.slack.test/response",
            ),
        )

        assertEquals("먼저 앱과의 DM에서 사용자 등록을 완료해주세요.", message)
        assertTrue(slack.modals.isEmpty())
    }

    @Test
    fun `restricted submission requires at least one viewer`() {
        val requester = RegisteredUser(UserId("member-1"), "첫째")
        val workflow = RecordingKnowledgeInjectionWorkflow(
            KnowledgeInjectionPreparation.Ready(requester, listOf(requester)),
        )
        val slack = RecordingSlackClient()
        val command = SlackKnowledgeInjectionCommand("team-1", workflow, slack, Runnable::run)
        command.open(
            SlackKnowledgeCommandInvocation(
                "team-1",
                "slack-1",
                "channel-1",
                "trigger-1",
                "https://hooks.slack.test/response",
            ),
        )

        val errors = command.submit(
            "team-1",
            "slack-1",
            slack.modals.single().second["private_metadata"] as String,
            validValues(viewerId = null),
        )

        assertEquals("열람할 사용자를 한 명 이상 선택해주세요.", errors[SlackKnowledgeInjectionCommand.VIEWERS_BLOCK_ID])
        assertTrue(workflow.requests.isEmpty())
    }

    private fun validValues(viewerId: String?): Map<String, Map<String, ViewState.Value>> = mapOf(
        SlackKnowledgeInjectionCommand.SOURCE_NAME_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.SOURCE_NAME_ACTION_ID to value("가족 규칙"),
        ),
        SlackKnowledgeInjectionCommand.SOURCE_TYPE_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.SOURCE_TYPE_ACTION_ID to selected("TEXT"),
        ),
        SlackKnowledgeInjectionCommand.AUDIENCE_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.AUDIENCE_ACTION_ID to selected("RESTRICTED"),
        ),
        SlackKnowledgeInjectionCommand.VIEWERS_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.VIEWERS_ACTION_ID to selectedMany(viewerId),
        ),
        SlackKnowledgeInjectionCommand.DATA_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.DATA_ACTION_ID to value("현관 비밀번호는 매달 바뀐다"),
        ),
    )

    private fun value(value: String) = ViewState.Value().apply { this.value = value }

    private fun selected(value: String) = ViewState.Value().apply {
        selectedOption = ViewState.SelectedOption().apply { this.value = value }
    }

    private fun selectedMany(value: String?) = ViewState.Value().apply {
        selectedOptions = value?.let { listOf(ViewState.SelectedOption().apply { this.value = it }) }.orEmpty()
    }

    private class RecordingKnowledgeInjectionWorkflow(
        private val preparation: KnowledgeInjectionPreparation,
    ) : KnowledgeInjectionWorkflow {
        val requests = mutableListOf<KnowledgeInjectionRequest>()

        override fun prepare(identity: com.homeassistant.application.port.input.identity.ConversationIdentity):
            KnowledgeInjectionPreparation = preparation

        override suspend fun execute(request: KnowledgeInjectionRequest): MemoryAnalysisResult {
            requests += request
            return MemoryAnalysisResult(
                sourceType = request.source.source.type,
                sourceName = request.source.source.name,
                importedRecordCount = request.source.records.size,
                retriedRecordCount = 0,
                alreadyAnalyzedRecordCount = 0,
                visibility = MemoryVisibility.RESTRICTED,
                allowedUserIds = request.access.allowedUserIds,
                memoryCount = 1,
                memories = emptyList(),
            )
        }
    }

    private class RecordingSlackClient : SlackClient {
        val modals = mutableListOf<Pair<String, Map<String, Any>>>()
        val responses = mutableListOf<Pair<String, String>>()

        override fun postMessage(
            channelId: String,
            text: String,
            blocks: List<Map<String, Any>>,
            threadTs: String?,
        ): SlackMessageDelivery = SlackMessageDelivery("message-1")

        override fun openModal(triggerId: String, view: Map<String, Any>) {
            modals += triggerId to view
        }

        override fun respond(responseUrl: String, text: String) {
            responses += responseUrl to text
        }
    }
}
