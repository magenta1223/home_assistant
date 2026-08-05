package com.homeassistant.domain.kakao

/** Imports a KakaoTalk export file into the Kakao message store. */
interface KakaoImporter {
    fun findNewMessages(messages: List<ParsedKakaoMessage>): List<ParsedKakaoMessage>
    fun import(sourceFileName: String, messages: List<ParsedKakaoMessage>): KakaoImportResult
}

internal class DefaultKakaoImporter(
    private val repository: KakaoMessageStore,
) : KakaoImporter {
    override fun findNewMessages(messages: List<ParsedKakaoMessage>): List<ParsedKakaoMessage> {
        val existingFingerprints = repository.findExistingFingerprints(
            messages.mapTo(mutableSetOf()) { it.fingerprint },
        )
        return messages
            .filterNot { it.fingerprint in existingFingerprints }
            .distinctBy { it.fingerprint }
    }

    override fun import(sourceFileName: String, messages: List<ParsedKakaoMessage>): KakaoImportResult {
        val imported = repository.importMessages(messages)
        val messages = repository.listMessages(sourceFileName)
        return KakaoImportResult(imported.size, messages)
    }
}

object KakaoImporterFactory {
    fun create(store: KakaoMessageStore): KakaoImporter =
        DefaultKakaoImporter(store)
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
