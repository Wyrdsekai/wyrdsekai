package org.wyrdsekai.between.inference;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire protocol for cross-zone inference over NATS relay.
 *
 * <p>Why NATS instead of direct HTTP: the relay already scopes household traffic
 * with auth; metering is native; the operator doesn't become a generic HTTP proxy.
 * Streaming is supported via a subject-per-stream pattern so each token is a
 * separate publish on {@link #streamSubject(String)}.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Requestor subscribes to {@code federation.inference.stream.{streamId}}
 *       BEFORE publishing the request (avoid race).</li>
 *   <li>Requestor publishes {@link Request} to
 *       {@code federation.inference.{targetZone}.complete}.</li>
 *   <li>Provider runs local inference and publishes {@link StreamChunk} messages
 *       on the stream subject — one per token (when streaming) followed by a
 *       terminal chunk with {@code done=true}.</li>
 *   <li>Requestor reassembles the response, unsubscribes, completes its future.</li>
 * </ol>
 * </p>
 */
public final class NatsInferenceProtocol {

    public record Request(
        @JsonProperty("streamId") String streamId,
        @JsonProperty("sourceZone") String sourceZone,
        @JsonProperty("agentId") String agentId,          // for metering attribution
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<Message> messages,
        @JsonProperty("maxTokens") Integer maxTokens,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("stream") boolean stream,
        @JsonProperty("sourceNode") String sourceNode,    // requester node id — household-trust check
        // Audit F7 (pre-OSS): the household auto-share exemption grants UNLIMITED
        // inference, and sourceNode/sourceZone were self-asserted — a stranger who
        // guessed a household node id got free unmetered inference. These two
        // fields bind the claim to the node's Ed25519 key: `sig` is the base64
        // signature over householdSigningData(streamId, sourceZone, sourceNode,
        // authTs); the provider verifies it against the public key it has on file
        // for sourceNode (HouseholdStore) before honouring the exemption. Absent /
        // invalid → no exemption, fall back to the bilateral quota. authTs bounds
        // replay to a short freshness window (streamId dedup covers the rest).
        @JsonProperty("sig") String sig,
        @JsonProperty("authTs") Long authTs
    ) {
        /** Back-compat — no sourceNode (older clients / non-household callers). */
        public Request(String streamId, String sourceZone, String agentId, String model,
                       List<Message> messages, Integer maxTokens, Double temperature, boolean stream) {
            this(streamId, sourceZone, agentId, model, messages, maxTokens, temperature, stream, null, null, null);
        }

        /** Back-compat — sourceNode but no household signature (exemption unavailable). */
        public Request(String streamId, String sourceZone, String agentId, String model,
                       List<Message> messages, Integer maxTokens, Double temperature, boolean stream,
                       String sourceNode) {
            this(streamId, sourceZone, agentId, model, messages, maxTokens, temperature, stream, sourceNode, null, null);
        }
    }

    /**
     * Canonical bytes a household requester signs (and the provider verifies) to
     * prove the {@code sourceNode}/{@code sourceZone} claim behind the
     * auto-share exemption. Order-fixed, {@code |}-joined, UTF-8. Audit F7.
     */
    public static byte[] householdSigningData(String streamId, String sourceZone,
                                              String sourceNode, long authTs) {
        var s = (streamId == null ? "" : streamId) + "|"
            + (sourceZone == null ? "" : sourceZone) + "|"
            + (sourceNode == null ? "" : sourceNode) + "|"
            + authTs;
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public record Message(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content
    ) {}

    /**
     * One chunk on the stream subject. For streaming, each token is a chunk with
     * {@code done=false} and {@code token} set. The final chunk has {@code done=true}
     * and carries usage stats (or an error). For non-streaming, only one chunk is sent,
     * with {@code done=true} and the full response in {@code fullContent}.
     */
    public record StreamChunk(
        @JsonProperty("streamId") String streamId,
        @JsonProperty("token") String token,
        @JsonProperty("fullContent") String fullContent,  // non-streaming: complete text
        @JsonProperty("done") boolean done,
        @JsonProperty("promptTokens") Integer promptTokens,
        @JsonProperty("completionTokens") Integer completionTokens,
        @JsonProperty("finishReason") String finishReason,
        @JsonProperty("error") String error
    ) {}

    /** Subject the provider of {@code targetZone} subscribes to for incoming requests. */
    public static String requestSubject(String targetZone) {
        return "federation.inference." + targetZone + ".complete";
    }

    /** Subject a single request's token stream flows on. Requestor subscribes; provider publishes. */
    public static String streamSubject(String streamId) {
        return "federation.inference.stream." + streamId;
    }

    private NatsInferenceProtocol() {}
}
