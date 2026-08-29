package com.homeassistant.domain.source

data class SourceDocumentDraft(
    val source: SourceDescriptor,
    val records: List<SourceRecordDraft>,
    val reference: SourceReferenceDraft? = null,
)
