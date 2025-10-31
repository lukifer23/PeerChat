package com.peerchat.app.engine

import com.peerchat.data.db.Message
import com.peerchat.templates.ChatMessage
import com.peerchat.templates.ChatPrompt
import com.peerchat.templates.ChatRole
import com.peerchat.templates.ChatTemplate
import com.peerchat.templates.TemplateCatalog

/**
 * Composes chat prompts by combining system prompts, conversation history, and user input
 * with the appropriate chat template.
 */
object PromptComposer {
    /**
     * Input parameters for prompt composition.
     *
     * @param systemPrompt Optional system prompt to include at the start.
     * @param history List of previous messages in the conversation.
     * @param nextUserContent The current user input to process.
     * @param selectedTemplateId Template ID explicitly selected by the user.
     * @param detectedTemplateId Template ID auto-detected from model metadata.
     */
    data class Inputs(
        val systemPrompt: String?,
        val history: List<Message>,
        val nextUserContent: String,
        val selectedTemplateId: String?,
        val detectedTemplateId: String?,
    )

    /**
     * Result of prompt composition.
     *
     * @param prompt The composed chat prompt with text and stop sequences.
     * @param template The template used for composition.
     */
    data class Result(
        val prompt: ChatPrompt,
        val template: ChatTemplate,
    )

    /**
     * Composes a prompt from the given inputs.
     *
     * Template resolution order: selected > detected > default.
     *
     * @param inputs The composition inputs.
     * @return The composed prompt result.
     */
    fun compose(inputs: Inputs): Result {
        val template = resolveTemplate(inputs.selectedTemplateId, inputs.detectedTemplateId)
        val history = inputs.history.mapNotNull { it.toChatMessage() }
        val prompt = template.build(
            systemPrompt = inputs.systemPrompt,
            history = history,
            nextUser = ChatMessage(ChatRole.USER, inputs.nextUserContent)
        )
        return Result(prompt, template)
    }

    private fun resolveTemplate(selectedId: String?, detectedId: String?): ChatTemplate {
        val selected = TemplateCatalog.resolve(selectedId)
        val detected = TemplateCatalog.resolve(detectedId)
        return selected ?: detected ?: TemplateCatalog.default()
    }
}

private fun Message.toChatMessage(): ChatMessage? {
    val role = when (role.lowercase()) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.ASSISTANT
        else -> return null
    }
    return ChatMessage(role, contentMarkdown)
}
