package com.homeassistant.codex.rpcclient.lifecycle

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StdioCodexRpcClientTest {
    @Test
    fun `stdio echoes UTF8 lines restarts and treats EOF as closure`() {
        client("echo").use { client ->
            val messages = LinkedBlockingQueue<String>()
            val closed = LinkedBlockingQueue<RpcSession>()
            val first = assertNotNull(client.start({ _, line -> messages.put(line) }, closed::put))
            client.send(first, "안녕하세요")
            assertEquals("안녕하세요", messages.poll(5, TimeUnit.SECONDS))
            client.stop()
            val second = assertNotNull(client.start({ _, line -> messages.put(line) }, closed::put))
            assertNotSame(first, second)
            client.send(second, "eof")
            assertEquals(second, closed.poll(10, TimeUnit.SECONDS))
            assertNull(closed.poll())
        }
    }

    @Test
    fun `stop can terminate a child with a pending large write`() {
        client("stall").use { client ->
            val ready = CountDownLatch(1)
            val session = assertNotNull(client.start({ _, _ -> ready.countDown() }, {}))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val sending = CountDownLatch(1)
            val send = CompletableFuture.runAsync {
                sending.countDown()
                runCatching { client.send(session, "x".repeat(8 * 1024 * 1024)) }
            }
            assertTrue(sending.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { send.get(250, TimeUnit.MILLISECONDS) }
            val stop = CompletableFuture.runAsync { client.stop() }
            stop.get(10, TimeUnit.SECONDS)
            send.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `missing executable returns failure and close remains safe`() {
        StdioCodexRpcClient(listOf("nonexistent-codex-rpc-test-executable"), Path.of(".")).use { client ->
            assertNull(client.start({ _, _ -> }, {}))
        }
    }

    private fun client(mode: String): StdioCodexRpcClient {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return StdioCodexRpcClient(
            listOf(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8",
                "-cp",
                Path.of(StdioFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(),
                StdioFixture::class.java.name,
                mode,
            ),
            Path.of("."),
        )
    }
}
