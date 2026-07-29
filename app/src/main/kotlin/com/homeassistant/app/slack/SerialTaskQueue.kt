package com.homeassistant.app.slack

import java.util.ArrayDeque
import java.util.concurrent.Executor

internal interface SerialTaskQueue {
    fun execute(task: () -> Unit)
}

internal object SerialTaskQueueFactory {
    fun create(executor: Executor): SerialTaskQueue =
        ExecutorSerialTaskQueue(executor)
}

private class ExecutorSerialTaskQueue(
    private val executor: Executor,
) : SerialTaskQueue {
    private val tasks = ArrayDeque<Runnable>()
    private var running = false

    override fun execute(task: () -> Unit) {
        val shouldSchedule = synchronized(this) {
            tasks.addLast(Runnable(task))
            if (running) false else {
                running = true
                true
            }
        }
        if (shouldSchedule) scheduleNext()
    }

    private fun scheduleNext() {
        val task = synchronized(this) { tasks.pollFirst() }
        if (task == null) {
            synchronized(this) { running = false }
            return
        }
        executor.execute {
            try {
                task.run()
            } finally {
                scheduleNext()
            }
        }
    }
}
