package com.homeassistant.domain.kakao

/** Imports a KakaoTalk export file into the Kakao message store. */
class KakaoImportService(private val repository: KakaoMessageRepository) {
    fun import(sourceFileName: KakaoSourceFileName, text: KakaoExportText): KakaoImportResult {
        val parsed = KakaoMessageParser.parse(sourceFileName, text)
        val imported = repository.importMessages(parsed)
        val messages = repository.listMessages(sourceFileName)
        return KakaoImportResult(ImportedMessageCount(imported.size), messages)
    }
}

/** Result of importing a KakaoTalk export file. */
data class KakaoImportResult(
    val importedMessageCount: ImportedMessageCount,
    val messages: List<KakaoImportedMessage>,
)
