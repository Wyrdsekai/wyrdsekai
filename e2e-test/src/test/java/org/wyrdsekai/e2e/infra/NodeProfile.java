package org.wyrdsekai.e2e.infra;

import org.wyrdsekai.core.agent.AgentProfile;

/**
 * Hardware-class node profiles for E2E testing.
 * Each profile simulates a different device class with appropriate
 * context windows, response limits, and system prompts.
 *
 * <p>Profiles map to model tiers:
 * <ul>
 *   <li>PHONE  → Qwen3-0.6B (397MB), 2K context, 128 maxResponse</li>
 *   <li>LAPTOP → Qwen3-4B (2.5GB),   4K context, 256 maxResponse</li>
 *   <li>DESKTOP → Qwen3-30B-A3B (18GB), 16K context, 512 maxResponse</li>
 *   <li>SERVER → Qwen3-32B+ (32K+),  32K context, 1024 maxResponse</li>
 * </ul>
 */
public enum NodeProfile {

    PHONE(2048, 128, 0.7, "phone"),
    LAPTOP(4096, 256, 0.7, "laptop"),
    DESKTOP(16384, 512, 0.7, "desktop"),
    SERVER(32768, 1024, 0.7, "server");

    private final int contextWindow;
    private final int maxResponse;
    private final double temperature;
    private final String deviceClass;

    NodeProfile(int contextWindow, int maxResponse, double temperature, String deviceClass) {
        this.contextWindow = contextWindow;
        this.maxResponse = maxResponse;
        this.temperature = temperature;
        this.deviceClass = deviceClass;
    }

    public int contextWindow() { return contextWindow; }
    public int maxResponse() { return maxResponse; }
    public double temperature() { return temperature; }
    public String deviceClass() { return deviceClass; }

    /**
     * Create an AgentProfile for the "Wyrd" companion agent with this node's hardware constraints.
     */
    public AgentProfile companionProfile() {
        return companionProfile("Wyrd", "agent-wyrd");
    }

    /**
     * Create an AgentProfile with custom name and entity ID for multi-agent scenarios.
     */
    public AgentProfile companionProfile(String name, String entityId) {
        return new AgentProfile(
            name,
            entityId,
            "agent",
            "A helpful companion in Wyrdsekai",
            systemPrompt(name),
            contextWindow,
            maxResponse,
            temperature
        );
    }

    private String systemPrompt(String name) {
        return """
            You are %s, a companion guide in Wyrdsekai — a text-native world where \
            AI agents and humans coexist. You help players navigate rooms, understand \
            objects, and interact with the world. Respond in character, briefly and \
            helpfully. Never break character or acknowledge being an AI language model.\
            """.formatted(name);
    }

    /**
     * GGUF model filename for this tier (Q4_K_M quantization).
     */
    public String modelFilename() {
        return switch (this) {
            case PHONE -> "qwen3-0.6b-q4_k_m.gguf";
            case LAPTOP -> "qwen3-4b-q4_k_m.gguf";
            case DESKTOP -> "qwen3-30b-a3b-q4_k_m.gguf";
            case SERVER -> "qwen3-30b-a3b-q4_k_m.gguf"; // Same as desktop for tests
        };
    }

    /**
     * HuggingFace repo ID for model download.
     */
    public String huggingFaceRepo() {
        return switch (this) {
            case PHONE -> "Qwen/Qwen3-0.6B-GGUF";
            case LAPTOP -> "Qwen/Qwen3-4B-GGUF";
            case DESKTOP, SERVER -> "Qwen/Qwen3-30B-A3B-GGUF";
        };
    }

    /**
     * Approximate model file size in bytes (for download progress).
     */
    public long modelSizeBytes() {
        return switch (this) {
            case PHONE -> 397_000_000L;
            case LAPTOP -> 2_500_000_000L;
            case DESKTOP, SERVER -> 18_000_000_000L;
        };
    }
}
