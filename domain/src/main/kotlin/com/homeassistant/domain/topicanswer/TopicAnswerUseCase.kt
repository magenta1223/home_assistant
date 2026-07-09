package com.homeassistant.domain.topicanswer

interface TopicAnswerUseCase {
    fun answer(request: TopicAnswerRequest): TopicAnswerResult
}
