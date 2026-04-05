package com.homeassistant.nlp

enum class Intent(val value: String) {
    // TODO: from domain
    MEMO_ADD("memo_add"), MEMO_SEARCH("memo_search"), MEMO_LIST("memo_list"),
    TODO_ADD("todo_add"), TODO_LIST("todo_list"), TODO_DONE("todo_done"),
    SCHEDULE_ADD("schedule_add"), SCHEDULE_LIST("schedule_list"),
    STATUS_UPDATE("status_update"), STATUS_CHECK("status_check"),
    LOCATION_SAVE("location_save"), LOCATION_CHECK("location_check"),
    ASSET_ADD("asset_add"), ASSET_CHECK("asset_check"), ASSET_HISTORY("asset_history"),
    RECIPE_SAVE("recipe_save"), RECIPE_SEARCH("recipe_search"), RECIPE_LIST("recipe_list"),
    GROCERY_ADD("grocery_add"), GROCERY_CHECK("grocery_check"),
    GREETING("greeting"), OTHER("other");

    companion object {
        // intentSystem 프롬프트에서 Claude에게 전달할 목록 (원본 순서 그대로 유지)
        val ALL_VALUES: String = entries.joinToString(", ") { it.value }
    }
}
