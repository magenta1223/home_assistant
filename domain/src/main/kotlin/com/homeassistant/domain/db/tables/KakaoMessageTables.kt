package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object KakaoImportedMessageTable : Table("kakao_imported_messages") {
    val id = integer("id").autoIncrement()
    val sourceFileName = text("source_file_name")
    val sender = text("sender")
    val displayTime = text("display_time")
    val content = text("content")
    val lineStart = integer("line_start")
    val lineEnd = integer("line_end")
    val fingerprint = text("fingerprint")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(fingerprint)
    }
}
