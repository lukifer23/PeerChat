package com.peerchat.app.util

import com.peerchat.data.db.Chat
import com.peerchat.data.db.Document
import com.peerchat.data.db.Embedding
import com.peerchat.data.db.Folder
import com.peerchat.data.db.Message
import com.peerchat.data.db.RagChunk

/**
 * Data validation utilities for ensuring data integrity
 */
object DataValidation {

    /**
     * Validate folder data
     */
    fun validateFolder(folder: Folder): ValidationResult {
        val errors = mutableListOf<String>()

        if (folder.name.isBlank()) {
            errors.add("Folder name cannot be blank")
        }

        if (folder.name.length > 100) {
            errors.add("Folder name too long (max 100 characters)")
        }

        if (folder.createdAt <= 0) {
            errors.add("Invalid creation timestamp")
        }

        if (folder.updatedAt < folder.createdAt) {
            errors.add("Updated timestamp cannot be before creation timestamp")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    /**
     * Validate chat data
     */
    fun validateChat(chat: Chat): ValidationResult {
        val errors = mutableListOf<String>()

        if (chat.title.isBlank()) {
            errors.add("Chat title cannot be blank")
        }

        if (chat.title.length > 200) {
            errors.add("Chat title too long (max 200 characters)")
        }

        if (chat.systemPrompt.length > 10000) {
            errors.add("System prompt too long (max 10000 characters)")
        }

        if (chat.modelId.isBlank()) {
            errors.add("Model ID cannot be blank")
        }

        if (chat.createdAt <= 0) {
            errors.add("Invalid creation timestamp")
        }

        if (chat.updatedAt < chat.createdAt) {
            errors.add("Updated timestamp cannot be before creation timestamp")
        }

        // Validate JSON
        if (!isValidJson(chat.settingsJson)) {
            errors.add("Invalid settings JSON")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    /**
     * Validate message data
     */
    fun validateMessage(message: Message): ValidationResult {
        val errors = mutableListOf<String>()

        if (message.chatId <= 0) {
            errors.add("Invalid chat ID")
        }

        if (message.role !in listOf("user", "assistant", "system")) {
            errors.add("Invalid message role: ${message.role}")
        }

        if (message.contentMarkdown.isBlank()) {
            errors.add("Message content cannot be blank")
        }

        if (message.contentMarkdown.length > 100000) {
            errors.add("Message content too long (max 100000 characters)")
        }

        if (message.tokens < 0) {
            errors.add("Token count cannot be negative")
        }

        if (message.ttfsMs < 0) {
            errors.add("TTFS time cannot be negative")
        }

        if (message.tps < 0) {
            errors.add("Tokens per second cannot be negative")
        }

        if (message.contextUsedPct < 0 || message.contextUsedPct > 1) {
            errors.add("Context usage percentage must be between 0 and 1")
        }

        if (message.createdAt <= 0) {
            errors.add("Invalid creation timestamp")
        }

        // Validate JSON
        if (!isValidJson(message.metaJson)) {
            errors.add("Invalid metadata JSON")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    /**
     * Validate document data
     */
    fun validateDocument(document: Document): ValidationResult {
        val errors = mutableListOf<String>()

        if (document.uri.isBlank()) {
            errors.add("Document URI cannot be blank")
        }

        if (document.title.isBlank()) {
            errors.add("Document title cannot be blank")
        }

        if (document.title.length > 500) {
            errors.add("Document title too long (max 500 characters)")
        }

        if (document.hash.isBlank()) {
            errors.add("Document hash cannot be blank")
        }

        if (document.hash.length != 64 || !document.hash.matches(Regex("[a-fA-F0-9]+"))) {
            errors.add("Invalid document hash format")
        }

        if (document.mime.isBlank()) {
            errors.add("Document MIME type cannot be blank")
        }

        if (document.textBytes.isEmpty()) {
            errors.add("Document content cannot be empty")
        }

        if (document.textBytes.size > 50 * 1024 * 1024) { // 50MB limit
            errors.add("Document content too large (max 50MB)")
        }

        if (document.createdAt <= 0) {
            errors.add("Invalid creation timestamp")
        }

        // Validate JSON
        if (!isValidJson(document.metaJson)) {
            errors.add("Invalid metadata JSON")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    /**
     * Validate embedding data
     */
    fun validateEmbedding(embedding: Embedding): ValidationResult {
        val errors = mutableListOf<String>()

        if (embedding.textHash.isBlank()) {
            errors.add("Text hash cannot be blank")
        }

        if (embedding.textHash.length != 64 || !embedding.textHash.matches(Regex("[a-fA-F0-9]+"))) {
            errors.add("Invalid text hash format")
        }

        if (embedding.vector.isEmpty()) {
            errors.add("Embedding vector cannot be empty")
        }

        if (embedding.dim <= 0) {
            errors.add("Embedding dimension must be positive")
        }

        if (embedding.norm < 0) {
            errors.add("Embedding norm cannot be negative")
        }

        if (embedding.createdAt <= 0) {
            errors.add("Invalid creation timestamp")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    /**
     * Validate RAG chunk data
     */
    fun validateRagChunk(chunk: RagChunk): ValidationResult {
        val errors = mutableListOf<String>()

        if (chunk.docId <= 0) {
            errors.add("Invalid document ID")
        }

        if (chunk.start < 0) {
            errors.add("Start position cannot be negative")
        }

        if (chunk.end <= chunk.start) {
            errors.add("End position must be greater than start position")
        }

        if (chunk.text.isBlank()) {
            errors.add("Chunk text cannot be blank")
        }

        if (chunk.text.length > 10000) {
            errors.add("Chunk text too long (max 10000 characters)")
        }

        if (chunk.tokenCount <= 0) {
            errors.add("Token count must be positive")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    /**
     * Check if string is valid JSON
     */
    private fun isValidJson(json: String): Boolean {
        return try {
            org.json.JSONObject(json)
            true
        } catch (e: Exception) {
            try {
                org.json.JSONArray(json)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * Validation result
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()

    fun isValid(): Boolean = this is Valid

    fun getValidationErrors(): List<String> = when (this) {
        is Valid -> emptyList()
        is Invalid -> errors
    }
}
