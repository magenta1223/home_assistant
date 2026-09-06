package com.homeassistant.codex.conversation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class JsonRpcRequest<P>(
    val id: Long,
    val method: String,
    val params: P,
)

@Serializable
internal data class JsonRpcNotification<P>(
    val method: String,
    val params: P,
)

@Serializable
internal data class JsonRpcSuccessResponse(
    val id: RequestId,
    val result: JsonElement,
)

@Serializable
internal data class JsonRpcErrorResponse(
    val id: RequestId,
    val error: JsonRpcError,
)

@Serializable
internal data class JsonRpcError(
    val code: Int,
    val message: String,
)

@Serializable
internal data class IncomingAppServerMessage(
    val id: RequestId? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable(with = RequestIdSerializer::class)
internal sealed interface RequestId {
    data class Number(val value: Long) : RequestId
    data class Text(val value: String) : RequestId
}

internal object RequestIdSerializer : KSerializer<RequestId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RequestId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RequestId) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RequestId is supported only for JSON")
        jsonEncoder.encodeJsonElement(
            when (value) {
                is RequestId.Number -> JsonPrimitive(value.value)
                is RequestId.Text -> JsonPrimitive(value.value)
            },
        )
    }

    override fun deserialize(decoder: Decoder): RequestId {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RequestId is supported only for JSON")
        val value = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("RequestId must be a string or integer")
        if (value.isString) return RequestId.Text(value.content)
        return value.longOrNull?.let(RequestId::Number)
            ?: throw SerializationException("RequestId must be a string or integer")
    }
}

internal class AppServerRequestMethod<P, R>(
    val wireName: String,
    val paramsSerializer: KSerializer<P>,
    val resultSerializer: KSerializer<R>,
)

internal class AppServerNotificationMethod<P>(
    val wireName: String,
    val paramsSerializer: KSerializer<P>,
)

internal object AppServerProtocol {
    val initialize = AppServerRequestMethod(
        "initialize",
        InitializeParams.serializer(),
        JsonObject.serializer(),
    )
    val initialized = AppServerNotificationMethod("initialized", EmptyParams.serializer())
    val threadStart = AppServerRequestMethod(
        "thread/start",
        ThreadStartParams.serializer(),
        ThreadResult.serializer(),
    )
    val threadResume = AppServerRequestMethod(
        "thread/resume",
        ThreadResumeParams.serializer(),
        ThreadResult.serializer(),
    )
    val threadUnsubscribe = AppServerRequestMethod(
        "thread/unsubscribe",
        ThreadUnsubscribeParams.serializer(),
        JsonObject.serializer(),
    )
    val turnStart = AppServerRequestMethod(
        "turn/start",
        TurnStartParams.serializer(),
        TurnStartResult.serializer(),
    )
    val turnInterrupt = AppServerRequestMethod(
        "turn/interrupt",
        TurnInterruptParams.serializer(),
        JsonObject.serializer(),
    )

    val itemCompleted = AppServerNotificationMethod(
        "item/completed",
        ItemCompletedParams.serializer(),
    )
    val turnCompleted = AppServerNotificationMethod(
        "turn/completed",
        TurnCompletedParams.serializer(),
    )
    val threadClosed = AppServerNotificationMethod(
        "thread/closed",
        ThreadClosedParams.serializer(),
    )
}

@Serializable
internal data class InitializeParams(val clientInfo: ClientInfo)

@Serializable
internal data class ClientInfo(
    val name: String,
    val title: String,
    val version: String,
)

@Serializable
internal object EmptyParams

@Serializable
internal data class ThreadStartParams(
    val model: String,
    val cwd: String,
    val approvalPolicy: ApprovalPolicy,
    val sandbox: SandboxMode,
    val serviceName: String,
    val config: ThreadConfig,
)

@Serializable
internal data class ThreadResumeParams(
    val threadId: String,
    val model: String,
    val cwd: String,
    val approvalPolicy: ApprovalPolicy,
    val sandbox: SandboxMode,
    val config: ThreadConfig,
)

@Serializable
internal data class ThreadUnsubscribeParams(val threadId: String)

@Serializable
internal data class TurnStartParams(
    val threadId: String,
    val input: List<TextUserInput>,
    val cwd: String,
    val approvalPolicy: ApprovalPolicy,
    val sandboxPolicy: ReadOnlySandboxPolicy,
    val model: String,
    val effort: String,
    val outputSchema: JsonElement,
)

@Serializable
internal data class TurnInterruptParams(
    val threadId: String,
    val turnId: String,
)

@Serializable
internal data class TextUserInput(
    val type: UserInputType = UserInputType.TEXT,
    val text: String,
)

@Serializable
internal data class ReadOnlySandboxPolicy(
    val type: SandboxPolicyType = SandboxPolicyType.READ_ONLY,
)

@Serializable
internal data class ThreadConfig(
    @SerialName("web_search") val webSearch: WebSearchMode = WebSearchMode.DISABLED,
    @SerialName("model_reasoning_effort") val modelReasoningEffort: String,
)

@Serializable
internal enum class ApprovalPolicy {
    @SerialName("never") NEVER,
}

@Serializable
internal enum class SandboxMode {
    @SerialName("read-only") READ_ONLY,
}

@Serializable
internal enum class SandboxPolicyType {
    @SerialName("readOnly") READ_ONLY,
}

@Serializable
internal enum class UserInputType {
    @SerialName("text") TEXT,
}

@Serializable
internal enum class WebSearchMode {
    @SerialName("disabled") DISABLED,
}

@Serializable
internal data class ThreadResult(val thread: AppServerThread)

@Serializable
internal data class AppServerThread(val id: String)

@Serializable
internal data class TurnStartResult(val turn: AppServerTurnReference)

@Serializable
internal data class AppServerTurnReference(val id: String)

@Serializable
internal data class ItemCompletedParams(
    val threadId: String,
    val turnId: String,
    val item: AppServerThreadItem,
    val completedAtMs: Long,
)

@Serializable
internal data class TurnCompletedParams(
    val threadId: String,
    val turn: AppServerTurn,
)

@Serializable
internal data class ThreadClosedParams(val threadId: String)

@Serializable
internal data class AppServerTurn(
    val id: String,
    val status: String,
    val items: List<AppServerThreadItem>,
    val error: JsonElement?,
)

@Serializable
internal data class AppServerThreadItem(
    val type: String,
    val text: String? = null,
)

@Serializable
internal data class StructuredAnswer(val answer: String)
