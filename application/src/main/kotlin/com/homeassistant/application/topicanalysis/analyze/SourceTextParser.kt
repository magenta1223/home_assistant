package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.source.SourceRecordDraft

fun interface SourceTextParser {
    fun parse(sourceName: String, text: String): List<SourceRecordDraft>
}
