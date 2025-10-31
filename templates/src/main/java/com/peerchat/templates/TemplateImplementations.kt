package com.peerchat.templates

object TemplateIds {
    const val LLAMA_3 = "llama-3"
    const val CHATML = "chatml"
    const val QWEN = "qwen"
    const val GEMMA = "gemma"
    const val MISTRAL = "mistral-inst"
}

private fun String.trimmed(): String = trim().replace("\r\n", "\n")

private fun normaliseHistory(history: List<ChatMessage>): List<ChatMessage> {
    if (history.isEmpty()) return history
    val result = ArrayList<ChatMessage>(history.size)
    var lastRole: ChatRole? = null
    history.forEach { message ->
        if (message.content.isBlank()) return@forEach
        val role = message.role
        if (role == ChatRole.SYSTEM) return@forEach
        if (lastRole == role && role != ChatRole.SYSTEM) {
            // Merge consecutive messages with the same role into one turn to avoid template violations.
            val merged = result.removeLast()
            result.add(
                merged.copy(
                    content = buildString {
                        append(merged.content)
                        append("\n\n")
                        append(message.content)
                    }
                )
            )
        } else {
            result.add(message)
        }
        lastRole = role
    }
    return result
}

/**
 * Template aligned with Llama 3 / Meta Instruct formatting.
 *
 * ```
 * <|begin_of_text|><|start_header_id|>system<|end_header_id|>
 * ...
 * <|eot_id|><|start_header_id|>user<|end_header_id|>
 * ...
 * <|eot_id|><|start_header_id|>assistant<|end_header_id|>
 * ```
 */
private class Llama3Template : ChatTemplate {
    override val id: String = TemplateIds.LLAMA_3
    override val displayName: String = "Llama 3 / Meta Instruct"
    override val stopSequences: List<String> = listOf("<|eot_id|>")

    override fun build(
        systemPrompt: String?,
        history: List<ChatMessage>,
        nextUser: ChatMessage,
    ): ChatPrompt {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")

        fun appendBlock(role: String, content: String) {
            val trimmed = content.trimmed()
            if (trimmed.isEmpty()) return
            sb.append("<|start_header_id|>")
            sb.append(role)
            sb.append("<|end_header_id|>\n")
            sb.append(trimmed)
            sb.append("<|eot_id|>")
        }

        systemPrompt?.takeIf { it.isNotBlank() }?.let { appendBlock("system", it) }

        normaliseHistory(history).forEach { message ->
            val role = when (message.role) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.SYSTEM -> return@forEach
            }
            appendBlock(role, message.content)
        }

        appendBlock("user", nextUser.content)
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
        return ChatPrompt(sb.toString(), stopSequences)
    }
}

/**
 * OpenAI ChatML-style template shared by multiple model families (Granite, some Qwen builds, etc).
 */
private class ChatMlTemplate(
    override val id: String,
    override val displayName: String,
) : ChatTemplate {
    override val stopSequences: List<String> = listOf("<|im_end|>")

    override fun build(
        systemPrompt: String?,
        history: List<ChatMessage>,
        nextUser: ChatMessage,
    ): ChatPrompt {
        val sb = StringBuilder()

        fun append(role: String, content: String) {
            val trimmed = content.trimmed()
            if (trimmed.isEmpty()) return
            sb.append("<|im_start|>")
            sb.append(role)
            sb.append("\n")
            sb.append(trimmed)
            sb.append("<|im_end|>\n")
        }

        systemPrompt?.takeIf { it.isNotBlank() }?.let { append("system", it) }
        normaliseHistory(history).forEach { message ->
            when (message.role) {
                ChatRole.USER -> append("user", message.content)
                ChatRole.ASSISTANT -> append("assistant", message.content)
                ChatRole.SYSTEM -> Unit
            }
        }
        append("user", nextUser.content)
        sb.append("<|im_start|>assistant\n")
        return ChatPrompt(sb.toString(), stopSequences)
    }
}

/**
 * Gemma 2 template using <start_of_turn> / <end_of_turn> markers.
 */
private class GemmaTemplate : ChatTemplate {
    override val id: String = TemplateIds.GEMMA
    override val displayName: String = "Gemma Instruct"
    override val stopSequences: List<String> = listOf("<end_of_turn>")

    override fun build(
        systemPrompt: String?,
        history: List<ChatMessage>,
        nextUser: ChatMessage,
    ): ChatPrompt {
        val sb = StringBuilder()
        sb.append("<bos>")

        fun append(role: String, content: String) {
            val trimmed = content.trimmed()
            if (trimmed.isEmpty()) return
            sb.append("<start_of_turn>")
            sb.append(role)
            sb.append("\n")
            sb.append(trimmed)
            sb.append("<end_of_turn>\n")
        }

        systemPrompt?.takeIf { it.isNotBlank() }?.let { append("system", it) }
        normaliseHistory(history).forEach { message ->
            when (message.role) {
                ChatRole.USER -> append("user", message.content)
                ChatRole.ASSISTANT -> append("model", message.content)
                ChatRole.SYSTEM -> Unit
            }
        }
        append("user", nextUser.content)
        sb.append("<start_of_turn>model\n")
        return ChatPrompt(sb.toString(), stopSequences)
    }
}

/**
 * Mistral / Mixtral instruction template. Handles multi-turn transcripts by wrapping each
 * user query in an [INST] block and appending assistant continuations. The final block is left
 * without an assistant response so inference can stream directly afterwards.
 */
private class MistralTemplate : ChatTemplate {
    override val id: String = TemplateIds.MISTRAL
    override val displayName: String = "Mistral Instruct"
    override val stopSequences: List<String> = listOf("</s>")

    override fun build(
        systemPrompt: String?,
        history: List<ChatMessage>,
        nextUser: ChatMessage,
    ): ChatPrompt {
        val turns = ArrayList<Pair<String, String?>>()
        var pendingUser: StringBuilder? = null
        val cleanedHistory = normaliseHistory(history)
        cleanedHistory.forEach { message ->
            when (message.role) {
                ChatRole.USER -> {
                    if (pendingUser == null) pendingUser = StringBuilder()
                    else pendingUser!!.append("\n\n")
                    pendingUser!!.append(message.content.trimmed())
                }
                ChatRole.ASSISTANT -> {
                    val userText = pendingUser?.toString()
                    if (userText != null) {
                        turns.add(userText to message.content.trimmed())
                        pendingUser = null
                    }
                }
                ChatRole.SYSTEM -> Unit
            }
        }
        pendingUser?.toString()?.let { turns.add(it to null) }
        turns.add(nextUser.content.trimmed() to null)

        val sb = StringBuilder()
        val sys = systemPrompt?.trimmed()
        turns.forEachIndexed { index, (user, assistant) ->
            sb.append("<s>[INST]")
            if (index == 0 && !sys.isNullOrEmpty()) {
                sb.append(" <<SYS>>\n")
                sb.append(sys)
                sb.append("\n<</SYS>>\n\n")
            } else {
                sb.append(" ")
            }
            sb.append(user)
            sb.append(" [/INST]")
            if (assistant != null) {
                sb.append("\n")
                sb.append(assistant)
                sb.append("</s>")
            } else {
                sb.append("\n")
            }
        }
        return ChatPrompt(sb.toString(), stopSequences)
    }
}

/**
 * Registry of supported templates and heuristics to detect them from GGUF metadata.
 */
object TemplateCatalog {
    data class Descriptor(
        val id: String,
        val displayName: String,
        val stopSequences: List<String>,
    )

    data class ModelMetadata(
        val arch: String?,
        val chatTemplate: String?,
        val tokenizerModel: String?,
        val tags: String?,
    )

    private val templates: Map<String, ChatTemplate> = mapOf(
        TemplateIds.LLAMA_3 to Llama3Template(),
        TemplateIds.CHATML to ChatMlTemplate(TemplateIds.CHATML, "ChatML"),
        TemplateIds.QWEN to ChatMlTemplate(TemplateIds.QWEN, "Qwen ChatML"),
        TemplateIds.GEMMA to GemmaTemplate(),
        TemplateIds.MISTRAL to MistralTemplate(),
    )

    fun descriptors(): List<Descriptor> =
        templates.values.map { template ->
            Descriptor(template.id, template.displayName, template.stopSequences)
        }.sortedBy { it.displayName }

    fun resolve(id: String?): ChatTemplate? = id?.let { templates[it] }

    fun default(): ChatTemplate = templates[TemplateIds.LLAMA_3]!!

    fun detect(metadata: ModelMetadata): String {
        val arch = metadata.arch?.lowercase().orEmpty()
        val tmpl = metadata.chatTemplate?.lowercase().orEmpty()
        val tokenizer = metadata.tokenizerModel?.lowercase().orEmpty()
        val tags = metadata.tags?.lowercase().orEmpty()

        fun matches(value: String, vararg probes: String): Boolean =
            probes.any { probe -> value.contains(probe, ignoreCase = true) }

        // More specific patterns first, then generic fallbacks
        return when {
            // Llama 3 detection (most specific)
            matches(tmpl, "<|start_header_id|>", "llama-3", "meta-llama") ||
                matches(tokenizer, "llama-3", "meta-llama-3") ||
                (matches(arch, "llama") && matches(tokenizer, "llama-3")) -> TemplateIds.LLAMA_3

            // Qwen detection
            matches(arch, "qwen") ||
                matches(tokenizer, "qwen") ||
                matches(tmpl, "<|im_start|>", "<|im_end|>") ||
                matches(tags, "qwen") -> TemplateIds.QWEN

            // Granite detection
            matches(arch, "granite") ||
                matches(tokenizer, "granite") ||
                matches(tags, "granite") -> TemplateIds.CHATML

            // Gemma detection
            matches(arch, "gemma") ||
                matches(tmpl, "<start_of_turn>", "<end_of_turn>") ||
                matches(tokenizer, "gemma") -> TemplateIds.GEMMA

            // Mistral detection
            matches(arch, "mistral", "mixtral") ||
                matches(tags, "mistral", "mixtral") ||
                matches(tmpl, "[inst]", "[system]", "mistral") ||
                matches(tokenizer, "mistral", "mixtral") -> TemplateIds.MISTRAL

            // InternVL uses Qwen-style templates
            matches(arch, "internvl") -> TemplateIds.QWEN

            // Generic Llama fallback (check for Llama architecture without specific version)
            matches(arch, "llama") && !matches(arch, "llama-3") -> {
                // Try to infer from tokenizer or template
                when {
                    matches(tmpl, "[inst]", "[system]") -> TemplateIds.MISTRAL
                    matches(tmpl, "<|im_start|>") -> TemplateIds.QWEN
                    else -> TemplateIds.LLAMA_3 // Default to Llama 3 for generic Llama
                }
            }

            // Default fallback
            else -> {
                TemplateIds.LLAMA_3
            }
        }
    }

    fun parseMetadata(rawJson: String?): ModelMetadata {
        if (rawJson.isNullOrBlank()) return ModelMetadata(null, null, null, null)
        
        return runCatching {
            val obj = org.json.JSONObject(rawJson)
            fun extract(vararg keys: String): String? {
                keys.forEach { key ->
                    if (obj.has(key)) {
                        val value = obj.optString(key)
                        if (!value.isNullOrBlank()) {
                            val trimmed = value.trim()
                            if (trimmed.isNotEmpty()) return trimmed
                        }
                    }
                }
                return null
            }
            
            // Try nested keys (e.g., "general.architecture")
            fun extractNested(key: String): String? {
                val parts = key.split(".")
                var current: Any? = obj
                for (part in parts) {
                    current = (current as? org.json.JSONObject)?.opt(part)
                    if (current == null) return null
                }
                return when (current) {
                    is String -> current.takeIf { it.isNotBlank() }
                    is Number -> current.toString()
                    else -> null
                }
            }
            
            ModelMetadata(
                arch = extract("arch", "general.architecture")
                    ?: extractNested("general.architecture"),
                chatTemplate = extract("chatTemplate", "tokenizer.chat_template")
                    ?: extractNested("tokenizer.chat_template"),
                tokenizerModel = extract("tokenizerModel", "tokenizer.ggml.model")
                    ?: extractNested("tokenizer.ggml.model"),
                tags = extract("tags", "general.tags")
                    ?: extractNested("general.tags"),
            )
        }.getOrElse { e ->
            ModelMetadata(null, null, null, null)
        }
    }

    fun detect(rawMetadataJson: String?): String = detect(parseMetadata(rawMetadataJson))
}
