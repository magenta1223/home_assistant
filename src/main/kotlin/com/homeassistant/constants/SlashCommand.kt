package com.homeassistant.constants

enum class SlashCommand(val value: String) {
    HARILADO("/할일"), HARILADO_LIST("/할일목록"), WANRYO("/완료"),
    MEMO("/메모"), MEMO_LIST("/메모목록"), MEMO_SEARCH("/메모검색"),
    SCHEDULE("/일정"), SCHEDULE_LIST("/일정목록"),
    STATUS("/상태"), STATUS_CHECK("/상태확인"),
    LOCATION_SAVE("/위치저장"), LOCATION_CHECK("/위치"),
    ASSET("/자산"), ASSET_CHECK("/자산확인"), ASSET_HISTORY("/자산내역"),
    RECIPE_SAVE("/레시피저장"), RECIPE_SEARCH("/레시피"), RECIPE_LIST("/레시피목록"),
    PURCHASE("/구매"), INVENTORY("/재고");
}
