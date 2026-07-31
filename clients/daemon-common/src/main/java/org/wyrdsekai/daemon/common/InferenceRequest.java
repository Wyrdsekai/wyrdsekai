package org.wyrdsekai.daemon.common;

import java.util.List;

/**
 * Wire format for inference requests routed via NATS request/reply.
 * Subject: {@code wyrd.inference.request.<nodeId>}
 *
 * A server node's inference routing layer (InferenceRouter) sends this; the
 * daemon processes it and replies with an {@link InferenceResponse}.
 */
public record InferenceRequest(
    String requestId,
    String model,
    List<ChatMessage> messages,
    int maxTokens,
    double temperature
) {
    /** A single message in the chat history. */
    public record ChatMessage(String role, String content) {}
}
