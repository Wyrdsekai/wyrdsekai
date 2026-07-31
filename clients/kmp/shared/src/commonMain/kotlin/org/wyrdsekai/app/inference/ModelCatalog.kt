package org.wyrdsekai.app.inference

/**
 * Built-in catalog of GGUF models available for on-device inference.
 *
 * Models are sourced from HuggingFace and sized for household hardware:
 * - tiny: runs on anything with 2 GB+ RAM
 * - medium: needs 8 GB+ RAM but much higher quality
 */
object ModelCatalog {
    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "qwen3.5-2b-q4",
            name = "Qwen3.5 2B",
            filename = "Qwen3.5-2B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            size = 1_280_000_000L,
            tier = "phone",
            description = "Best for phone. Holds personality, fast inference. Q4 quantization.",
        ),
        ModelInfo(
            id = "qwen3-0.6b-q8",
            name = "Qwen3 0.6B",
            filename = "Qwen3-0.6B-Q8_0.gguf",
            url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            size = 639_000_000L,
            tier = "tiny",
            description = "Fastest. Q8 quantization. Runs on any device with 2GB+ RAM.",
        ),
        ModelInfo(
            id = "qwen3-0.6b-q4",
            name = "Qwen3 0.6B (Q4)",
            filename = "Qwen3-0.6B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            size = 379_000_000L,
            tier = "tiny",
            description = "Smallest Q4. 379MB. Study command layer — fast offline actions.",
        ),
        ModelInfo(
            id = "functiongemma-270m",
            name = "FunctionGemma 270M",
            filename = "functiongemma-270m-it-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/functiongemma-270m-it-GGUF/resolve/main/functiongemma-270m-it-Q4_K_M.gguf",
            size = 242_000_000L,
            tier = "tiny",
            description = "Google's function-calling model. 242MB. Optimized for tool use, not chat.",
        ),
        ModelInfo(
            id = "qwen3-4b-q4",
            name = "Qwen3 4B",
            filename = "Qwen3-4B-Q4_K_M.gguf",
            url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            size = 2_500_000_000L,
            tier = "medium",
            description = "Strong quality. Q4 quantization. Needs 8GB+ RAM.",
        ),
    )

    fun findById(id: String): ModelInfo? = models.find { it.id == id }
}
