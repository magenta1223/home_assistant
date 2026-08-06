package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.source.ParsedSource

fun interface SourceTextParser {
    fun parse(sourceName: String, text: String): ParsedSource
}
