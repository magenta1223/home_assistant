package com.homeassistant.llm.codex.transport

import com.homeassistant.llm.codex.transport.process.ProcessTransport
import com.homeassistant.llm.codex.transport.process.StdioProcessTransport
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StdioProcessTransportTest {
    @Test
    fun `transports lines and expected stop permits restart without an exit event`() {
        transport().use { transport ->
            val first = RecordingListener()
            assertTrue(transport.start(first))
            transport.send("안녕하세요")
            assertEquals("안녕하세요", first.messages.poll(5, TimeUnit.SECONDS))

            transport.stop()

            assertEquals(0, first.exitCalls)
            val second = RecordingListener()
            assertTrue(transport.start(second))
            transport.send("restarted")
            assertEquals("restarted", second.messages.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `unexpected exit rejects start until the exit callback returns`() {
        transport().use { transport ->
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val startDuringCallback = CompletableFuture<Boolean>()
            val listener = object : RecordingListener() {
                override fun onProcessExited() {
                    super.onProcessExited()
                    startDuringCallback.complete(transport.start(RecordingListener()))
                    callbackEntered.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(transport.start(listener))

            transport.send("exit")

            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            assertFalse(startDuringCallback.get(5, TimeUnit.SECONDS))
            releaseCallback.countDown()
            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertEventually { transport.start(RecordingListener()) }
        }
    }

    private fun transport(): StdioProcessTransport {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return StdioProcessTransport(
            listOf(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8",
                "-cp",
                Path.of(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(),
                ProcessFixture::class.java.name,
            ),
            Path.of("."),
        )
    }

    private fun assertEventually(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private open class RecordingListener : ProcessTransport.Listener {
        val messages = LinkedBlockingQueue<String>()
        val exited = CountDownLatch(1)
        var exitCalls = 0
            private set

        override fun onMessage(message: String) {
            messages += message
        }

        override fun onProcessExited() {
            exitCalls++
            exited.countDown()
        }
    }
}
