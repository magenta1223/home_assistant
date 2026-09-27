package com.homeassistant.codex.transport

import com.homeassistant.codex.transport.process.ProcessTransport
import com.homeassistant.codex.transport.protocol.AppServerProtocol
import com.homeassistant.codex.transport.protocol.CODEX_JSON
import com.homeassistant.codex.transport.protocol.IncomingAppServerMessage
import com.homeassistant.codex.transport.protocol.ThreadResult
import com.homeassistant.codex.transport.protocol.TurnStartResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class DefaultCodexAppServerTransport(
    private val process: ProcessTransport,
) : CodexAppServerTransport {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()

    override fun start(listener: CodexAppServerTransport.Listener): Boolean =
        process.start(ProcessListener(listener))

    override fun initialize(request: AppServerProtocol.Initialize): CompletableFuture<Unit> =
        request(
            method = request.method,
            params = request,
            serializer = AppServerProtocol.Initialize.serializer(),
            resultDeserializer = JsonObject.serializer(),
        ).thenApply {
            sendNotification(
                method = AppServerProtocol.Initialized.method,
                params = AppServerProtocol.Initialized,
                serializer = AppServerProtocol.Initialized.serializer(),
            )
            Unit
        }

    override fun startThread(request: AppServerProtocol.ThreadStart): CompletableFuture<ThreadResult> =
        request(
            method = request.method,
            params = request,
            serializer = AppServerProtocol.ThreadStart.serializer(),
            resultDeserializer = ThreadResult.serializer(),
        )

    override fun resumeThread(request: AppServerProtocol.ThreadResume): CompletableFuture<ThreadResult> =
        request(
            method = request.method,
            params = request,
            serializer = AppServerProtocol.ThreadResume.serializer(),
            resultDeserializer = ThreadResult.serializer(),
        )

    override fun startTurn(request: AppServerProtocol.TurnStart): CompletableFuture<TurnStartResult> =
        request(
            method = request.method,
            params = request,
            serializer = AppServerProtocol.TurnStart.serializer(),
            resultDeserializer = TurnStartResult.serializer(),
        )

    override fun interruptTurn(request: AppServerProtocol.TurnInterrupt): CompletableFuture<Unit> =
        requestUnit(
            method = request.method,
            params = request,
            serializer = AppServerProtocol.TurnInterrupt.serializer(),
        )

    override fun unsubscribeThread(request: AppServerProtocol.ThreadUnsubscribe): CompletableFuture<Unit> =
        requestUnit(
            method = request.method,
            params = request,
            serializer = AppServerProtocol.ThreadUnsubscribe.serializer(),
        )

    override fun stop() {
        process.stop()
        failPending(CodexAppServerTransportException("Codex app-server was stopped"))
    }

    override fun close() {
        process.close()
        failPending(CodexAppServerTransportException("Codex app-server transport is closed"))
    }

    private fun <P, R> request(
        method: String,
        params: P,
        serializer: SerializationStrategy<P>,
        resultDeserializer: DeserializationStrategy<R>,
    ): CompletableFuture<R> = sendRequest(method, CODEX_JSON.encodeToJsonElement(serializer, params))
        .thenApply { result ->
            runCatching { CODEX_JSON.decodeFromJsonElement(resultDeserializer, result) }
                .getOrElse { throw CodexAppServerTransportException("Invalid Codex app-server result", it) }
        }

    private fun <P> requestUnit(
        method: String,
        params: P,
        serializer: SerializationStrategy<P>,
    ): CompletableFuture<Unit> = sendRequest(method, CODEX_JSON.encodeToJsonElement(serializer, params))
        .thenApply { Unit }

    private fun sendRequest(method: String, params: JsonElement): CompletableFuture<JsonElement> {
        val id = requestIds.getAndIncrement()
        val result = CompletableFuture<JsonElement>()
        pending[id] = result
        result.whenComplete { _, _ -> pending.remove(id, result) }
        runCatching {
            process.send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString())
        }.onFailure { error ->
            pending.remove(id, result)
            result.completeExceptionally(error)
        }
        return result
    }

    private fun <P> sendNotification(
        method: String,
        params: P,
        serializer: SerializationStrategy<P>,
    ) {
        process.send(buildJsonObject {
            put("method", method)
            put("params", CODEX_JSON.encodeToJsonElement(serializer, params))
        }.toString())
    }

    private fun handleMessage(listener: CodexAppServerTransport.Listener, line: String) {
        val message = try {
            CODEX_JSON.decodeFromString(IncomingAppServerMessage.serializer(), line)
        } catch (error: Exception) {
            failPending(CodexAppServerTransportException("Invalid Codex app-server message", error))
            notifyProtocolError(listener)
            return
        }
        val method = message.method
        val id = message.id
        when {
            method == null && id != null -> completeRequest(message)
            method != null && id != null -> respondUnsupported(id)
            method != null -> handleNotification(listener, method, message.params)
            else -> {
                failPending(CodexAppServerTransportException("Invalid Codex app-server message"))
                notifyProtocolError(listener)
            }
        }
    }

    private fun completeRequest(message: IncomingAppServerMessage) {
        val id = message.id?.longOrNull ?: return
        val request = pending.remove(id) ?: return
        when {
            message.error != null -> request.completeExceptionally(
                CodexAppServerTransportException("Codex app-server RPC error ${message.error.code}"),
            )
            message.result != null -> request.complete(message.result)
            else -> request.completeExceptionally(
                CodexAppServerTransportException("Codex app-server response has no result"),
            )
        }
    }

    private fun handleNotification(
        listener: CodexAppServerTransport.Listener,
        method: String,
        params: JsonElement?,
    ) {
        try {
            val value = requireNotNull(params) { "Missing notification params" }
            when (method) {
                AppServerProtocol.ItemCompleted.METHOD -> {
                    val event = CODEX_JSON.decodeFromJsonElement(AppServerProtocol.ItemCompleted.serializer(), value)
                    notifyListener(listener) { it.onItemCompleted(event) }
                }
                AppServerProtocol.TurnCompleted.METHOD -> {
                    val event = CODEX_JSON.decodeFromJsonElement(AppServerProtocol.TurnCompleted.serializer(), value)
                    notifyListener(listener) { it.onTurnCompleted(event) }
                }
                AppServerProtocol.ThreadClosed.METHOD -> {
                    val event = CODEX_JSON.decodeFromJsonElement(AppServerProtocol.ThreadClosed.serializer(), value)
                    notifyListener(listener) { it.onThreadClosed(event) }
                }
            }
        } catch (error: Exception) {
            log.warn(
                "Rejected malformed Codex app-server notification method={} category={}",
                method,
                error.javaClass.simpleName,
            )
            notifyProtocolError(listener)
        }
    }

    private fun respondUnsupported(id: JsonPrimitive) {
        runCatching {
            process.send(buildJsonObject {
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32601)
                    put("message", "Unsupported server request")
                })
            }.toString())
        }.onFailure {
            log.debug("Failed to reject unsupported Codex app-server request")
        }
    }

    private fun notifyProtocolError(listener: CodexAppServerTransport.Listener) {
        notifyListener(listener, CodexAppServerTransport.Listener::onProtocolError)
    }

    private fun notifyListener(
        listener: CodexAppServerTransport.Listener,
        callback: (CodexAppServerTransport.Listener) -> Unit,
    ) {
        runCatching { callback(listener) }
            .onFailure {
                log.warn("Codex app-server listener failed category={}", it.javaClass.simpleName)
            }
    }

    private fun failPending(error: Exception) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private inner class ProcessListener(
        private val listener: CodexAppServerTransport.Listener,
    ) : ProcessTransport.Listener {
        override fun onMessage(message: String) {
            handleMessage(listener, message)
        }

        override fun onProcessExited() {
            failPending(CodexAppServerTransportException("Codex app-server exited"))
            runCatching { listener.onAppServerExited() }
                .onFailure {
                    log.warn("Codex app-server exit listener failed category={}", it.javaClass.simpleName)
                }
        }
    }
}

internal class CodexAppServerTransportException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
