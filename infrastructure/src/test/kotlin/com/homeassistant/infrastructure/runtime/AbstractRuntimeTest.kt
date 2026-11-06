package com.homeassistant.infrastructure.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AbstractRuntimeTest {
    private class RecordingRuntime(
        private val startAction: RecordingRuntime.() -> Unit = {},
        private val stopAction: () -> Unit = {},
    ) : AbstractRuntime() {
        val calls = mutableListOf<String>()

        override fun onStart() {
            calls += "start"
            startAction()
        }

        override fun onStop() {
            calls += "stop"
            stopAction()
        }

        fun die(cause: Throwable) = fail(cause)
    }

    @Test
    fun `a started runtime is running and a stopped one is terminated`() {
        val runtime = RecordingRuntime()

        runtime.start()
        assertEquals(RuntimeState.RUNNING, runtime.state)

        runtime.stop()
        assertEquals(RuntimeState.TERMINATED, runtime.state)
        assertEquals(listOf("start", "stop"), runtime.calls)
    }

    @Test
    fun `a runtime that failed to start is failed and is not stopped`() {
        val cause = IllegalStateException("boom")
        val runtime = RecordingRuntime(startAction = { throw cause })

        assertSame(cause, assertFailsWith<IllegalStateException> { runtime.start() })
        assertEquals(RuntimeState.FAILED, runtime.state)

        runtime.stop()
        assertEquals(listOf("start"), runtime.calls)
    }

    @Test
    fun `a runtime that failed to stop is failed`() {
        val runtime = RecordingRuntime(stopAction = { error("boom") })
        runtime.start()

        assertFailsWith<IllegalStateException> { runtime.stop() }
        assertEquals(RuntimeState.FAILED, runtime.state)
    }

    @Test
    fun `a runtime stopped before starting terminates without opening anything`() {
        val runtime = RecordingRuntime()

        runtime.stop()

        assertEquals(RuntimeState.TERMINATED, runtime.state)
        assertEquals(emptyList(), runtime.calls)
    }

    @Test
    fun `a runtime starts only once`() {
        val runtime = RecordingRuntime()
        runtime.start()
        runtime.stop()

        assertFailsWith<IllegalStateException> { runtime.start() }
        assertEquals(listOf("start", "stop"), runtime.calls)
    }

    @Test
    fun `stopping again does nothing`() {
        val runtime = RecordingRuntime()
        runtime.start()
        runtime.stop()

        runtime.stop()

        assertEquals(listOf("start", "stop"), runtime.calls)
    }

    @Test
    fun `a resource that dies while running fails the runtime and stop does nothing`() {
        val runtime = RecordingRuntime()
        runtime.start()

        runtime.die(IllegalStateException("exited"))
        runtime.stop()

        assertEquals(RuntimeState.FAILED, runtime.state)
        assertEquals(listOf("start"), runtime.calls)
    }

    @Test
    fun `a resource that dies while starting fails the start with its cause`() {
        val cause = IllegalStateException("exited")
        val runtime = RecordingRuntime(startAction = { die(cause) })

        val failure = assertFailsWith<IllegalStateException> { runtime.start() }

        assertSame(cause, failure.cause)
        assertEquals(RuntimeState.FAILED, runtime.state)
    }

    @Test
    fun `a stop during start waits until the runtime is running`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = RecordingRuntime(startAction = {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        val starter = thread { runtime.start() }
        started.await(5, TimeUnit.SECONDS)

        val stopper = thread { runtime.stop() }
        assertEquals(RuntimeState.STARTING, runtime.state)
        release.countDown()
        starter.join()
        stopper.join()

        assertEquals(RuntimeState.TERMINATED, runtime.state)
        assertEquals(listOf("start", "stop"), runtime.calls)
    }
}
