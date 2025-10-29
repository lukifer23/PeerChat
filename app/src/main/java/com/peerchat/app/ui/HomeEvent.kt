package com.peerchat.app.ui

sealed interface HomeEvent {
    data class Toast(val message: String) : HomeEvent
}
