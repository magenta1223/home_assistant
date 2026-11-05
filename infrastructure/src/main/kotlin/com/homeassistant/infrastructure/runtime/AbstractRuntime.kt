package com.homeassistant.infrastructure.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class AbstractRuntime<I>(
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Runtime<I> {

    private val runtimeJob = SupervisorJob()

    protected val runtimeScope = CoroutineScope(
        runtimeJob + dispatcher
    )

    private val mutex = Mutex()

    private val _state =
        MutableStateFlow(RuntimeState.UNINITIALIZED)

    override val state: StateFlow<RuntimeState> =
        _state.asStateFlow()

    private val _failure =
        MutableStateFlow<Throwable?>(null)

    override val failure: StateFlow<Throwable?> =
        _failure.asStateFlow()

    private var startupJob: Deferred<Unit>? = null

    private var monitorJob: Job? = null
    private var terminationSignal: CompletableDeferred<Throwable>? = null

    // onStart가 호출되었는지 여부.
    // 일부 리소스만 생성된 상태에서도 onStop으로 정리할 수 있도록 관리한다.
    private var startAttempted = false

    // --------------------------------------------------
    // Lifecycle hooks
    // --------------------------------------------------

    protected abstract suspend fun onCreate(input: I)

    protected abstract suspend fun onStart()

    protected abstract suspend fun onAwaitReady()

    protected abstract suspend fun onStop()

    protected open suspend fun onDestroy() = Unit

    // Runtime monitoring
    protected abstract suspend fun awaitRuntimeTermination()

    // --------------------------------------------------
    // Create
    // --------------------------------------------------

    final override suspend fun create(input: I) {
        mutex.withLock {
            checkState(RuntimeState.UNINITIALIZED)

            try {
                onCreate(input)
                transitionTo(RuntimeState.INITIALIZED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
                throw e
            }
        }
    }

    // --------------------------------------------------
    // Start
    // --------------------------------------------------

    final override suspend fun start() {
        val job = mutex.withLock {
            checkState(RuntimeState.INITIALIZED)

            startAttempted = true
            _failure.value = null

            transitionTo(RuntimeState.STARTING)

            runtimeScope.async {
                try {
                    onStart()

                    // 실행 대상이 실제로 시작된 직후 감시 시작
                    val signal = startMonitoring()

                    if (signal != null) {
                        // 준비 완료와 비정상 종료를 동시에 감시
                        coroutineScope {
                            val readyJob = async {
                                onAwaitReady()
                            }

                            select<Unit> {
                                readyJob.onAwait {
                                    // 준비 완료
                                }

                                signal.onAwait { cause ->
                                    throw cause
                                }
                            }
                        }
                    } else {
                        throw CancellationException(
                            "Runtime startup was interrupted."
                        )
                    }

                    mutex.withLock {
                        if (_state.value == RuntimeState.STARTING) {
                            transitionTo(RuntimeState.RUNNING)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 준비 실패 등으로 startup이 끝나면 감시도 정리
                    stopMonitoring()

                    mutex.withLock {
                        if (_state.value == RuntimeState.STARTING) {
                            fail(e)
                        }
                    }

                    throw e
                }
            }.also {
                startupJob = it
            }
        }

        // Mutex를 잡지 않은 상태에서 준비 완료를 기다린다.
        job.await()
    }

    // --------------------------------------------------
    // Stop
    // --------------------------------------------------

    final override suspend fun stop() {
        val job = mutex.withLock {
            when (_state.value) {
                RuntimeState.INITIALIZED -> return

                RuntimeState.STARTING,
                RuntimeState.RUNNING,
                RuntimeState.FAILED -> {
                    transitionTo(RuntimeState.STOPPING)
                    startupJob
                }

                RuntimeState.UNINITIALIZED ->
                    error("Runtime has not been created.")

                RuntimeState.STOPPING ->
                    error("Runtime is already stopping.")

                RuntimeState.DESTROYED ->
                    error("Runtime has already been destroyed.")
            }
        }

        withContext(NonCancellable) {
            try {
                // 정상 종료 전에 감시 중단
                stopMonitoring()

                // STARTING 중이라면 준비 작업을 먼저 취소한다.
                job?.cancelAndJoin()

                if (startAttempted) {
                    onStop()
                }

                mutex.withLock {
                    startupJob = null
                    startAttempted = false
                    _failure.value = null
                    transitionTo(RuntimeState.INITIALIZED)
                }
            } catch (e: Throwable) {
                mutex.withLock {
                    fail(e)
                }
                throw e
            }
        }
    }

    // --------------------------------------------------
    // Destroy
    // --------------------------------------------------

    final override suspend fun destroy() {
        val (job, shouldStop) = mutex.withLock {
            when (_state.value) {
                RuntimeState.DESTROYED ->
                    return

                RuntimeState.STOPPING ->
                    error("Runtime is already stopping.")

                RuntimeState.UNINITIALIZED,
                RuntimeState.INITIALIZED,
                RuntimeState.STARTING,
                RuntimeState.RUNNING,
                RuntimeState.FAILED -> {
                    val shouldStop = startAttempted

                    transitionTo(RuntimeState.STOPPING)

                    startupJob to shouldStop
                }
            }
        }

        withContext(NonCancellable) {
            // 감시와 시작 작업 정리
            stopMonitoring()

            var cleanupFailure: Throwable? = null

            try {
                job?.cancelAndJoin()

                if (shouldStop) {
                    onStop()
                }
            } catch (e: Throwable) {
                cleanupFailure = e
            }

            try {
                onDestroy()
            } catch (e: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = e
                } else {
                    cleanupFailure.addSuppressed(e)
                }
            }

            mutex.withLock {
                startupJob = null
                startAttempted = false

                if (cleanupFailure != null) {
                    fail(cleanupFailure)
                } else {
                    _failure.value = null
                    transitionTo(RuntimeState.DESTROYED)
                }
            }

            runtimeJob.cancel()

            cleanupFailure?.let { throw it }
        }
    }

    // --------------------------------------------------
    // Runtime monitoring
    // --------------------------------------------------

    /**
     * Runtime 종료 감시를 시작한다.
     *
     * STARTING 상태에서만 감시를 시작한다.
     * 반환된 signal은 Runtime의 비정상 종료를 전달한다.
     */
    private suspend fun startMonitoring(): Deferred<Throwable>? {
        return mutex.withLock {
            if (_state.value != RuntimeState.STARTING) {
                return@withLock null
            }

            currentCoroutineContext().ensureActive()

            val signal = CompletableDeferred<Throwable>()

            terminationSignal = signal

            monitorJob = runtimeScope.launch {
                monitorRuntime(signal)
            }

            signal
        }
    }

    /**
     * 실행 대상의 종료를 감시한다.
     *
     * awaitRuntimeTermination()이 정상 반환되면
     * 예상치 못한 종료로 판단한다.
     */
    private suspend fun monitorRuntime(
        signal: CompletableDeferred<Throwable>
    ) {
        try {
            awaitRuntimeTermination()

            reportRuntimeFailure(
                IllegalStateException(
                    "Runtime terminated unexpectedly."
                ),
                signal
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportRuntimeFailure(e, signal)
        }
    }

    /**
     * 비정상 종료를 Runtime 상태에 반영한다.
     */
    private suspend fun reportRuntimeFailure(
        cause: Throwable,
        signal: CompletableDeferred<Throwable>
    ) {
        mutex.withLock {
            when (_state.value) {
                RuntimeState.STARTING,
                RuntimeState.RUNNING -> {
                    fail(cause)
                    signal.complete(cause)
                }

                else -> Unit
            }
        }
    }

    /**
     * 종료 감시 작업을 취소하고 정리한다.
     *
     * 호출 전에 상태가 STOPPING으로 전이되어 있어야 한다.
     */
    private suspend fun stopMonitoring() {
        val job = mutex.withLock {
            monitorJob.also {
                monitorJob = null
                terminationSignal = null
            }
        }

        job?.cancelAndJoin()
    }

    // --------------------------------------------------
    // Internal state management
    // --------------------------------------------------

    private fun checkState(expected: RuntimeState) {
        check(_state.value == expected) {
            "Invalid Runtime state. Expected=$expected, Actual=${_state.value}"
        }
    }

    private fun transitionTo(target: RuntimeState) {
        val current = _state.value

        check(target in allowedTransitions[current].orEmpty()) {
            "Invalid Runtime transition: $current -> $target"
        }

        _state.value = target
    }

    private fun fail(cause: Throwable) {
        _failure.value = cause

        if (_state.value != RuntimeState.FAILED) {
            transitionTo(RuntimeState.FAILED)
        }
    }

    private companion object {
        val allowedTransitions = mapOf(
            RuntimeState.UNINITIALIZED to setOf(
                RuntimeState.INITIALIZED,
                RuntimeState.FAILED,
                RuntimeState.STOPPING
            ),
            RuntimeState.INITIALIZED to setOf(
                RuntimeState.STARTING,
                RuntimeState.STOPPING,
                RuntimeState.DESTROYED
            ),
            RuntimeState.STARTING to setOf(
                RuntimeState.RUNNING,
                RuntimeState.STOPPING,
                RuntimeState.FAILED
            ),
            RuntimeState.RUNNING to setOf(
                RuntimeState.STOPPING,
                RuntimeState.FAILED
            ),
            RuntimeState.STOPPING to setOf(
                RuntimeState.INITIALIZED,
                RuntimeState.FAILED,
                RuntimeState.DESTROYED
            ),
            RuntimeState.FAILED to setOf(
                RuntimeState.STOPPING,
                RuntimeState.DESTROYED
            ),
            RuntimeState.DESTROYED to emptySet()
        )
    }
}