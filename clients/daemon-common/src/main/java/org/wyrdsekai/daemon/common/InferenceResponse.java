package org.wyrdsekai.daemon.common;

/**
 * Wire format for inference responses sent back via NATS reply.
 */
public record InferenceResponse(
    String requestId,
    String content,
    int promptTokens,
    int completionTokens,
    String error
) {
    public static InferenceResponse ok(String requestId, String content,
                                        int promptTokens, int completionTokens) {
        return new InferenceResponse(requestId, content, promptTokens, completionTokens, null);
    }

    public static InferenceResponse error(String requestId, String errorMsg) {
        return new InferenceResponse(requestId, null, 0, 0, errorMsg);
    }

    /** Check if this response represents an error. Named to avoid Jackson's "is" prefix convention. */
    public boolean hasError() {
        return error != null;
    }
}
