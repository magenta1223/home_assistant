package com.homeassistant.constants

enum class InventoryStatus(
    val emoji: String,
    val label: String,
    val maxDays: Int,  // 이 값 이하(<=)이면 해당 상태
) {
    SHORTAGE("⚠️", "부족 예상", 0),
    IMMINENT("🔔", "구매 임박", 3),
    OK("✅", "여유 있음", Int.MAX_VALUE),
    INSUFFICIENT("📊", "데이터 부족", -1),
}
