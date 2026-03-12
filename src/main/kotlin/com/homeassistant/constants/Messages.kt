package com.homeassistant.constants

object Messages {
    object Errors {
        const val BLANK_INPUT       = "할 일 내용을 입력해주세요."
        const val BLANK_MEMO        = "메모 내용을 입력해주세요."
        const val DATE_NOT_FOUND    = "날짜/시간 정보를 찾지 못했어요. 날짜를 포함해서 입력해주세요."
        const val NO_STATUS_INPUT   = "상태도 함께 입력해주세요. 예: \"에어컨 켜\""
        const val NO_LOCATION_INPUT = "위치도 함께 입력해주세요."
        const val NO_AMOUNT_INPUT   = "금액도 함께 입력해주세요."
        const val AMOUNT_NOT_NUMBER = "금액은 숫자로 입력해주세요."
        const val GROCERY_FORMAT    = "형식: \"달걀 10개\" (수량+단위 필수)"
        const val UNKNOWN_COMMAND   = "해당 명령어를 처리할 수 없어요."
        const val PIPELINE_ERROR    = "죄송해요, 요청을 처리하는 중 오류가 발생했어요. 잠시 후 다시 시도해주세요."
        const val NLP_FALLBACK      = "응답을 처리하지 못했어요."
    }
    object Todos {
        const val LABEL_DONE   = "완료된 할 일"
        const val LABEL_SHARED = "공유 할 일"
        const val LABEL_LIST   = "할 일 목록"
        const val SHARED_TAG   = " _(공유)_"
        const val DONE_FILTER  = "완료"   // /할일목록 완료 파라미터
    }
    object Memos {
        const val LABEL_SHARED = "공유 메모"
        const val LABEL_MINE   = "내 메모"
        const val SHARED_TAG   = " _(공유)_"
    }
    object Schedules {
        const val SHARED_TAG = " _(공유)_"
    }
    object HomeStatus {
        const val LABEL_ALL = "집 전체 상태"
    }
    object Grocery {
        const val NO_ITEMS           = "기록된 식재료가 없어요. 구매 기록부터 추가해보세요."
        const val DATA_INSUF_FMT     = "구매 이력 부족 (%d회) — 예측 불가"
    }
    object Recipes {
        const val NOT_FOUND  = "레시피를 찾지 못했어요."
        const val NO_ITEMS   = "저장된 레시피가 없어요."
    }
    object Routes {
        const val HEALTH_STATUS = "ok"
    }
}
