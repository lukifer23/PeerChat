package com.peerchat.engine

fun interface TokenCallback {
    fun onToken(chunk: String, done: Boolean)
}

