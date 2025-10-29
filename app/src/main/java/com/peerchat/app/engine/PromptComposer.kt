package com.peerchat.app.engine

import com.peerchat.data.db.Message
import com.peerchat.templates.ChatMessage
import com.peerchat.templates.ChatPrompt
import com.peerchat.templates.ChatRole
import com.peerchat.templates.ChatTemplate
import com.peerchat.templates.TemplateCatalog

object PromptComposer {
    data class Inputs(
        val systemPrompt: String?,
        val history: List<Message>,
        val nextUserContent: String,
        val selectedTemplateId: String?,
        val detectedTemplateId: String?,
    )

    data class Result(
        val prompt: ChatPrompt,
        val template: ChatTemplate,
    )

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
