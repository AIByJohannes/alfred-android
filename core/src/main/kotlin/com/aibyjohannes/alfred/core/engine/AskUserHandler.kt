package com.aibyjohannes.alfred.core.engine

fun interface AskUserHandler {
    suspend fun askUser(request: AskUserRequest): AskUserAnswer
}

data class AskUserRequest(val question: String, val options: List<String> = emptyList())

sealed interface AskUserAnswer {
    data class Option(val value: String) : AskUserAnswer
    data class Text(val value: String) : AskUserAnswer
    data object Cancelled : AskUserAnswer
}
