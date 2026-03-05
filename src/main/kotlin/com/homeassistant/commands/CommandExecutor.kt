package com.homeassistant.commands

import com.homeassistant.constants.AppConfig
import com.homeassistant.constants.InventoryStatus
import com.homeassistant.constants.Messages
import com.homeassistant.constants.SharedStatus
import com.homeassistant.constants.SlashCommand
import com.homeassistant.context.ContextRetriever
import com.homeassistant.models.CommandResult
import com.homeassistant.nlp.AiClient
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate

private val log = LoggerFactory.getLogger(CommandExecutor::class.java)



private val QTY_REGEX = Regex("""^(.+?)\s+(\d+(?:\.\d+)?)\s*(개|L|리터|g|kg|봉|팩|병|캔|줄|판|묶음|포)$""")

class CommandExecutor(
    private val claudeClient: AiClient,
    private val contextRetriever: ContextRetriever? = null,
) {

    suspend fun execute(command: String, params: String, userId: String): CommandResult {

        if (params.isBlank()) return CommandResult(Messages.Errors.BLANK_INPUT)

        return when (command) {

            // ── Todos ──────────────────────────────────────────────────────

            SlashCommand.HARILADO.value -> {
                val isShared = params.startsWith(SharedStatus.INPUT_PREFIX)
                val content = if (isShared) params.drop(SharedStatus.INPUT_PREFIX_LEN).trim() else params
                val id = transaction {
                    execInsert(
                        "INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)",
                        listOf(userId, SharedStatus.fromBoolean(isShared).sqlValue, content)
                    )
                }
                contextRetriever?.storeEmbedding("todos", id, content)
                CommandResult(if (isShared) "공유 할 일 추가: *$content*" else "할 일 추가: *$content*")
            }

            SlashCommand.HARILADO_LIST.value -> {
                val rows = transaction {
                    when (params) {
                        SharedStatus.FILTER_LABEL -> execQuery(
                            "SELECT * FROM todos WHERE is_shared = ${SharedStatus.SHARED.sqlValue} AND is_done = 0 ORDER BY created_at DESC",
                            emptyList()
                        )
                        Messages.Todos.DONE_FILTER -> execQuery(
                            "SELECT * FROM todos WHERE (user_id = ? OR is_shared = ${SharedStatus.SHARED.sqlValue}) AND is_done = 1 ORDER BY done_at DESC LIMIT ${AppConfig.LIST_LIMIT_LONG}",
                            listOf(userId)
                        )
                        else -> execQuery(
                            "SELECT * FROM todos WHERE (user_id = ? OR is_shared = ${SharedStatus.SHARED.sqlValue}) AND is_done = 0 ORDER BY created_at DESC",
                            listOf(userId)
                        )
                    }
                }
                val label = when (params) {
                    Messages.Todos.DONE_FILTER -> Messages.Todos.LABEL_DONE
                    SharedStatus.FILTER_LABEL  -> Messages.Todos.LABEL_SHARED
                    else                       -> Messages.Todos.LABEL_LIST
                }
                CommandResult(formatList(label, rows) { r ->
                    "${r["content"]}${if ((r["is_shared"] as? Int) == SharedStatus.SHARED.sqlValue) Messages.Todos.SHARED_TAG else ""}"
                })
            }

            SlashCommand.WANRYO.value -> {
                val id = transaction {
                    execQuery(
                        "SELECT id FROM todos WHERE (user_id = ? OR is_shared = ${SharedStatus.SHARED.sqlValue}) AND is_done = 0 AND content LIKE ? LIMIT 1",
                        listOf(userId, "%$params%")
                    ).firstOrNull()?.get("id") as? Int
                } ?: CommandResult("\"$params\"에 해당하는 미완료 할 일을 찾지 못했어요.")
                transaction {
                    execUpdate(
                        "UPDATE todos SET is_done = 1, done_at = datetime('now','localtime') WHERE id = ?",
                        listOf(id)
                    )
                }
                CommandResult("완료 처리했어요!")
            }

            // ── Memos ──────────────────────────────────────────────────────

            SlashCommand.MEMO.value -> {
                if (params.isBlank()) CommandResult(Messages.Errors.BLANK_MEMO)
                val isShared = params.startsWith(SharedStatus.INPUT_PREFIX)
                val content = if (isShared) params.drop(SharedStatus.INPUT_PREFIX_LEN).trim() else params
                transaction {
                    execInsert(
                        "INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)",
                        listOf(userId, SharedStatus.fromBoolean(isShared).sqlValue, content)
                    )
                }
                CommandResult(if (isShared) "공유 메모 저장했어요: _${content}_" else "메모 저장했어요!")
            }

            SlashCommand.MEMO_LIST.value -> {
                val rows = transaction {
                    if (params == SharedStatus.FILTER_LABEL)
                        execQuery("SELECT * FROM memos WHERE is_shared = ${SharedStatus.SHARED.sqlValue} ORDER BY created_at DESC LIMIT ${AppConfig.LIST_LIMIT_MED}", emptyList())
                    else
                        execQuery("SELECT * FROM memos WHERE (user_id = ? OR is_shared = ${SharedStatus.SHARED.sqlValue}) ORDER BY created_at DESC LIMIT ${AppConfig.LIST_LIMIT_MED}", listOf(userId))
                }
                val label = if (params == SharedStatus.FILTER_LABEL) Messages.Memos.LABEL_SHARED else Messages.Memos.LABEL_MINE
                CommandResult(formatList(label, rows) { r ->
                    val shared = if ((r["is_shared"] as? Int) == SharedStatus.SHARED.sqlValue) Messages.Memos.SHARED_TAG else ""
                    val title = r["title"]?.let { "*$it*\n" } ?: ""
                    "$title${r["content"]}$shared\n_${r["created_at"]}_"
                })
            }

            SlashCommand.MEMO_SEARCH.value -> {
                val rows = transaction {
                    execQuery(
                        "SELECT * FROM memos WHERE (user_id = ? OR is_shared = ${SharedStatus.SHARED.sqlValue}) AND (title LIKE ? OR content LIKE ? OR tags LIKE ?) ORDER BY created_at DESC LIMIT ${AppConfig.LIST_LIMIT_SHORT}",
                        listOf(userId, "%$params%", "%$params%", "%$params%")
                    )
                }
                CommandResult(formatList("\"$params\" 검색 결과", rows) { r ->
                    val shared = if ((r["is_shared"] as? Int) == SharedStatus.SHARED.sqlValue) Messages.Memos.SHARED_TAG else ""
                    "${r["content"]}$shared\n_${r["created_at"]}_"
                })
            }

            // ── Schedules ──────────────────────────────────────────────────

            SlashCommand.SCHEDULE.value -> {
                val isShared = params.startsWith(SharedStatus.INPUT_PREFIX)
                val content = if (isShared) params.drop(SharedStatus.INPUT_PREFIX_LEN).trim() else params
                val eventDate = claudeClient.parseDate(content)
                    ?: return CommandResult(Messages.Errors.DATE_NOT_FOUND)
                transaction {
                    execInsert(
                        "INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)",
                        listOf(userId, SharedStatus.fromBoolean(isShared).sqlValue, content, eventDate)
                    )
                }
                CommandResult(
                    if (isShared) "공유 일정 등록: *$content* ($eventDate)"
                    else "일정 등록: *$content* ($eventDate)"
                )
            }

            SlashCommand.SCHEDULE_LIST.value -> {
                val (from, to) = if (params.isNotBlank()) {
                    claudeClient.parseDateRange(params)
                } else {
                    val today = LocalDate.now()
                    Pair(today.toString(), today.plusDays(30).toString())
                }
                val rows = transaction {
                    execQuery(
                        "SELECT * FROM schedules WHERE (user_id = ? OR is_shared = ${SharedStatus.SHARED.sqlValue}) AND date(event_date) BETWEEN ? AND ? ORDER BY event_date ASC",
                        listOf(userId, from ?: LocalDate.now().toString(), to ?: LocalDate.now().plusDays(30).toString())
                    )
                }
                val label = if (params.isNotBlank()) "일정 ($params)" else "일정 (다음 30일)"
                CommandResult(formatList(label, rows) { r ->
                    val shared = if ((r["is_shared"] as? Int) == SharedStatus.SHARED.sqlValue) Messages.Schedules.SHARED_TAG else ""
                    "*${r["title"]}*$shared\n_${r["event_date"]}_"
                })
            }

            // ── Home Status ────────────────────────────────────────────────

            SlashCommand.STATUS.value -> {
                val parts = params.trim().split(Regex("\\s+"))
                if (parts.size < 2) return CommandResult(Messages.Errors.NO_STATUS_INPUT)
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

            SlashCommand.STATUS_CHECK.value -> {
                if (params.isBlank()) {
                    val rows = transaction {
                        execQuery("SELECT * FROM home_status ORDER BY device_name", emptyList())
                    }
                    CommandResult(formatList(Messages.HomeStatus.LABEL_ALL, rows) { r ->
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

            SlashCommand.LOCATION_SAVE.value -> {
                val parts = params.trim().split(Regex("\\s+"))
                if (parts.size < 2) return CommandResult(Messages.Errors.NO_LOCATION_INPUT)
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

            SlashCommand.LOCATION_CHECK.value -> {
                val row = transaction {
                    execQuery("SELECT * FROM item_locations WHERE item_name = ?", listOf(params)).firstOrNull()
                } ?: CommandResult("*$params* 위치 정보가 없어요.")
                CommandResult(row.toString())
            }

            // ── Assets ─────────────────────────────────────────────────────

            SlashCommand.ASSET.value -> {
                val parts = params.split(Regex("\\s+"))
                if (parts.size < 2) CommandResult(Messages.Errors.NO_AMOUNT_INPUT)
                val category = parts[0]
                val amount = parts[1].replace(",", "").toDoubleOrNull()
                    ?: return CommandResult(Messages.Errors.AMOUNT_NOT_NUMBER)
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

            SlashCommand.ASSET_CHECK.value -> {
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
                val total = rows.sumOf { (it["amount"] as? Double) ?: 0.0 }
                val lines = rows.joinToString("\n") { r ->
                    val amt = (r["amount"] as? Double) ?: 0.0
                    "*${r["category"]}*: ${"%.0f".format(amt)}원"
                }
                CommandResult("*자산 현황*\n$lines\n---\n*합계: ${"%.0f".format(total)}원*")
            }

            SlashCommand.ASSET_HISTORY.value -> {
                val rows = transaction {
                    if (params.isNotBlank())
                        execQuery("SELECT * FROM assets WHERE user_id = ? AND category = ? ORDER BY recorded_at DESC LIMIT ${AppConfig.LIST_LIMIT_LONG}", listOf(userId, params))
                    else
                        execQuery("SELECT * FROM assets WHERE user_id = ? ORDER BY recorded_at DESC LIMIT ${AppConfig.LIST_LIMIT_LONG}", listOf(userId))
                }
                val label = if (params.isNotBlank()) "$params 내역" else "자산 내역"
                CommandResult(formatList(label, rows) { r ->
                    val amt = (r["amount"] as? Double) ?: 0.0
                    val note = r["note"]?.let { " ($it)" } ?: ""
                    "*${r["category"]}*: ${"%.0f".format(amt)}원$note\n_${r["recorded_at"]}_"
                })
            }

            // ── Recipes ────────────────────────────────────────────────────

            SlashCommand.RECIPE_SAVE.value -> {
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

            SlashCommand.RECIPE_SEARCH.value -> {
                transaction {
                    execQuery("SELECT * FROM recipes WHERE name LIKE ? ORDER BY created_at DESC LIMIT 1", listOf("%$params%")).firstOrNull()
                }?.let {
                    val ingredients = it["ingredients"]
                    val steps = it["steps"]
                    val stepsText = if (steps != null && steps.toString().isNotBlank()) "\n\n*조리 순서*\n$steps" else ""
                    CommandResult("*${it["name"]}*\n\n*재료*\n$ingredients$stepsText")
                } ?: CommandResult(Messages.Recipes.NOT_FOUND)

            }

            SlashCommand.RECIPE_LIST.value -> {
                val rows = transaction {
                    execQuery("SELECT id, name, created_at FROM recipes ORDER BY created_at DESC LIMIT ${AppConfig.LIST_LIMIT_LONG}", emptyList())
                }
                if (rows.isEmpty()) CommandResult(Messages.Recipes.NO_ITEMS)
                else {
                    val list = rows.mapIndexed { i, r -> "${i + 1}. *${r["name"]}*" }.joinToString("\n")
                    CommandResult("*레시피 목록*\n$list")
                }
            }

            // ── Grocery ────────────────────────────────────────────────────

            SlashCommand.PURCHASE.value -> {
                QTY_REGEX.matchEntire(params.trim())?.let {
                    val itemName = it.groupValues[1].trim()
                    val qty = it.groupValues[2].toDouble()
                    val unit = it.groupValues[3]

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
                } ?: CommandResult(Messages.Errors.GROCERY_FORMAT)

            }

            SlashCommand.INVENTORY.value -> {
                val items = transaction {
                    execQuery("SELECT * FROM grocery_items ORDER BY name", emptyList())
                }
                if (items.isEmpty()) CommandResult(Messages.Grocery.NO_ITEMS)
                else {
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
                        if (purchases.size < AppConfig.MIN_PURCHASES_FOR_PREDICT) {
                            insufficient.add("${InventoryStatus.INSUFFICIENT.emoji} *$itemName*: ${Messages.Grocery.DATA_INSUF_FMT.format(purchases.size)}")
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
                            daysRemaining <= InventoryStatus.SHORTAGE.maxDays  -> shortage.add("${InventoryStatus.SHORTAGE.emoji} *$itemName*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)")
                            daysRemaining <= InventoryStatus.IMMINENT.maxDays  -> imminent.add("${InventoryStatus.IMMINENT.emoji} *$itemName*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)")
                            else                                                -> ok.add("${InventoryStatus.OK.emoji} *$itemName*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)")
                        }
                    }

                    val sb = StringBuilder("*재고 현황*\n")
                    if (shortage.isNotEmpty()) sb.append("\n*${InventoryStatus.SHORTAGE.label}*\n${shortage.joinToString("\n")}\n")
                    if (imminent.isNotEmpty()) sb.append("\n*${InventoryStatus.IMMINENT.label}*\n${imminent.joinToString("\n")}\n")
                    if (ok.isNotEmpty()) sb.append("\n*${InventoryStatus.OK.label}*\n${ok.joinToString("\n")}\n")
                    if (insufficient.isNotEmpty()) sb.append("\n*${InventoryStatus.INSUFFICIENT.label}*\n${insufficient.joinToString("\n")}")
                    CommandResult(sb.toString())
                }

            }

            else -> CommandResult(Messages.Errors.UNKNOWN_COMMAND)
        }
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
