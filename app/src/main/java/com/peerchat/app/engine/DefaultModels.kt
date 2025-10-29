package com.peerchat.app.engine

data class DefaultModel(
    val id: String,
    val name: String,
    val description: String,
    val cardUrl: String,
    val downloadUrl: String,
    val suggestedFileName: String,
    val isDefault: Boolean = true,
)

object DefaultModels {
    val list: List<DefaultModel> = listOf(
        DefaultModel(
            id = "lfm2_8b_q4km",
            name = "Liquid LFM-2 8B Q4_K_M",
            description = "Versatile 8B general model (Liquid AI).",
            cardUrl = "https://huggingface.co/bartowski/LiquidAI_LFM2-8B-A1B-GGUF",
            downloadUrl = "https://huggingface.co/bartowski/LiquidAI_LFM2-8B-A1B-GGUF/resolve/main/LiquidAI_LFM2-8B-A1B-Q4_K_M.gguf?download=1",
            suggestedFileName = "LiquidAI_LFM2-8B-A1B-Q4_K_M.gguf"
        ),
        DefaultModel(
            id = "granite_4_tiny_q4km",
            name = "Granite 4.0 Tiny Q4_K_M",
            description = "IBM Granite Tiny instruction-tuned model.",
            cardUrl = "https://huggingface.co/bartowski/ibm-granite_granite-4.0-h-tiny-GGUF",
            downloadUrl = "https://huggingface.co/bartowski/ibm-granite_granite-4.0-h-tiny-GGUF/resolve/main/ibm-granite_granite-4.0-h-tiny-Q4_K_M.gguf?download=1",
            suggestedFileName = "ibm-granite_granite-4.0-h-tiny-Q4_K_M.gguf"
        ),
        DefaultModel(
            id = "internvl_35_4b_q4km",
            name = "InternVL 3.5 4B Q4_K_M",
            description = "Multimodal-capable InternVL 3.5 distilled for 4B.",
            cardUrl = "https://huggingface.co/bartowski/OpenGVLab_InternVL3_5-4B-GGUF",
            downloadUrl = "https://huggingface.co/bartowski/OpenGVLab_InternVL3_5-4B-GGUF/resolve/main/OpenGVLab_InternVL3_5-4B-Q4_K_M.gguf?download=1",
            suggestedFileName = "OpenGVLab_InternVL3_5-4B-Q4_K_M.gguf"
        ),
        DefaultModel(
            id = "qwen3_4b_thinking_q4km",
            name = "Qwen 3 4B Thinking Q4_K_M",
            description = "Reasoning-optimized Qwen 3 4B.",
            cardUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Thinking-2507-GGUF",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Thinking-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Thinking-2507-Q4_K_M.gguf?download=1",
            suggestedFileName = "Qwen_Qwen3-4B-Thinking-2507-Q4_K_M.gguf"
        ),
        DefaultModel(
            id = "qwen3_06b_q4km",
            name = "Qwen 3 0.6B Q4_K_M",
            description = "Lightweight Qwen 3 0.6B for quick tests.",
            cardUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf?download=1",
            suggestedFileName = "Qwen_Qwen3-0.6B-Q4_K_M.gguf"
        )
    )
}
