package com.homeassistant.adapter.inbound.slack

import java.util.ArrayDeque
import java.util.concurrent.Executor

internal class SerialTaskQueue(
    private val executor: Executor,
) {
    private val tasks = ArrayDeque<Runnable>()
    private var running = false

    fun execute(task: () -> Unit) {
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
