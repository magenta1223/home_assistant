package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.common.json.JsonSerializer
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The last synced `logId` per chat, kept in a file owned by this adapter. Losing it only costs a re-read:
 * source record deduplication makes re-sent messages harmless.
 */
class KakaoSyncCursor(private val path: Path) {
    private val positions: MutableMap<String, Long> = load()

    fun after(chatId: String): Long = positions[chatId] ?: START

    fun advance(chatId: String, logId: Long) {
        positions[chatId] = logId
        save()
    }

    private fun load(): MutableMap<String, Long> {
        if (!Files.exists(path)) return mutableMapOf()
        return JsonSerializer.json.decodeFromString<Map<String, Long>>(Files.readString(path)).toMutableMap()
    }

    /** Writes a sibling file first so a crash never leaves a half-written cursor. */
    private fun save() {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        val pending = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(pending, JsonSerializer.json.encodeToString(positions.toMap()))
        Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** Kakao `logId`s are positive, so the start position reads every message. */
        const val START = 0L
    }
}
