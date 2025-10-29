
package com.peerchat.app.ui

import com.peerchat.data.db.Message

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 1024,
    val selectedTemplateId: String? = null,
)
