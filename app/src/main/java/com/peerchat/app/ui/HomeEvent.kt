package com.peerchat.app.ui

sealed interface HomeEvent {
    data class Toast(val message: String, val isError: Boolean = false) : HomeEvent
    data class SelectChat(val chatId: Long) : HomeEvent
}
