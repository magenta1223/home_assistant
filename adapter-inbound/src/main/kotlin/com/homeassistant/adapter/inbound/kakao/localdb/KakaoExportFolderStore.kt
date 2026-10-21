package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Reads `<chatKey>.txt` that the external exporter saves into [exportDir] with KakaoTalk's own chat export.
 * The exporter replaces the file atomically, so every read sees one complete export of the room.
 */
class KakaoExportFolderStore(private val exportDir: Path) : KakaoLocalStore {
    /** A chat that has not been exported yet has no messages. A message without a date cannot be placed and is skipped. */
    override fun messagesFrom(chatKey: String, from: Instant, limit: Int): List<KakaoLocalMessage> {
        val file = exportDir.resolve("$chatKey.txt")
        if (!Files.exists(file)) return emptyList()
        return KakaoExportParser.messages(Files.readString(file))
            .filterNot { isAttachment(it.text.trim()) }
            .mapNotNull { message ->
                val sentAt = message.sentAt ?: return@mapNotNull null
                KakaoLocalMessage(message.sender, message.text, sentAt.atZone(KakaoLocalDbSync.KAKAO_ZONE).toInstant())
            }
            .filter { it.sentAt >= from }
            .takeWholeMinutes(limit)
    }

    /** Only text is collected: a body KakaoTalk writes in place of an attachment is left out. */
    private fun isAttachment(body: String): Boolean = body in MEDIA_PLACEHOLDERS || body.startsWith(FILE_PREFIX)

    private companion object {
        /** Bodies KakaoTalk writes in place of a photo, video or emoticon. A message with other text is kept. */
        val MEDIA_PLACEHOLDERS = setOf("사진", "동영상", "이모티콘")

        /** KakaoTalk writes a shared file as `파일: <file name>`. */
        const val FILE_PREFIX = "파일: "
    }
}
