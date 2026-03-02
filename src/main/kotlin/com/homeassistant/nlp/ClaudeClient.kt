package com.homeassistant.nlp

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.TextBlock
import com.homeassistant.models.*
import kotlinx.serialization.json.*
import java.time.LocalDate

class ClaudeClient(apiKey: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    private val model = Model.CLAUDE_3_5_HAIKU_LATEST

    // ── Date parsers ─────────────────────────────────────────────────────────

    private fun dateSystemPrompt(): String {
        val today = LocalDate.now().toString()
        return """You are a date parser for a Korean home assistant bot.
Convert Korean date/time expressions to ISO 8601 format using today's date ($today).
Return ONLY valid JSON, no explanation.

Examples:
- "내일 오후 3시" → { "date": "${today}T15:00" }
- "이번 주 금요일" → { "date": "${today}T00:00" }

If no time given, use T00:00."""
    }

    private fun dateRangeSystemPrompt(): String {
        val today = LocalDate.now().toString()
        return """You are a date range parser for a Korean home assistant bot.
Convert Korean date range expressions to ISO 8601 using today's date ($today).
Return ONLY valid JSON with "from" and "to" fields (YYYY-MM-DD format), no explanation.

Examples:
- "이번 주" → { "from": "$today", "to": "$today" }
- "다음 달" → { "from": "$today", "to": "$today" }"""
    }

    suspend fun parseDate(text: String): String? {
        val responseText = callClaude(
            system = dateSystemPrompt(),
            userMessage = text,
            maxTokens = 128,
            temperature = 0.0,
        ) ?: return null
        return try {
            Json.parseToJsonElement(responseText.trim())
                .jsonObject["date"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
    }

    suspend fun parseDateRange(text: String): Pair<String?, String?> {
        val responseText = callClaude(
            system = dateRangeSystemPrompt(),
            userMessage = text,
            maxTokens = 128,
            temperature = 0.0,
        ) ?: return Pair(null, null)
        return try {
            val obj = Json.parseToJsonElement(responseText.trim()).jsonObject
            Pair(
                obj["from"]?.jsonPrimitive?.content,
                obj["to"]?.jsonPrimitive?.content,
            )
        } catch (_: Exception) { Pair(null, null) }
    }

    // ── Intent analyzer ──────────────────────────────────────────────────────

    private val intentSystem = """당신은 한국 가정용 Slack 봇의 의도 분석기입니다.
사용자 발화를 분석하여 필요한 DB context를 JSON으로 반환하세요.

사용 가능한 intent 값 (이 중 하나만 사용):
memo_add, memo_search, memo_list,
todo_add, todo_list, todo_done,
schedule_add, schedule_list,
status_update, status_check,
location_save, location_check,
asset_add, asset_check, asset_history,
recipe_save, recipe_search, recipe_list,
grocery_add, grocery_check,
greeting, other

사용 가능한 DB: memos, todos, schedules, home_status, item_locations, assets, recipes, grocery_items

조회 타입:
- recent: 최근 데이터가 필요할 때
- similar: 특정 내용과 유사한 데이터 검색 (searchText 필드 필수)
- query: 날짜/카테고리/키워드 등 조건 기반 조회 (filter 필드 필수)
  filter 가능 필드: keyword, dateFrom, dateTo, category, isShared

반환 형식 (JSON only, 다른 텍스트 없이):
{"intent":"...","contexts":[...]}"""

    suspend fun analyzeIntent(
        history: List<ConversationMessage>,
        userText: String,
    ): IntentAnalysis {
        val messages = buildMessages(history, userText)
        val responseText = callClaude(
            system = intentSystem,
            messages = messages,
            maxTokens = 512,
            temperature = 0.0,
        ) ?: return IntentAnalysis("unknown", emptyList())

        return try {
            val obj = Json.parseToJsonElement(responseText.trim()).jsonObject
            val intent = obj["intent"]?.jsonPrimitive?.content ?: "unknown"
            val validDbs = setOf(
                "memos", "todos", "schedules", "home_status",
                "item_locations", "assets", "recipes", "grocery_items"
            )
            val contexts = obj["contexts"]?.jsonArray?.mapNotNull { elem ->
                val o = elem.jsonObject
                val db = o["db"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val type = o["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (db !in validDbs) return@mapNotNull null
                val searchText = o["searchText"]?.jsonPrimitive?.content
                val filter = o["filter"]?.jsonObject?.let { f ->
                    FilterParams(
                        keyword = f["keyword"]?.jsonPrimitive?.content,
                        dateFrom = f["dateFrom"]?.jsonPrimitive?.content,
                        dateTo = f["dateTo"]?.jsonPrimitive?.content,
                        category = f["category"]?.jsonPrimitive?.content,
                        isShared = f["isShared"]?.jsonPrimitive?.booleanOrNull,
                    )
                }
                ContextSpec(db = db, type = type, searchText = searchText, filter = filter)
            } ?: emptyList()
            IntentAnalysis(intent, contexts)
        } catch (_: Exception) {
            IntentAnalysis("unknown", emptyList())
        }
    }

    // ── Chat session ─────────────────────────────────────────────────────────

    private val chatbotSystem = """당신은 한국 가정용 Slack 봇 어시스턴트입니다.
사용자의 자연어 메시지를 분석하여 아래 명령어 중 하나로 매핑하고 JSON으로 응답하세요.

사용 가능한 명령어:
- /메모 <내용> : 메모 저장 (앞에 "공유" 붙이면 가족 공유)
- /메모목록 [공유] : 메모 목록 조회
- /메모검색 <검색어> : 메모 검색
- /일정 <날짜+제목> : 일정 등록 (앞에 "공유" 붙이면 가족 공유)
- /일정목록 [기간] : 일정 목록 조회
- /상태 <기기> <상태> : 집 기기 상태 업데이트
- /상태확인 [기기] : 기기 상태 조회 (비우면 전체)
- /위치저장 <물건> <위치> : 물건 위치 저장
- /위치 <물건> : 물건 위치 조회
- /자산 <카테고리> <금액> : 자산 기록
- /자산확인 : 카테고리별 현재 자산
- /자산내역 [카테고리] : 자산 변동 내역
- /할일 <내용> : 할 일 추가 (앞에 "공유" 붙이면 가족 공유)
- /할일목록 [공유|완료] : 할 일 목록
- /완료 <키워드> : 할 일 완료 처리
- /레시피저장 <이름\n재료: ...\n순서: ...> : 레시피 저장
- /레시피 <검색어> : 레시피 검색
- /레시피목록 : 전체 레시피 목록
- /구매 <식재료> <수량+단위> : 구매 기록
- /재고 : 재고 현황 및 부족 예측

응답 형식 (반드시 유효한 JSON만 출력):
- 추가 정보가 필요한 경우: {"type":"question","text":"질문 내용"}
- 명령어 확정된 경우: {"type":"result","text":"안내 메시지","command":"/명령어","params":"파라미터"}
- 이해 불가한 경우: {"type":"unknown","text":"안내 메시지"}

규칙:
- params는 명령어 뒤에 오는 텍스트만 포함 (명령어 자체 제외)
- /구매의 params 형식: "재료이름 수량단위" (예: "달걀 12개")
- 항상 JSON만 응답하고 다른 텍스트는 포함하지 마세요"""

    suspend fun chatSession(
        history: List<ConversationMessage>,
        userMessage: String,
        context: List<ContextResult> = emptyList(),
    ): NlpChatResponse {
        val contextBlock = formatContext(context)
        val fullUserMessage = if (contextBlock.isNotEmpty())
            "[context]\n$contextBlock\n[/context]\n\n$userMessage"
        else
            userMessage

        val messages = buildMessages(history, fullUserMessage)
        val responseText = callClaude(
            system = chatbotSystem,
            messages = messages,
            maxTokens = 512,
        ) ?: return NlpChatResponse("unknown", "응답을 처리하지 못했어요.")

        return try {
            val obj = Json.parseToJsonElement(responseText.trim()).jsonObject
            NlpChatResponse(
                type = obj["type"]?.jsonPrimitive?.content ?: "unknown",
                text = obj["text"]?.jsonPrimitive?.content ?: "응답을 처리하지 못했어요.",
                command = obj["command"]?.jsonPrimitive?.content,
                params = obj["params"]?.jsonPrimitive?.content,
            )
        } catch (_: Exception) {
            NlpChatResponse("unknown", "응답을 처리하지 못했어요.")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatContext(results: List<ContextResult>): String =
        results.filter { it.rows.isNotEmpty() }
            .joinToString("\n\n") { r ->
                val label = "${r.db} (${r.type})"
                val lines = r.rows.take(10).joinToString("\n") { row ->
                    val values = row.entries
                        .filter { it.key !in setOf("id", "user_id", "is_shared") }
                        .joinToString(", ") { "${it.key}: ${it.value}" }
                    "- $values"
                }
                "$label:\n$lines"
            }

    /** Build messages list for multi-turn context. */
    private fun buildMessages(
        history: List<ConversationMessage>,
        userText: String,
    ): List<Pair<String, String>> =
        history.map { Pair(it.role, it.content) } + Pair("user", userText)

    /** Core Claude API call. Returns the text content or null on failure. */
    private fun callClaude(
        system: String,
        userMessage: String? = null,
        messages: List<Pair<String, String>>? = null,
        maxTokens: Int = 512,
        temperature: Double? = null,
    ): String? {
        val msgList = when {
            messages != null -> messages
            userMessage != null -> listOf(Pair("user", userMessage))
            else -> return null
        }

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())
            .system(system)
            .apply {
                msgList.forEach { (role, content) ->
                    when (role) {
                        "user" -> this.addUserMessage(content)
                        "assistant" -> this.addAssistantMessage(content)
                    }
                }
                if (temperature != null) this.temperature(temperature)
            }
            .build()

        val response = client.messages().create(params)
        return (response.content().firstOrNull() as? TextBlock)?.text()
    }
}
