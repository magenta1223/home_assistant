package com.homeassistant.codex.conversation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class JsonRpcError(
    val code: Int,
    val message: String,
)

@Serializable
internal data class IncomingAppServerMessage(
    val id: JsonPrimitive? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
internal sealed class AppServerProtocol(
    @Transient val method: String = "",
) {
    @Serializable
    data class Initialize(val clientInfo: ClientInfo) : AppServerProtocol("initialize")

    @Serializable
    data object Initialized : AppServerProtocol("initialized")

    @Serializable
    data class ThreadStart(
        val model: String,
        val cwd: String,
        val approvalPolicy: ApprovalPolicy = ApprovalPolicy.NEVER,
        val sandbox: SandboxMode = SandboxMode.READ_ONLY,
        val serviceName: String = "home_second_brain",
        val config: ThreadConfig,
    ) : AppServerProtocol("thread/start")

    @Serializable
    data class ThreadResume(
        val threadId: String,
        val model: String,
        val cwd: String,
        val approvalPolicy: ApprovalPolicy = ApprovalPolicy.NEVER,
        val sandbox: SandboxMode = SandboxMode.READ_ONLY,
        val config: ThreadConfig,
    ) : AppServerProtocol("thread/resume")

    @Serializable
    data class ThreadUnsubscribe(val threadId: String) : AppServerProtocol("thread/unsubscribe")

    @Serializable
    data class TurnStart(
        val threadId: String,
        val input: List<TextUserInput>,
        val cwd: String,
        val approvalPolicy: ApprovalPolicy = ApprovalPolicy.NEVER,
        val sandboxPolicy: ReadOnlySandboxPolicy = ReadOnlySandboxPolicy(),
        val model: String,
        val effort: String,
        val outputSchema: JsonElement,
    ) : AppServerProtocol("turn/start")

    @Serializable
    data class TurnInterrupt(
        val threadId: String,
        val turnId: String,
    ) : AppServerProtocol("turn/interrupt")

    @Serializable
    data class ItemCompleted(
        val threadId: String,
        val turnId: String,
        val item: AppServerThreadItem,
        val completedAtMs: Long,
    ) : AppServerProtocol(METHOD) {
        companion object {
            const val METHOD = "item/completed"
        }
    }

    @Serializable
    data class TurnCompleted(
        val threadId: String,
        val turn: AppServerTurn,
    ) : AppServerProtocol(METHOD) {
        companion object {
            const val METHOD = "turn/completed"
        }
    }

    @Serializable
    data class ThreadClosed(val threadId: String) : AppServerProtocol(METHOD) {
        companion object {
            const val METHOD = "thread/closed"
        }
    }
}

@Serializable
internal data class ClientInfo(
    val name: String,
    val title: String,
    val version: String,
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
