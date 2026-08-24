package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionPreparation
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRequest
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionWorkflow
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryVisibility
import com.slack.api.model.File as SlackFile
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
        val scopes = manifest["oauth_config"]!!.jsonObject["scopes"]!!.jsonObject["bot"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("files:read" in scopes)
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
        val blocks = modal["blocks"] as List<Map<String, Any>>
        val fileBlock = blocks.single { it["block_id"] == SlackKnowledgeInjectionCommand.FILE_BLOCK_ID }
        val fileElement = fileBlock["element"] as Map<String, Any>
        assertEquals("file_input", fileElement["type"])
        assertEquals(listOf("txt"), fileElement["filetypes"])
        assertEquals(1, fileElement["max_files"])

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

    @Test
    fun `kakao export can be submitted as a text file`() {
        val requester = RegisteredUser(UserId("member-1"), "첫째")
        val workflow = RecordingKnowledgeInjectionWorkflow(
            KnowledgeInjectionPreparation.Ready(requester, listOf(requester)),
        )
        val slack = RecordingSlackClient(
            mapOf("file-1" to SlackTextFile("KakaoTalk_20260824.txt", KAKAO_EXPORT)),
        )
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
            validValues("member-1", sourceType = "KAKAO", text = "", fileId = "file-1"),
        )

        assertTrue(errors.isEmpty())
        assertEquals(listOf("file-1"), slack.readFileIds)
        val request = workflow.requests.single()
        assertEquals("kakao", request.source.source.type)
        assertEquals(1, request.source.records.size)
    }

    @Test
    fun `kakao export can still be submitted as pasted text`() {
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
            validValues("member-1", sourceType = "KAKAO", text = KAKAO_EXPORT),
        )

        assertTrue(errors.isEmpty())
        assertTrue(slack.readFileIds.isEmpty())
        assertEquals("kakao", workflow.requests.single().source.source.type)
    }

    @Test
    fun `file upload is rejected for direct text source`() {
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
            validValues("member-1", fileId = "file-1"),
        )

        assertEquals(
            "파일은 카카오톡 내보내기 형식에서만 사용할 수 있습니다.",
            errors[SlackKnowledgeInjectionCommand.FILE_BLOCK_ID],
        )
        assertTrue(workflow.requests.isEmpty())
    }

    @Test
    fun `kakao export requires either pasted text or one file`() {
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
        val metadata = slack.modals.single().second["private_metadata"] as String

        val missing = command.submit(
            "team-1",
            "slack-1",
            metadata,
            validValues("member-1", sourceType = "KAKAO", text = ""),
        )
        val ambiguous = command.submit(
            "team-1",
            "slack-1",
            metadata,
            validValues("member-1", sourceType = "KAKAO", text = KAKAO_EXPORT, fileId = "file-1"),
        )

        assertEquals(
            "내용을 붙여 넣거나 카카오톡 내보내기 파일을 첨부해주세요.",
            missing[SlackKnowledgeInjectionCommand.TEXT_BLOCK_ID],
        )
        assertEquals("붙여넣기와 파일 중 하나만 선택해주세요.", ambiguous[SlackKnowledgeInjectionCommand.FILE_BLOCK_ID])
        assertTrue(workflow.requests.isEmpty())
    }

    private fun validValues(
        viewerId: String?,
        sourceType: String = "TEXT",
        text: String = "현관 비밀번호는 매달 바뀐다",
        fileId: String? = null,
    ): Map<String, Map<String, ViewState.Value>> = mapOf(
        SlackKnowledgeInjectionCommand.SOURCE_NAME_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.SOURCE_NAME_ACTION_ID to value("가족 규칙"),
        ),
        SlackKnowledgeInjectionCommand.SOURCE_TYPE_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.SOURCE_TYPE_ACTION_ID to selected(sourceType),
        ),
        SlackKnowledgeInjectionCommand.AUDIENCE_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.AUDIENCE_ACTION_ID to selected("RESTRICTED"),
        ),
        SlackKnowledgeInjectionCommand.VIEWERS_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.VIEWERS_ACTION_ID to selectedMany(viewerId),
        ),
        SlackKnowledgeInjectionCommand.TEXT_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.TEXT_ACTION_ID to value(text),
        ),
        SlackKnowledgeInjectionCommand.FILE_BLOCK_ID to mapOf(
            SlackKnowledgeInjectionCommand.FILE_ACTION_ID to files(fileId),
        ),
    )

    private fun value(value: String) = ViewState.Value().apply { this.value = value }

    private fun selected(value: String) = ViewState.Value().apply {
        selectedOption = ViewState.SelectedOption().apply { this.value = value }
    }

    private fun selectedMany(value: String?) = ViewState.Value().apply {
        selectedOptions = value?.let { listOf(ViewState.SelectedOption().apply { this.value = it }) }.orEmpty()
    }

    private fun files(fileId: String?) = ViewState.Value().apply {
        files = fileId?.let { listOf(SlackFile().apply { id = it }) }.orEmpty()
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

    private class RecordingSlackClient(
        private val files: Map<String, SlackTextFile> = emptyMap(),
    ) : SlackClient {
        val modals = mutableListOf<Pair<String, Map<String, Any>>>()
        val responses = mutableListOf<Pair<String, String>>()
        val readFileIds = mutableListOf<String>()

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

        override fun readTextFile(fileId: String, maxBytes: Int): SlackTextFile {
            readFileIds += fileId
            return files.getValue(fileId)
        }
    }
    private companion object {
        const val KAKAO_EXPORT = "2026년 8월 24일 오전 10:00, 엄마 : 현관 비밀번호는 매달 바뀐다"
    }
}
