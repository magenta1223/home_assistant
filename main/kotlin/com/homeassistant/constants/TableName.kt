package com.homeassistant.constants

object TableName {
    const val MEMOS          = "memos"
    const val TODOS          = "todos"
    const val SCHEDULES      = "schedules"
    const val HOME_STATUS    = "home_status"
    const val ITEM_LOCATIONS = "item_locations"
    const val ASSETS         = "assets"
    const val RECIPES        = "recipes"
    const val GROCERY_ITEMS  = "grocery_items"
    const val VEC_MEMOS      = "vec_memos"
    const val VEC_TODOS      = "vec_todos"
    const val VEC_RECIPES    = "vec_recipes"

    val ALL_DATA_TABLES: Set<String> = setOf(
        MEMOS, TODOS, SCHEDULES, HOME_STATUS,
        ITEM_LOCATIONS, ASSETS, RECIPES, GROCERY_ITEMS
    )
    val VEC_TABLES: Set<String> = setOf(VEC_MEMOS, VEC_TODOS, VEC_RECIPES)
}
