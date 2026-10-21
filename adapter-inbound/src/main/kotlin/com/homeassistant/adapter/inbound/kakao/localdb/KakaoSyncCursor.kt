package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.common.json.JsonSerializer
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The minute of the last synced message per chat, as ISO instants in a file owned by this adapter. The next round
 * reads from that minute again, so messages that arrive later in the same minute are not missed; source record
 * deduplication makes the re-read messages harmless, as it does losing the file.
 */
class KakaoSyncCursor(private val path: Path) {
    private val positions: MutableMap<String, String> = load()

    fun from(chatKey: String): Instant = positions[chatKey]?.let(Instant::parse) ?: START

    fun advance(chatKey: String, minute: Instant) {
        positions[chatKey] = minute.truncatedTo(ChronoUnit.MINUTES).toString()
        save()
    }

    private fun load(): MutableMap<String, String> {
        if (!Files.exists(path)) return mutableMapOf()
        return JsonSerializer.json.decodeFromString<Map<String, String>>(Files.readString(path)).toMutableMap()
    }

    /** Writes a sibling file first so a crash never leaves a half-written cursor. */
    private fun save() {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        val pending = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(pending, JsonSerializer.json.encodeToString(positions.toMap()))
        Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** Reads every message. */
        val START: Instant = Instant.EPOCH
    }
}
