package com.homeassistant.domain.kakao

import com.homeassistant.datamodel.kakao.KakaoMessage

/** Imports a KakaoTalk export file into the Kakao message store. */
class KakaoImportService(private val repository: KakaoMessageStore) {
    fun import(sourceFileName: String, text: String): KakaoImportResult {
        val parsed = KakaoMessageParser.parse(sourceFileName, text)
        val imported = repository.importMessages(parsed)
        val messages = repository.listMessages(sourceFileName)
        return KakaoImportResult(imported.size, messages)
    }
}

/**
 * Result of importing a KakaoTalk export file.
 *
 * @property importedMessageCount Number of newly imported messages.
 * @property messages Stored messages for the source file after import.
 */
data class KakaoImportResult(
    val importedMessageCount: Int,
    val messages: List<KakaoMessage>,
)
