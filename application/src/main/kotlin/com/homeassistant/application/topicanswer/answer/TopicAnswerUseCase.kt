package com.homeassistant.application.topicanswer.answer

interface TopicAnswerUseCase {
    fun answer(request: TopicAnswerRequest): TopicAnswerResult
}
