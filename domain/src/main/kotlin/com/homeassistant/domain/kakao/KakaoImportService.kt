package com.homeassistant.domain.kakao

class KakaoImportService(private val repository: KakaoMessageRepository) {
    fun import(sourceFileName: KakaoSourceFileName, text: KakaoExportText): KakaoImportResult {
        val parsed = KakaoMessageParser.parse(sourceFileName, text)
        val imported = repository.importMessages(parsed)
        val messages = repository.listMessages(sourceFileName)
        return KakaoImportResult(ImportedMessageCount(imported.size), messages)
    }
}

data class KakaoImportResult(
    val importedMessageCount: ImportedMessageCount,
    val messages: List<KakaoImportedMessage>,
)
