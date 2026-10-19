package com.homeassistant.llm.codex.transport

import com.homeassistant.llm.codex.transport.process.StdioProcessTransport
import com.homeassistant.llm.codex.transport.protocol.AppServerProtocol
import com.homeassistant.llm.codex.transport.protocol.ClientInfo
import com.homeassistant.llm.codex.transport.protocol.ReadOnlySandboxPolicy
import com.homeassistant.llm.codex.transport.protocol.TextUserInput
import com.homeassistant.llm.codex.transport.protocol.ThreadConfig
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies the typed Codex protocol over a real stdio process transport. */
class DefaultCodexAppServerTransportTest {
    @Test
    fun `executes typed app server operations and delivers typed thread events`() {
        transport("normal").use { transport ->
            val listener = RecordingListener()
            assertTrue(transport.start(listener))

            transport.initialize(initialize()).get(5, TimeUnit.SECONDS)
            val thread = transport.startThread(startThread()).get(5, TimeUnit.SECONDS).thread
            assertEquals(THREAD_ID, thread.id)
            assertEquals(
                THREAD_ID,
                transport.resumeThread(resumeThread()).get(5, TimeUnit.SECONDS).thread.id,
            )
            assertEquals("turn-1", transport.startTurn(startTurn()).get(5, TimeUnit.SECONDS).turn.id)
            transport.interruptTurn(AppServerProtocol.TurnInterrupt(THREAD_ID, "turn-1"))
                .get(5, TimeUnit.SECONDS)
            transport.unsubscribeThread(AppServerProtocol.ThreadUnsubscribe(THREAD_ID))
                .get(5, TimeUnit.SECONDS)

            assertEquals("answer", listener.items.poll(5, TimeUnit.SECONDS)?.item?.text)
            assertEquals("completed", listener.turns.poll(5, TimeUnit.SECONDS)?.turn?.status)
            assertEquals(0, listener.exitCalls.get())
        }
    }

    @Test
    fun `rejects unsupported server requests inside the transport`() {
        transport("server-request").use { transport ->
            val listener = RecordingListener()
            assertTrue(transport.start(listener))

            transport.initialize(initialize()).get(5, TimeUnit.SECONDS)

            assertEquals(THREAD_ID, listener.closedThreads.poll(5, TimeUnit.SECONDS)?.threadId)
        }
    }

    @Test
    fun `reports malformed known notifications without closing the process`() {
        transport("malformed-notification").use { transport ->
            val listener = RecordingListener()
            assertTrue(transport.start(listener))
            transport.initialize(initialize()).get(5, TimeUnit.SECONDS)

            transport.startTurn(startTurn()).get(5, TimeUnit.SECONDS)

            assertTrue(listener.protocolError.await(5, TimeUnit.SECONDS))
            assertTrue(transport.start(listener))
            assertEquals(0, listener.exitCalls.get())
        }
    }

    @Test
    fun `does not restart while the process exit callback is running`() {
        transport("exit-on-thread-start").use { transport ->
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val startDuringCallback = CompletableFuture<Boolean>()
            val listener = object : RecordingListener() {
                override fun onAppServerExited() {
                    super.onAppServerExited()
                    startDuringCallback.complete(transport.start(RecordingListener()))
                    callbackEntered.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(transport.start(listener))
            transport.initialize(initialize()).get(5, TimeUnit.SECONDS)

            val request = transport.startThread(startThread())

            assertFailsWith<Exception> { request.get(5, TimeUnit.SECONDS) }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            assertFalse(startDuringCallback.get(5, TimeUnit.SECONDS))
            releaseCallback.countDown()
            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertEquals(1, listener.exitCalls.get())
            assertEventually { transport.start(RecordingListener()) }
        }
    }

    @Test
    fun `stop unblocks a pending write and permits a later start`() {
        transport("stall").use { transport ->
            val listener = RecordingListener()
            assertTrue(transport.start(listener))
            transport.initialize(initialize()).get(5, TimeUnit.SECONDS)
            val sending = CountDownLatch(1)
            val write = CompletableFuture.runAsync {
                sending.countDown()
                runCatching { transport.startTurn(startTurn("x".repeat(8 * 1024 * 1024))) }
            }
            assertTrue(sending.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { write.get(250, TimeUnit.MILLISECONDS) }

            transport.stop()

            write.get(10, TimeUnit.SECONDS)
            assertTrue(transport.start(RecordingListener()))
            assertEquals(0, listener.exitCalls.get())
        }
    }

    @Test
    fun `close is terminal and missing executable fails safely`() {
        DefaultCodexAppServerTransport(
            StdioProcessTransport(
                listOf("nonexistent-codex-app-server-test-executable"),
                Path.of("."),
            ),
        ).use { transport ->
            assertFalse(transport.start(RecordingListener()))
        }

        val transport = transport("normal")
        assertTrue(transport.start(RecordingListener()))
        transport.close()
        assertFalse(transport.start(RecordingListener()))
    }

    private fun transport(mode: String): CodexAppServerTransport {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return DefaultCodexAppServerTransport(
            StdioProcessTransport(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    Path.of(CodexAppServerFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(),
                    CodexAppServerFixture::class.java.name,
                    mode,
                ),
                Path.of("."),
            ),
        )
    }

    private fun initialize(): AppServerProtocol.Initialize =
        AppServerProtocol.Initialize(ClientInfo("test", "Test", "1"))

    private fun startThread(): AppServerProtocol.ThreadStart = AppServerProtocol.ThreadStart(
        model = "test-model",
        cwd = ".",
        config = ThreadConfig(modelReasoningEffort = "medium"),
    )

    private fun resumeThread(): AppServerProtocol.ThreadResume = AppServerProtocol.ThreadResume(
        threadId = THREAD_ID,
        model = "test-model",
        cwd = ".",
        config = ThreadConfig(modelReasoningEffort = "medium"),
    )

    private fun startTurn(prompt: String = "hello"): AppServerProtocol.TurnStart = AppServerProtocol.TurnStart(
        threadId = THREAD_ID,
        input = listOf(TextUserInput(text = prompt)),
        cwd = ".",
        sandboxPolicy = ReadOnlySandboxPolicy(),
        model = "test-model",
        effort = "medium",
        outputSchema = buildJsonObject {},
    )

    private fun assertEventually(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private open class RecordingListener : CodexAppServerTransport.Listener {
        val items = LinkedBlockingQueue<AppServerProtocol.ItemCompleted>()
        val turns = LinkedBlockingQueue<AppServerProtocol.TurnCompleted>()
        val closedThreads = LinkedBlockingQueue<AppServerProtocol.ThreadClosed>()
        val protocolError = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val exitCalls = AtomicInteger()

        override fun onItemCompleted(event: AppServerProtocol.ItemCompleted) {
            items += event
        }

        override fun onTurnCompleted(event: AppServerProtocol.TurnCompleted) {
            turns += event
        }

        override fun onThreadClosed(event: AppServerProtocol.ThreadClosed) {
            closedThreads += event
        }

        override fun onProtocolError() {
            protocolError.countDown()
        }

        override fun onAppServerExited() {
            exitCalls.incrementAndGet()
            exited.countDown()
        }
    }

    private companion object {
        const val THREAD_ID = "00000000-0000-0000-0000-000000000001"
    }
}
