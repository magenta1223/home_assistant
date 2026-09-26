package com.homeassistant.codex.rpcclient.lifecycle

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AbstractCodexRpcClientTest {
    @Test
    fun `start is idempotent stop permits restart and close is terminal`() {
        FakeClient().use { client ->
            val first = client.start()
            assertSame(first, client.start())
            assertEquals(1, client.resources.size)
            client.send(first, "hello")
            assertEquals(listOf("hello"), client.resources[0].writes)
            client.stop()
            client.stop()
            assertEquals(1, client.resources[0].releases.get())
            val second = client.start()
            assertNotSame(first, second)
            assertFailsWith<IllegalStateException> { client.send(first, "stale") }
            client.close()
            client.close()
            assertNull(client.start({ _, _ -> }, {}))
            assertFailsWith<IllegalStateException> { client.send(second, "closed") }
            assertEquals(1, client.resources[1].releases.get())
            assertTrue(client.closedSessions.isEmpty())
        }
    }

    @Test
    fun `close waits for open then releases the resource`() {
        FakeClient().use { client ->
            client.openGate = CountDownLatch(1)
            val opening = CompletableFuture.supplyAsync { client.start({ _, _ -> }, {}) }
            client.openEntered.awaitChecked()
            val closing = CompletableFuture.runAsync { client.close() }
            client.openGate.countDown()
            assertNotNull(opening.get(5, TimeUnit.SECONDS))
            closing.get(5, TimeUnit.SECONDS)
            assertEquals(1, client.resources.single().releases.get())
            assertNull(client.start({ _, _ -> }, {}))
        }
    }

    @Test
    fun `stop waits for open and permits a subsequent restart`() {
        FakeClient().use { client ->
            client.openGate = CountDownLatch(1)
            val opening = CompletableFuture.supplyAsync { client.start({ _, _ -> }, {}) }
            client.openEntered.awaitChecked()
            val stopping = CompletableFuture.runAsync { client.stop() }
            client.openGate.countDown()
            assertNotNull(opening.get(5, TimeUnit.SECONDS))
            stopping.get(5, TimeUnit.SECONDS)
            assertNotNull(client.start())
            assertEquals(1, client.resources.first().releases.get())
        }
    }

    @Test
    fun `open failure rolls back and allows a later start`() {
        FakeClient().use { client ->
            client.failOpen = true
            assertNull(client.start({ _, _ -> }, {}))
            client.failOpen = false
            assertNotNull(client.start())
        }
    }

    @Test
    fun `EOF retires a still alive resource and notifies once`() {
        FakeClient().use { client ->
            val session = client.start()
            client.resources.single().input.put(EOF)
            client.closedSignal.awaitChecked()
            assertEquals(listOf(session), client.closedSessions)
            assertEquals(1, client.resources.single().releases.get())
            client.stop()
            assertNotNull(client.start())
        }
    }

    @Test
    fun `dead observation retires the old session before restart`() {
        FakeClient().use { client ->
            val old = client.start()
            client.resources.single().alive = false
            val replacement = client.start()
            assertNotSame(old, replacement)
            assertEquals(listOf(old), client.closedSessions)
            assertEquals(1, client.resources.first().releases.get())
        }
    }

    @Test
    fun `stop unblocks a write and its late failure cannot close the replacement`() {
        FakeClient().use { client ->
            val old = client.start()
            val resource = client.resources.single()
            resource.writeGate = CountDownLatch(1)
            val send = CompletableFuture.runAsync {
                assertFailsWith<IOException> { client.send(old, "blocked") }
            }
            resource.writeEntered.awaitChecked()
            client.stop()
            val replacement = client.start()
            resource.writeGate.countDown()
            send.get(5, TimeUnit.SECONDS)
            client.send(replacement, "new")
            assertEquals(listOf("new"), client.resources.last().writes)
            assertTrue(client.closedSessions.isEmpty())
        }
    }

    @Test
    fun `late EOF from old reader cannot retire a replacement`() {
        FakeClient().use { client ->
            client.start()
            val old = client.resources.single()
            old.readExitGate = CountDownLatch(1)
            client.stop()
            val replacement = client.start()
            old.readExitGate.countDown()
            client.readerTasks.tasks.first().get(5, TimeUnit.SECONDS)
            client.send(replacement, "still running")
            assertEquals(1, old.releases.get())
            assertTrue(client.closedSessions.isEmpty())
        }
    }

    @Test
    fun `write failure notifies outside locks and permits reentrant start`() {
        FakeClient().use { client ->
            val restarted = CompletableFuture<RpcSession>()
            val first = assertNotNull(client.start({ _, _ -> }) {
                // Another thread must be able to enter lifecycle methods during the callback.
                restarted.complete(CompletableFuture.supplyAsync { client.start() }.get(5, TimeUnit.SECONDS))
            })
            client.resources.single().failWrite = true
            assertFailsWith<IOException> { client.send(first, "fail") }
            val replacement = restarted.get(5, TimeUnit.SECONDS)
            client.send(replacement, "ok")
        }
    }

    @Test
    fun `cleanup failure does not wedge lifecycle or suppress the closure notification`() {
        FakeClient().use { client ->
            val first = client.start()
            client.resources.single().failRelease = true
            client.resources.single().failWrite = true
            assertFailsWith<IOException> { client.send(first, "fail") }
            assertEquals(listOf(first), client.closedSessions)
            assertNotNull(client.start())
        }
    }

    @Test
    fun `reader submission failure releases the unpublished resource without closure notification`() {
        FakeClient(TrackingExecutor(rejectAt = 2)).use { client ->
            assertNull(client.start({ _, _ -> }) { client.closedSessions += it })
            client.readerTasks.tasks.first().get(5, TimeUnit.SECONDS)
            assertEquals(1, client.resources.single().releases.get())
            assertTrue(client.closedSessions.isEmpty())
        }
    }

    @Test
    fun `EOF and stop racing have one cleanup owner`() {
        FakeClient().use { client ->
            client.start()
            val resource = client.resources.single()
            resource.readExitGate = CountDownLatch(1)
            resource.input.put(EOF)
            val stop = CompletableFuture.runAsync { client.stop() }
            resource.readExitGate.countDown()
            stop.get(5, TimeUnit.SECONDS)
            client.readerTasks.tasks.first().get(5, TimeUnit.SECONDS)
            assertEquals(1, resource.releases.get())
            assertTrue(client.closedSessions.size <= 1)
            assertNotNull(client.start())
        }
    }

    @Test
    fun `message callback failure does not close the execution`() {
        FakeClient().use { client ->
            val seen = CountDownLatch(2)
            val session = assertNotNull(client.start({ _, _ ->
                seen.countDown()
                throw IllegalArgumentException("handler failed")
            }, { client.closedSessions += it }))
            client.resources.single().input.put("first")
            client.resources.single().input.put("second")
            seen.awaitChecked()
            client.send(session, "still open")
            assertTrue(client.closedSessions.isEmpty())
        }
    }

    private class FakeClient(val readerTasks: TrackingExecutor = TrackingExecutor()) :
        AbstractCodexRpcClient<Resource>(readerTasks) {
        val resources = CopyOnWriteArrayList<Resource>()
        val closedSessions = CopyOnWriteArrayList<RpcSession>()
        val closedSignal = CountDownLatch(1)
        val openEntered = CountDownLatch(1)
        var openGate = CountDownLatch(0)
        var failOpen = false

        fun start(): RpcSession = assertNotNull(start({ _, _ -> }) {
            closedSessions += it
            closedSignal.countDown()
        })

        override fun openResource(): Resource {
            openEntered.countDown()
            openGate.awaitChecked()
            if (failOpen) throw IOException("open failed")
            return Resource().also { resources += it }
        }

        override fun isResourceAlive(resource: Resource): Boolean = resource.alive

        override fun writeMessage(resource: Resource, message: String) {
            resource.writeEntered.countDown()
            resource.writeGate.awaitChecked()
            if (!resource.alive || resource.failWrite) throw IOException("write failed")
            resource.writes += message
        }

        override fun readMessages(resource: Resource, emit: (String) -> Unit) {
            while (true) {
                val line = resource.input.take()
                if (line == EOF) break
                emit(line)
            }
            resource.readExitGate.awaitChecked()
        }

        override fun readDiagnostics(resource: Resource) = Unit

        override fun releaseResource(resource: Resource) {
            resource.releases.incrementAndGet()
            resource.alive = false
            resource.input.put(EOF)
            if (resource.failRelease) throw IOException("release failed")
        }
    }

    private class Resource {
        @Volatile var alive = true
        @Volatile var failWrite = false
        @Volatile var failRelease = false
        @Volatile var writeGate = CountDownLatch(0)
        @Volatile var readExitGate = CountDownLatch(0)
        val writeEntered = CountDownLatch(1)
        val input = LinkedBlockingQueue<String>()
        val writes = CopyOnWriteArrayList<String>()
        val releases = AtomicInteger()
    }

    private class TrackingExecutor(private val rejectAt: Int = -1) : ThreadPoolExecutor(
        0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "rpc-test-reader").apply { isDaemon = true } },
    ) {
        val tasks = CopyOnWriteArrayList<Future<*>>()
        private val submitted = AtomicInteger()

        override fun submit(task: Runnable): Future<*> {
            if (submitted.incrementAndGet() == rejectAt) throw RejectedExecutionException("test rejection")
            return super.submit(task).also { tasks += it }
        }
    }

    private companion object {
        const val EOF = "<EOF>"
        fun CountDownLatch.awaitChecked() = check(await(5, TimeUnit.SECONDS)) { "Latch timed out" }
    }
}
