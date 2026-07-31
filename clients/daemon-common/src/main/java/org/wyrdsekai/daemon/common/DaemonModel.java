package org.wyrdsekai.daemon.common;

/**
 * Wire-compatible with {@code InferenceGossip.AvailableModel} on the server.
 * Jackson field names match exactly: modelId, tier, endpoint, maxConcurrent, activeLeases.
 *
 * @param modelId       model identifier (e.g. "qwen3-4b-q4")
 * @param tier          "tiny", "small", "medium", or "large"
 * @param endpoint      HTTP URL for /v1/chat/completions (e.g. "http://198.51.100.42:8080")
 * @param maxConcurrent maximum concurrent requests (usually 1 for phones)
 * @param activeLeases  currently active inference requests
 */
public record DaemonModel(
    String modelId,
    String tier,
    String endpoint,
    int maxConcurrent,
    int activeLeases
) {}
