package com.homeassistant.commands

import com.homeassistant.context.ContextRetriever
import com.homeassistant.models.CommandResult
import com.homeassistant.nlp.ClaudeClient
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate

private val log = LoggerFactory.getLogger(CommandExecutor::class.java)

private val QTY_REGEX = Regex("""^(.+?)\s+(\d+(?:\.\d+)?)\s*(개|L|리터|g|kg|봉|팩|병|캔|줄|판|묶음|포)$""")

class CommandExecutor(
    private val claudeClient: ClaudeClient,
    private val contextRetriever: ContextRetriever? = null,
) {

    suspend fun execute(command: String, params: String, userId: String): CommandResult =
        when (command) {

            // ── Todos ──────────────────────────────────────────────────────

            "/할일" -> {
                if (params.isBlank()) return CommandResult("할 일 내용을 입력해주세요.")
                val isShared = params.startsWith("공유 ")
                val content = if (isShared) params.drop(3).trim() else params
                val id = transaction {
                    execInsert(
                        "INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)",
                        listOf(userId, if (isShared) 1 else 0, content)
                    )
                }
                contextRetriever?.storeEmbedding("todos", id, content)
                CommandResult(if (isShared) "공유 할 일 추가: *$content*" else "할 일 추가: *$content*")
            }

            "/할일목록" -> {
                val rows = transaction {
                    when (params) {
                        "공유" -> execQuery(
                            "SELECT * FROM todos WHERE is_shared = 1 AND is_done = 0 ORDER BY created_at DESC",
                            emptyList()
                        )
                        "완료" -> execQuery(
                            "SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 1 ORDER BY done_at DESC LIMIT 20",
                            listOf(userId)
                        )
                        else -> execQuery(
                            "SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 ORDER BY created_at DESC",
                            listOf(userId)
                        )
                    }
                }
                val label = when (params) {
                    "완료" -> "완료된 할 일"
                    "공유" -> "공유 할 일"
                    else -> "할 일 목록"
                }
                CommandResult(formatList(label, rows) { r ->
                    "${r["content"]}${if ((r["is_shared"] as? Int) == 1) " _(공유)_" else ""}"
                })
            }

            "/완료" -> {
                if (params.isBlank()) return CommandResult("완료할 할 일을 입력해주세요.")
                val id = transaction {
                    execQuery(
                        "SELECT id FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 AND content LIKE ? LIMIT 1",
                        listOf(userId, "%$params%")
                    ).firstOrNull()?.get("id") as? Int
                } ?: return CommandResult("\"$params\"에 해당하는 미완료 할 일을 찾지 못했어요.")
                transaction {
                    execUpdate(
                        "UPDATE todos SET is_done = 1, done_at = datetime('now','localtime') WHERE id = ?",
                        listOf(id)
                    )
                }
                CommandResult("완료 처리했어요!")
            }

            // ── Memos ──────────────────────────────────────────────────────

            "/메모" -> {
                if (params.isBlank()) return CommandResult("메모 내용을 입력해주세요.")
                val isShared = params.startsWith("공유 ")
                val content = if (isShared) params.drop(3).trim() else params
                transaction {
                    execInsert(
                        "INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)",
                        listOf(userId, if (isShared) 1 else 0, content)
                    )
                }
                CommandResult(if (isShared) "공유 메모 저장했어요: _${content}_" else "메모 저장했어요!")
            }

            "/메모목록" -> {
                val rows = transaction {
                    if (params == "공유")
                        execQuery("SELECT * FROM memos WHERE is_shared = 1 ORDER BY created_at DESC LIMIT 15", emptyList())
                    else
                        execQuery("SELECT * FROM memos WHERE (user_id = ? OR is_shared = 1) ORDER BY created_at DESC LIMIT 15", listOf(userId))
                }
                val label = if (params == "공유") "공유 메모" else "내 메모"
                CommandResult(formatList(label, rows) { r ->
                    val shared = if ((r["is_shared"] as? Int) == 1) " _(공유)_" else ""
                    val title = r["title"]?.let { "*$it*\n" } ?: ""
                    "$title${r["content"]}$shared\n_${r["created_at"]}_"
                })
            }

            "/메모검색" -> {
                if (params.isBlank()) return CommandResult("검색어를 입력해주세요.")
                val rows = transaction {
                    execQuery(
                        "SELECT * FROM memos WHERE (user_id = ? OR is_shared = 1) AND (title LIKE ? OR content LIKE ? OR tags LIKE ?) ORDER BY created_at DESC LIMIT 10",
                        listOf(userId, "%$params%", "%$params%", "%$params%")
                    )
                }
                CommandResult(formatList("\"$params\" 검색 결과", rows) { r ->
                    val shared = if ((r["is_shared"] as? Int) == 1) " _(공유)_" else ""
                    "${r["content"]}$shared\n_${r["created_at"]}_"
                })
            }

            // ── Schedules ──────────────────────────────────────────────────

            "/일정" -> {
                if (params.isBlank()) return CommandResult("일정을 입력해주세요. 예: \"내일 오후 3시 치과\"")
                val isShared = params.startsWith("공유 ")
                val content = if (isShared) params.drop(3).trim() else params
                val eventDate = claudeClient.parseDate(content)
                    ?: return CommandResult("날짜/시간 정보를 찾지 못했어요. 날짜를 포함해서 입력해주세요.")
                transaction {
                    execInsert(
                        "INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)",
                        listOf(userId, if (isShared) 1 else 0, content, eventDate)
                    )
                }
                CommandResult(
                    if (isShared) "공유 일정 등록: *$content* ($eventDate)"
                    else "일정 등록: *$content* ($eventDate)"
                )
            }

            "/일정목록" -> {
                val (from, to) = if (params.isNotBlank()) {
                    claudeClient.parseDateRange(params)
                } else {
                    val today = LocalDate.now()
                    Pair(today.toString(), today.plusDays(30).toString())
                }
                val rows = transaction {
                    execQuery(
                        "SELECT * FROM schedules WHERE (user_id = ? OR is_shared = 1) AND date(event_date) BETWEEN ? AND ? ORDER BY event_date ASC",
                        listOf(userId, from ?: LocalDate.now().toString(), to ?: LocalDate.now().plusDays(30).toString())
                    )
                }
                val label = if (params.isNotBlank()) "일정 ($params)" else "일정 (다음 30일)"
                CommandResult(formatList(label, rows) { r ->
                    val shared = if ((r["is_shared"] as? Int) == 1) " _(공유)_" else ""
                    "*${r["title"]}*$shared\n_${r["event_date"]}_"
                })
            }

            // ── Home Status ────────────────────────────────────────────────

            "/상태" -> {
                if (params.isBlank()) return CommandResult("기기명과 상태를 입력해주세요. 예: \"에어컨 켜\"")
                val parts = params.trim().split(Regex("\\s+"))
                if (parts.size < 2) return CommandResult("상태도 함께 입력해주세요. 예: \"에어컨 켜\"")
                val device = parts[0]
                val status = parts.drop(1).joinToString(" ")
                transaction {
                    execUpdate(
                        """INSERT INTO home_status (device_name, status, set_by)
                           VALUES (?, ?, ?)
                           ON CONFLICT(device_name) DO UPDATE SET
                             status = excluded.status,
                             set_by = excluded.set_by,
                             updated_at = datetime('now','localtime')""",
                        listOf(device, status, userId)
                    )
                }
                CommandResult("*$device* 상태를 *$status*(으)로 업데이트했어요.")
            }

            "/상태확인" -> {
                if (params.isBlank()) {
                    val rows = transaction {
                        execQuery("SELECT * FROM home_status ORDER BY device_name", emptyList())
                    }
                    CommandResult(formatList("집 전체 상태", rows) { r ->
                        "*${r["device_name"]}*: ${r["status"]}  _(${r["updated_at"]})_"
                    })
                } else {
                    val row = transaction {
                        execQuery("SELECT * FROM home_status WHERE device_name = ?", listOf(params)).firstOrNull()
                    } ?: return CommandResult("*$params* 상태 정보가 없어요.")
                    CommandResult("*${row["device_name"]}*: ${row["status"]}  _(${row["updated_at"]} 기준)_")
                }
            }

            // ── Item Locations ─────────────────────────────────────────────

            "/위치저장" -> {
                if (params.isBlank()) return CommandResult("물건명과 위치를 입력해주세요. 예: \"리모컨 소파 옆\"")
                val parts = params.trim().split(Regex("\\s+"))
                if (parts.size < 2) return CommandResult("위치도 함께 입력해주세요.")
                val item = parts[0]
                val location = parts.drop(1).joinToString(" ")
                transaction {
                    execUpdate(
                        """INSERT INTO item_locations (item_name, location, set_by)
                           VALUES (?, ?, ?)
                           ON CONFLICT(item_name) DO UPDATE SET
                             location = excluded.location,
                             set_by = excluded.set_by,
                             updated_at = datetime('now','localtime')""",
                        listOf(item, location, userId)
                    )
                }
                CommandResult("*$item* 위치 저장: $location")
            }

            "/위치" -> {
                if (params.isBlank()) return CommandResult("물건명을 입력해주세요. 예: \"리모컨\"")
                val row = transaction {
                    execQuery("SELECT * FROM item_locations WHERE item_name = ?", listOf(params)).firstOrNull()
                } ?: return CommandResult("*$params* 위치 정보가 없어요.")
                CommandResult("*${row["item_name"]}*: ${row["location"]}  _(${row["updated_at"]} 기준)_")
            }

            // ── Assets ─────────────────────────────────────────────────────

            "/자산" -> {
                if (params.isBlank()) return CommandResult("카테고리와 금액을 입력해주세요. 예: \"현금 500000\"")
                val parts = params.split(Regex("\\s+"))
                if (parts.size < 2) return CommandResult("금액도 함께 입력해주세요.")
                val category = parts[0]
                val amount = parts[1].replace(",", "").toDoubleOrNull()
                    ?: return CommandResult("금액은 숫자로 입력해주세요.")
                val note = if (parts.size > 2) parts.drop(2).joinToString(" ") else null
                transaction {
                    execInsert(
                        "INSERT INTO assets (user_id, category, amount, note) VALUES (?, ?, ?, ?)",
                        listOf(userId, category, amount, note)
                    )
                }
                val formatted = "%,.0f".format(amount)
                CommandResult("*$category* ${formatted}원 기록했어요.")
            }

            "/자산확인" -> {
                val rows = transaction {
                    execQuery(
                        """SELECT a.category, a.amount, a.recorded_at
                           FROM assets a
                           INNER JOIN (
                               SELECT category, MAX(id) as max_id
                               FROM assets WHERE user_id = ?
                               GROUP BY category
                           ) latest ON a.id = latest.max_id
                           WHERE a.user_id = ?
                           ORDER BY a.category""",
                        listOf(userId, userId)
                    )
                }
                if (rows.isEmpty()) return CommandResult("기록된 자산이 없어요.")
                val total = rows.sumOf { (it["amount"] as? Double) ?: 0.0 }
                val lines = rows.joinToString("\n") { r ->
                    val amt = (r["amount"] as? Double) ?: 0.0
                    "*${r["category"]}*: ${"%.0f".format(amt)}원"
                }
                CommandResult("*자산 현황*\n$lines\n---\n*합계: ${"%.0f".format(total)}원*")
            }

            "/자산내역" -> {
                val rows = transaction {
                    if (params.isNotBlank())
                        execQuery("SELECT * FROM assets WHERE user_id = ? AND category = ? ORDER BY recorded_at DESC LIMIT 20", listOf(userId, params))
                    else
                        execQuery("SELECT * FROM assets WHERE user_id = ? ORDER BY recorded_at DESC LIMIT 20", listOf(userId))
                }
                val label = if (params.isNotBlank()) "$params 내역" else "자산 내역"
                CommandResult(formatList(label, rows) { r ->
                    val amt = (r["amount"] as? Double) ?: 0.0
                    val note = r["note"]?.let { " ($it)" } ?: ""
                    "*${r["category"]}*: ${"%.0f".format(amt)}원$note\n_${r["recorded_at"]}_"
                })
            }

            // ── Recipes ────────────────────────────────────────────────────

            "/레시피저장" -> {
                if (params.isBlank()) return CommandResult("레시피를 입력해주세요.")
                val lines = params.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val name = lines.firstOrNull() ?: ""
                val body = lines.drop(1).joinToString("\n")
                val ingredientsMatch = Regex("재료[：:]?\\s*([\\s\\S]*?)(?=순서[：:]|조리법[：:]|\$)", RegexOption.IGNORE_CASE).find(body)
                val stepsMatch = Regex("(?:순서|조리법)[：:]?\\s*([\\s\\S]*)", RegexOption.IGNORE_CASE).find(body)
                val ingredients = ingredientsMatch?.groupValues?.get(1)?.trim() ?: body
                val steps = stepsMatch?.groupValues?.get(1)?.trim() ?: ""
                transaction {
                    execInsert(
                        "INSERT INTO recipes (user_id, name, ingredients, steps) VALUES (?, ?, ?, ?)",
                        listOf(userId, name, ingredients, steps)
                    )
                }
                CommandResult("레시피 *$name* 저장했어요!")
            }

            "/레시피" -> {
                if (params.isBlank()) return CommandResult("레시피 이름을 입력해주세요.")
                val row = transaction {
                    execQuery("SELECT * FROM recipes WHERE name LIKE ? ORDER BY created_at DESC LIMIT 1", listOf("%$params%")).firstOrNull()
                } ?: return CommandResult("*$params* 레시피를 찾지 못했어요.")
                val ingredients = row["ingredients"]
                val steps = row["steps"]
                val stepsText = if (steps != null && steps.toString().isNotBlank()) "\n\n*조리 순서*\n$steps" else ""
                CommandResult("*${row["name"]}*\n\n*재료*\n$ingredients$stepsText")
            }

            "/레시피목록" -> {
                val rows = transaction {
                    execQuery("SELECT id, name, created_at FROM recipes ORDER BY created_at DESC LIMIT 20", emptyList())
                }
                if (rows.isEmpty()) return CommandResult("저장된 레시피가 없어요.")
                val list = rows.mapIndexed { i, r -> "${i + 1}. *${r["name"]}*" }.joinToString("\n")
                CommandResult("*레시피 목록*\n$list")
            }

            // ── Grocery ────────────────────────────────────────────────────

            "/구매" -> {
                val m = QTY_REGEX.matchEntire(params.trim())
                    ?: return CommandResult("형식: \"달걀 10개\" (수량+단위 필수)")
                val itemName = m.groupValues[1].trim()
                val qty = m.groupValues[2].toDouble()
                val unit = m.groupValues[3]

                transaction {
                    // Upsert grocery_items
                    val existingId = execQuery(
                        "SELECT id FROM grocery_items WHERE name = ?", listOf(itemName)
                    ).firstOrNull()?.get("id") as? Int

                    val itemId = existingId ?: run {
                        execInsert("INSERT INTO grocery_items (name, unit) VALUES (?, ?)", listOf(itemName, unit)).toInt()
                    }
                    execInsert("INSERT INTO grocery_purchases (item_id, qty) VALUES (?, ?)", listOf(itemId, qty))
                }
                CommandResult("*$itemName* ${qty.toLong()}$unit 구매 기록 완료")
            }

            "/재고" -> {
                val items = transaction {
                    execQuery("SELECT * FROM grocery_items ORDER BY name", emptyList())
                }
                if (items.isEmpty()) return CommandResult("기록된 식재료가 없어요. 구매 기록부터 추가해보세요.")

                val shortage = mutableListOf<String>()
                val imminent = mutableListOf<String>()
                val ok = mutableListOf<String>()
                val insufficient = mutableListOf<String>()

                items.forEach { item ->
                    val itemId = item["id"] as? Int ?: return@forEach
                    val itemName = item["name"] as? String ?: return@forEach
                    val purchases = transaction {
                        execQuery(
                            "SELECT purchased_at FROM grocery_purchases WHERE item_id = ? ORDER BY purchased_at ASC",
                            listOf(itemId)
                        )
                    }
                    if (purchases.size < 2) {
                        insufficient.add("📊 *$itemName*: 구매 이력 부족 (${purchases.size}회) — 예측 불가")
                        return@forEach
                    }
                    val dates = purchases.mapNotNull {
                        try {
                            java.time.LocalDateTime.parse(
                                (it["purchased_at"] as? String)?.replace(" ", "T") ?: ""
                            ).toEpochSecond(java.time.ZoneOffset.UTC) * 1000L
                        } catch (_: Exception) { null }
                    }
                    var totalInterval = 0.0
                    for (i in 1 until dates.size) {
                        totalInterval += (dates[i] - dates[i - 1]) / (1000.0 * 60 * 60 * 24)
                    }
                    val avgInterval = totalInterval / (dates.size - 1)
                    val daysSinceLast = (System.currentTimeMillis() - dates.last()) / (1000.0 * 60 * 60 * 24)
                    val daysRemaining = Math.round(avgInterval - daysSinceLast)
                    when {
                        daysRemaining <= 0 -> shortage.add("⚠️ *$itemName*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)")
                        daysRemaining <= 3 -> imminent.add("🔔 *$itemName*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)")
                        else -> ok.add("✅ *$itemName*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)")
                    }
                }

                val sb = StringBuilder("*재고 현황*\n")
                if (shortage.isNotEmpty()) sb.append("\n*부족 예상*\n${shortage.joinToString("\n")}\n")
                if (imminent.isNotEmpty()) sb.append("\n*구매 임박*\n${imminent.joinToString("\n")}\n")
                if (ok.isNotEmpty()) sb.append("\n*여유 있음*\n${ok.joinToString("\n")}\n")
                if (insufficient.isNotEmpty()) sb.append("\n*데이터 부족*\n${insufficient.joinToString("\n")}")
                CommandResult(sb.toString())
            }

            else -> CommandResult("해당 명령어를 처리할 수 없어요.")
        }

    // ── Raw SQL helpers (called within transaction{}) ──────────────────────

    private fun execQuery(sql: String, params: List<Any?>): List<Map<String, Any?>> {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val results = mutableListOf<Map<String, Any?>>()
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
            val rs = stmt.executeQuery()
            val meta = rs.metaData
            while (rs.next()) {
                val row = mutableMapOf<String, Any?>()
                for (col in 1..meta.columnCount) {
                    row[meta.getColumnName(col)] = rs.getObject(col)
                }
                results.add(row)
            }
        }
        return results
    }

    private fun execInsert(sql: String, params: List<Any?>): Long {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
            params.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
            stmt.executeUpdate()
            val rs = stmt.generatedKeys
            return if (rs.next()) rs.getLong(1) else -1L
        }
    }

    private fun execUpdate(sql: String, params: List<Any?>) {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
            stmt.executeUpdate()
        }
    }

    // ── Formatting helper ──────────────────────────────────────────────────

    private fun formatList(
        label: String,
        rows: List<Map<String, Any?>>,
        formatter: (Map<String, Any?>) -> String,
    ): String {
        if (rows.isEmpty()) return "${label}: 항목이 없어요."
        val items = rows.joinToString("\n\n") { "• ${formatter(it)}" }
        return "*$label*\n\n$items"
    }
}
