package com.peerchat.templates

/**
 * Lightweight role model supported by the prompt templates.
 */
enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

/**
 * Normalised representation of a chat message used when constructing prompts.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

/**
 * Result of applying a template. Carries both the textual prompt and any stop sequences
 * required by the underlying model to terminate cleanly.
 */
data class ChatPrompt(
    val text: String,
    val stopSequences: List<String>,
)

/**
 * Contract implemented by concrete chat templates. Implementations must emit the full prompt
 * including the header required to begin the assistant response, leaving the model ready to
 * stream tokens for the assistant role.
 */
interface ChatTemplate {
    val id: String
    val displayName: String
    val stopSequences: List<String>

    /**
     * Builds a prompt for the provided conversation state.
     *
     * @param systemPrompt optional system instruction applied to the conversation.
     * @param history ordered list of previous turns (user/assistant). Messages with the
     *                [ChatRole.USER] and [ChatRole.ASSISTANT] roles must alternate.
     * @param nextUser next user turn to complete; this should already include any RAG context.
     */
    fun build(
        systemPrompt: String?,
        history: List<ChatMessage>,
        nextUser: ChatMessage,
    ): ChatPrompt
}
