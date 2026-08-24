package com.homeassistant.adapter.inbound.slack

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.adapter.inbound.text.PlainTextSourceParser
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.memory.analysis.ConflictingSourceAudienceException
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.InvalidMemoryAudienceException
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionPreparation
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRegistrationRequiredException
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRequest
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionUnavailableException
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionWorkflow
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDocumentDraft
import com.slack.api.bolt.App
import com.slack.api.model.File
import com.slack.api.model.view.ViewState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

internal class SlackKnowledgeInjectionCommand(
    private val configuredTeamId: String,
    private val workflow: KnowledgeInjectionWorkflow,
    private val slack: SlackClient,
    private val executor: Executor,
) : SlackSlashCommand {
    override val commandName: String = COMMAND_NAME
    override val interactionCallbackIds: Set<String> = setOf(VIEW_CALLBACK_ID)
    private val log = LoggerFactory.getLogger(javaClass)

    override fun register(app: App) {
        app.command(commandName) { request, context ->
            val payload = request.payload
            val response = open(
                SlackKnowledgeCommandInvocation(
                    teamId = payload.teamId.orEmpty(),
                    slackUserId = payload.userId.orEmpty(),
                    channelId = payload.channelId.orEmpty(),
                    triggerId = payload.triggerId.orEmpty(),
                    responseUrl = payload.responseUrl.orEmpty(),
                ),
            )
            if (response == null) context.ack() else context.ack(response)
        }
        app.viewSubmission(VIEW_CALLBACK_ID) { request, context ->
            val payload = request.payload
            val errors = submit(
                teamId = payload.team?.id.orEmpty(),
                slackUserId = payload.user?.id.orEmpty(),
                privateMetadata = payload.view?.privateMetadata.orEmpty(),
                values = payload.view?.state?.values.orEmpty(),
            )
            if (errors.isEmpty()) context.ack() else context.ackWithErrors(errors)
        }
    }

    internal fun open(invocation: SlackKnowledgeCommandInvocation): String? {
        if (invocation.teamId != configuredTeamId) return "이 워크스페이스에서는 사용할 수 없습니다."
        if (invocation.slackUserId.isBlank() || invocation.channelId.isBlank() ||
            invocation.triggerId.isBlank() || invocation.responseUrl.isBlank()
        ) {
            return "지식 주입 요청 정보를 확인할 수 없습니다."
        }
        val identity = ConversationIdentity(invocation.teamId, invocation.slackUserId)
        return when (val preparation = workflow.prepare(identity)) {
            is KnowledgeInjectionPreparation.Ready -> runCatching {
                slack.openModal(
                    invocation.triggerId,
                    knowledgeModal(invocation, preparation.requester, preparation.availableViewers),
                )
                null
            }.getOrElse {
                log.warn("Knowledge injection modal delivery failed category={}", it.javaClass.simpleName)
                "지식 입력창을 열지 못했습니다. 다시 시도해주세요."
            }
            KnowledgeInjectionPreparation.RegistrationRequired ->
                "먼저 앱과의 DM에서 사용자 등록을 완료해주세요."
            KnowledgeInjectionPreparation.Failed ->
                "사용자 정보를 확인하지 못했습니다. 다시 시도해주세요."
        }
    }

    internal fun submit(
        teamId: String,
        slackUserId: String,
        privateMetadata: String,
        values: Map<String, Map<String, ViewState.Value>>,
    ): Map<String, String> {
        val metadata = runCatching {
            JsonSerializer.json.decodeFromString<KnowledgeModalMetadata>(privateMetadata)
        }.getOrNull() ?: return mapOf(TEXT_BLOCK_ID to "요청 정보를 확인할 수 없습니다. 다시 시작해주세요.")
        if (teamId != configuredTeamId || metadata.teamId != teamId || metadata.slackUserId != slackUserId) {
            return mapOf(TEXT_BLOCK_ID to "요청한 사용자를 확인할 수 없습니다. 다시 시작해주세요.")
        }

        val sourceName = values.value(SOURCE_NAME_BLOCK_ID, SOURCE_NAME_ACTION_ID).trim()
        val text = values.value(TEXT_BLOCK_ID, TEXT_ACTION_ID).trim()
        val files = values.files(FILE_BLOCK_ID, FILE_ACTION_ID)
        val fileId = files.singleOrNull()?.id?.takeIf(String::isNotBlank)
        val sourceType = values.selectedValue(SOURCE_TYPE_BLOCK_ID, SOURCE_TYPE_ACTION_ID)
        val audience = values.selectedValue(AUDIENCE_BLOCK_ID, AUDIENCE_ACTION_ID)
        val selectedUserIds = values.selectedValues(VIEWERS_BLOCK_ID, VIEWERS_ACTION_ID)
        val errors = linkedMapOf<String, String>()
        if (sourceName.isEmpty()) errors[SOURCE_NAME_BLOCK_ID] = "소스 이름을 입력해주세요."
        if (sourceType !in SOURCE_TYPES) errors[SOURCE_TYPE_BLOCK_ID] = "지원하는 소스 형식을 선택해주세요."
        if (files.size > 1) errors[FILE_BLOCK_ID] = "파일은 하나만 첨부해주세요."
        if (files.size == 1 && fileId == null) errors[FILE_BLOCK_ID] = "첨부한 파일 정보를 확인할 수 없습니다."
        when (sourceType) {
            SOURCE_TYPE_TEXT -> {
                if (text.isEmpty()) errors[TEXT_BLOCK_ID] = "지식 데이터를 입력해주세요."
                if (files.isNotEmpty()) errors[FILE_BLOCK_ID] = "파일은 카카오톡 내보내기 형식에서만 사용할 수 있습니다."
            }
            SOURCE_TYPE_KAKAO -> when {
                text.isEmpty() && files.isEmpty() ->
                    errors[TEXT_BLOCK_ID] = "내용을 붙여 넣거나 카카오톡 내보내기 파일을 첨부해주세요."
                text.isNotEmpty() && files.isNotEmpty() ->
                    errors[FILE_BLOCK_ID] = "붙여넣기와 파일 중 하나만 선택해주세요."
            }
        }
        if (audience !in AUDIENCES) errors[AUDIENCE_BLOCK_ID] = "열람 범위를 선택해주세요."
        if (audience == AUDIENCE_RESTRICTED && selectedUserIds.isEmpty()) {
            errors[VIEWERS_BLOCK_ID] = "열람할 사용자를 한 명 이상 선택해주세요."
        }
        if (errors.isNotEmpty()) return errors

        val access = if (audience == AUDIENCE_PUBLIC) {
            MemoryAccess.PUBLIC
        } else {
            runCatching { MemoryAccess.restricted(selectedUserIds.map(::UserId)) }
                .getOrElse { return mapOf(VIEWERS_BLOCK_ID to "유효한 열람자를 선택해주세요.") }
        }
        val input = PendingKnowledgeInjection(
            identity = ConversationIdentity(teamId, slackUserId),
            sourceType = sourceType,
            sourceName = sourceName,
            text = text.takeIf(String::isNotEmpty),
            fileId = fileId,
            access = access,
        )
        return runCatching {
            executor.execute { analyze(metadata.responseUrl, input) }
        }.fold(
            onSuccess = { emptyMap() },
            onFailure = {
                val blockId = if (input.fileId == null) TEXT_BLOCK_ID else FILE_BLOCK_ID
                mapOf(blockId to "분석 작업을 시작하지 못했습니다. 다시 시도해주세요.")
            },
        )
    }

    private fun analyze(responseUrl: String, input: PendingKnowledgeInjection) {
        runCatching { slack.respond(responseUrl, "지식을 분석하고 있습니다…") }
        val message = try {
            val text = input.text ?: slack.readTextFile(input.fileId.orEmpty(), MAX_KAKAO_FILE_BYTES).text
            val source = try {
                parseSource(input.sourceType, input.sourceName, text)
            } catch (error: IllegalArgumentException) {
                throw InvalidSourceDataException(error)
            }
            if (source.records.isEmpty()) throw NoSourceRecordsException()
            val request = KnowledgeInjectionRequest(input.identity, source, input.access)
            val result = runBlocking { workflow.execute(request) }
            "완료: 소스 레코드 ${result.importedRecordCount}개, 메모리 ${result.memoryCount}개를 저장했습니다."
        } catch (error: SlackFileReadException) {
            when (error.category) {
                "UNSUPPORTED_FILE_TYPE" -> "카카오톡 내보내기 .txt 파일만 사용할 수 있습니다."
                "FILE_TOO_LARGE" -> "파일은 5MB 이하여야 합니다."
                "INVALID_UTF8" -> "UTF-8로 저장된 카카오톡 내보내기 파일만 사용할 수 있습니다."
                "EMPTY_FILE" -> "첨부한 파일이 비어 있습니다."
                else -> "첨부한 파일을 읽지 못했습니다. 다시 시도해주세요."
            }
        } catch (_: NoSourceRecordsException) {
            "분석할 소스 레코드를 찾지 못했습니다."
        } catch (_: InvalidSourceDataException) {
            "소스 데이터를 읽을 수 없습니다. 입력 형식을 확인해주세요."
        } catch (_: KnowledgeInjectionRegistrationRequiredException) {
            "사용자 등록이 필요합니다. 앱과의 DM에서 등록을 완료한 뒤 다시 시도해주세요."
        } catch (_: DuplicateSourceRecordsException) {
            "이 소스의 모든 레코드는 이미 분석되었습니다."
        } catch (error: ConflictingSourceAudienceException) {
            val viewers = when (val preparation = workflow.prepare(input.identity)) {
                is KnowledgeInjectionPreparation.Ready ->
                    (listOf(preparation.requester) + preparation.availableViewers).distinctBy { it.userId }
                else -> emptyList()
            }
            conflictingAudienceMessage(error.existingAccess, viewers)
        } catch (_: InvalidMemoryAudienceException) {
            "열람 권한이 없는 사용자가 포함되어 있습니다."
        } catch (_: UserAccessDeniedException) {
            "지식 주입 권한이 없습니다."
        } catch (_: KnowledgeInjectionUnavailableException) {
            "지식 분석을 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
        } catch (_: MemoryAnalysisUnavailableException) {
            "지식 분석을 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
        } catch (error: Exception) {
            log.warn("Slack knowledge injection failed category={}", error.javaClass.simpleName)
            "지식 분석에 실패했습니다. 다시 시도해주세요."
        }
        runCatching { slack.respond(responseUrl, message) }
            .onFailure { log.warn("Knowledge injection result delivery failed category={}", it.javaClass.simpleName) }
    }

    private fun parseSource(sourceType: String, sourceName: String, text: String): SourceDocumentDraft =
        when (sourceType) {
            SOURCE_TYPE_TEXT -> PlainTextSourceParser.parse(sourceName, text)
            SOURCE_TYPE_KAKAO -> KakaoExportParser.parse(sourceName, text)
            else -> error("unsupported source type")
        }

    private fun knowledgeModal(
        invocation: SlackKnowledgeCommandInvocation,
        requester: RegisteredUser,
        availableViewers: List<RegisteredUser>,
    ): Map<String, Any> {
        val metadata = JsonSerializer.json.encodeToString(
            KnowledgeModalMetadata(
                teamId = invocation.teamId,
                slackUserId = invocation.slackUserId,
                responseUrl = invocation.responseUrl,
            ),
        )
        val viewerOptions = (listOf(requester) + availableViewers)
            .distinctBy { it.userId }
            .sortedBy { it.displayName }
            .map(::viewerOption)
        val requesterOption = viewerOption(requester)
        return mapOf(
            "type" to "modal",
            "callback_id" to VIEW_CALLBACK_ID,
            "private_metadata" to metadata,
            "title" to plainText("지식 주입"),
            "submit" to plainText("분석하고 저장"),
            "close" to plainText("취소"),
            "blocks" to listOf(
                inputBlock(
                    SOURCE_NAME_BLOCK_ID,
                    "소스 이름",
                    mapOf(
                        "type" to "plain_text_input",
                        "action_id" to SOURCE_NAME_ACTION_ID,
                        "max_length" to MAX_SOURCE_NAME_LENGTH,
                        "placeholder" to plainText("예: 가족 대화 2026-08"),
                    ),
                ),
                inputBlock(
                    SOURCE_TYPE_BLOCK_ID,
                    "소스 형식",
                    mapOf(
                        "type" to "static_select",
                        "action_id" to SOURCE_TYPE_ACTION_ID,
                        "options" to listOf(
                            option("직접 작성", SOURCE_TYPE_TEXT),
                            option("카카오톡 내보내기", SOURCE_TYPE_KAKAO),
                        ),
                        "initial_option" to option("직접 작성", SOURCE_TYPE_TEXT),
                    ),
                ),
                inputBlock(
                    FILE_BLOCK_ID,
                    "카카오톡 내보내기 파일 (.txt, 선택)",
                    mapOf(
                        "type" to "file_input",
                        "action_id" to FILE_ACTION_ID,
                        "filetypes" to listOf("txt"),
                        "max_files" to 1,
                    ),
                    optional = true,
                ),
                inputBlock(
                    TEXT_BLOCK_ID,
                    "내용 붙여넣기 (선택)",
                    mapOf(
                        "type" to "plain_text_input",
                        "action_id" to TEXT_ACTION_ID,
                        "multiline" to true,
                        "max_length" to MAX_DATA_LENGTH,
                        "placeholder" to plainText("직접 작성하거나 카카오톡 내보내기 내용을 붙여 넣으세요."),
                    ),
                    optional = true,
                ),
                inputBlock(
                    AUDIENCE_BLOCK_ID,
                    "열람 범위",
                    mapOf(
                        "type" to "radio_buttons",
                        "action_id" to AUDIENCE_ACTION_ID,
                        "options" to listOf(
                            option("지정 사용자", AUDIENCE_RESTRICTED),
                            option("전체 공개", AUDIENCE_PUBLIC),
                        ),
                        "initial_option" to option("지정 사용자", AUDIENCE_RESTRICTED),
                    ),
                ),
                inputBlock(
                    VIEWERS_BLOCK_ID,
                    "열람 사용자",
                    mapOf(
                        "type" to "multi_static_select",
                        "action_id" to VIEWERS_ACTION_ID,
                        "options" to viewerOptions,
                        "initial_options" to listOf(requesterOption),
                        "placeholder" to plainText("사용자를 선택하세요"),
                    ),
                    optional = true,
                ),
            ),
        )
    }

    private fun inputBlock(
        blockId: String,
        label: String,
        element: Map<String, Any>,
        optional: Boolean = false,
    ): Map<String, Any> = mapOf(
        "type" to "input",
        "block_id" to blockId,
        "label" to plainText(label),
        "element" to element,
        "optional" to optional,
    )

    private fun viewerOption(user: RegisteredUser): Map<String, Any> = option(user.displayName, user.userId.value)

    private fun option(label: String, value: String): Map<String, Any> = mapOf(
        "text" to plainText(label),
        "value" to value,
    )

    private fun plainText(text: String): Map<String, Any> = mapOf(
        "type" to "plain_text",
        "text" to text,
    )

    private fun Map<String, Map<String, ViewState.Value>>.field(blockId: String, actionId: String): ViewState.Value? =
        this[blockId]?.get(actionId)

    private fun Map<String, Map<String, ViewState.Value>>.value(blockId: String, actionId: String): String =
        field(blockId, actionId)?.value.orEmpty()

    private fun Map<String, Map<String, ViewState.Value>>.selectedValue(blockId: String, actionId: String): String =
        field(blockId, actionId)?.selectedOption?.value.orEmpty()

    private fun Map<String, Map<String, ViewState.Value>>.selectedValues(blockId: String, actionId: String): Set<String> =
        field(blockId, actionId)?.selectedOptions.orEmpty().mapNotNullTo(linkedSetOf()) { it.value }

    private fun Map<String, Map<String, ViewState.Value>>.files(blockId: String, actionId: String): List<File> =
        field(blockId, actionId)?.files.orEmpty()

    companion object {
        const val COMMAND_NAME = "/knowledge"
        const val VIEW_CALLBACK_ID = "knowledge_injection_submit"
        const val SOURCE_NAME_BLOCK_ID = "knowledge_source_name"
        const val SOURCE_NAME_ACTION_ID = "knowledge_source_name_input"
        const val SOURCE_TYPE_BLOCK_ID = "knowledge_source_type"
        const val SOURCE_TYPE_ACTION_ID = "knowledge_source_type_select"
        const val AUDIENCE_BLOCK_ID = "knowledge_audience"
        const val AUDIENCE_ACTION_ID = "knowledge_audience_select"
        const val VIEWERS_BLOCK_ID = "knowledge_viewers"
        const val VIEWERS_ACTION_ID = "knowledge_viewers_select"
        const val TEXT_BLOCK_ID = "knowledge_text"
        const val TEXT_ACTION_ID = "knowledge_text_input"
        const val FILE_BLOCK_ID = "knowledge_file"
        const val FILE_ACTION_ID = "knowledge_file_input"
        private const val SOURCE_TYPE_TEXT = "TEXT"
        private const val SOURCE_TYPE_KAKAO = "KAKAO"
        private const val AUDIENCE_RESTRICTED = "RESTRICTED"
        private const val AUDIENCE_PUBLIC = "PUBLIC"
        private const val MAX_SOURCE_NAME_LENGTH = 100
        private const val MAX_DATA_LENGTH = 3_000
        private const val MAX_KAKAO_FILE_BYTES = 5 * 1024 * 1024
        private val SOURCE_TYPES = setOf(SOURCE_TYPE_TEXT, SOURCE_TYPE_KAKAO)
        private val AUDIENCES = setOf(AUDIENCE_RESTRICTED, AUDIENCE_PUBLIC)

        internal fun conflictingAudienceMessage(
            existingAccess: MemoryAccess,
            availableViewers: List<RegisteredUser>,
        ): String = when (existingAccess.visibility) {
            MemoryVisibility.PUBLIC ->
                "같은 내용이 이미 전체 공개로 등록되어 있습니다.\n" +
                    "다시 등록하려면 열람 범위에서 ‘전체 공개’를 선택해주세요. " +
                    "기존 열람 범위는 재등록으로 변경할 수 없습니다."
            MemoryVisibility.RESTRICTED -> {
                val displayNameByUserId = availableViewers.associate { it.userId.value to it.displayName }
                val viewerNames = existingAccess.allowedUserIds
                    .map { displayNameByUserId[it] ?: it }
                    .sorted()
                    .joinToString(", ")
                "같은 내용이 이미 지정 사용자 범위로 등록되어 있습니다.\n" +
                    "다시 등록하려면 ‘지정 사용자’를 선택한 뒤 열람 사용자를 다음과 정확히 " +
                    "맞춰주세요: $viewerNames.\n" +
                    "기존 열람 범위는 재등록으로 변경할 수 없습니다."
            }
        }
    }
}

private data class PendingKnowledgeInjection(
    val identity: ConversationIdentity,
    val sourceType: String,
    val sourceName: String,
    val text: String?,
    val fileId: String?,
    val access: MemoryAccess,
)

private class NoSourceRecordsException : RuntimeException()

private class InvalidSourceDataException(cause: Throwable) : RuntimeException(cause)

internal data class SlackKnowledgeCommandInvocation(
    val teamId: String,
    val slackUserId: String,
    val channelId: String,
    val triggerId: String,
    val responseUrl: String,
)

@Serializable
private data class KnowledgeModalMetadata(
    val teamId: String,
    val slackUserId: String,
    val responseUrl: String,
)
