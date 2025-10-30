package com.peerchat.app.ui

sealed interface HomeEvent {
    data class Toast(val message: String) : HomeEvent
    data class OpenChat(val chatId: Long) : HomeEvent
}
