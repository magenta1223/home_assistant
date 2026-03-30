package com.homeassistant.nlp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.PrintWriter

private val log = LoggerFactory.getLogger(ClaudeCliBackend::class.java)

class ClaudeCliBackend : LlmBackend {

    private val mutex = Mutex()

    @Volatile private var process: Process? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var reader: BufferedReader? = null

    private fun startProcess() {
        process?.destroyForcibly()
        val p = ProcessBuilder("claude", "--output-format", "json")
            .redirectErrorStream(false)
            .start()
        process = p
        writer = PrintWriter(p.outputStream.bufferedWriter(), true)
        reader = p.inputStream.bufferedReader()
        log.info("ClaudeCliBackend: session started PID=${p.pid()}")
    }

    private fun ensureAlive() {
        if (process?.isAlive != true) startProcess()
    }

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double?,
    ): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAlive()
            val start = System.currentTimeMillis()
            log.info("ClaudeCliBackend call messages=${messages.size}")
            try {
                val result = sendAndReceive(system, messages)
                log.info("ClaudeCliBackend response ${System.currentTimeMillis() - start}ms chars=${result?.length}")
                result
            } catch (e: Exception) {
                log.warn("ClaudeCliBackend error: ${e.message}, restarting session")
                startProcess()
                null
            }
        }
    }

    private fun sendAndReceive(system: String, messages: List<Pair<String, String>>): String? {
        val w = writer ?: return null
        val r = reader ?: return null

        val prompt = buildPrompt(system, messages)
        w.println("/clear")
        w.println(prompt)

        return readLastResult(r)
    }

    private fun readLastResult(r: BufferedReader): String? {
        var lastResult: String? = null
        val deadline = System.currentTimeMillis() + 60_000

        while (System.currentTimeMillis() < deadline) {
            val line = r.readLine() ?: run {
                log.warn("ClaudeCliBackend: stream closed unexpectedly")
                return lastResult
            }
            if (line.contains(""""type":"result"""")) {
                lastResult = parseResult(line)
                Thread.sleep(150)
                if (!r.ready()) return lastResult
            }
        }

        log.warn("ClaudeCliBackend: timeout waiting for result")
        return lastResult
    }

    private fun buildPrompt(system: String, messages: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append("[SYSTEM] ${system.toSingleLine()} [CONVERSATION] ")
        messages.forEachIndexed { i, (role, content) ->
            if (i > 0) sb.append(" | ")
            sb.append("$role: ${content.toSingleLine()}")
        }
        sb.append(" [END] Respond concisely. Do not use tools or read files.")
        return sb.toString()
    }

    private fun String.toSingleLine() = replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    private fun parseResult(json: String): String? =
        Regex(""""result"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
}
