package org.wyrdsekai.core.substrate.training;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Wire protocol for {@link PeerDelegatedExecutor}.
 *
 * <p>The submitter publishes a {@link Request} on
 * {@code wyrdsekai.training.peer.<peerNodeId>.request}; the peer's
 * {@link org.wyrdsekai.core.substrate.training.TrainingPeerService}
 * subscribes to that subject, runs {@link LocalSerialExecutor} on the
 * received corpus, and replies with a {@link Response} on the
 * NATS-auto-generated reply inbox.</p>
 *
 * <p>On success, the peer follows up with a sequence of
 * {@link AdapterChunk} messages on
 * {@code wyrdsekai.training.peer.<peerNodeId>.adapter.<requestId>.chunk.<seq>}
 * — these carry the binary adapter bytes (typically 21MB GGUF in
 * ~24 chunks at 1MB each). The submitter reassembles in chunk-seq
 * order, verifies the SHA-256, and writes to its local adapter root.</p>
 *
 * <p>Why chunked NATS messages instead of JetStream Object Store: avoids
 * adding a new persistence dependency. NATS default max payload is 1MB
 * (configurable up to 8MB on the server) and 25 round-trips for a 21MB
 * adapter is fine on a household mesh.</p>
 */
public final class PeerTrainingProtocol {

    private PeerTrainingProtocol() {}

    /** Default chunk size — 512KB raw. Base64 expands 4/3 → ~683KB encoded,
     *  plus JSON envelope overhead, leaving comfortable headroom under
     *  NATS' default 1MB max-payload. (768KB raw → ~1024KB encoded which
     *  trips the limit; fixed 2026-04-25.) */
    public static final int DEFAULT_CHUNK_BYTES = 512 * 1024;

    /** NATS subject prefix for incoming training requests on a peer. */
    public static String requestSubject(String peerNodeId) {
        return "wyrdsekai.training.peer." + peerNodeId + ".request";
    }

    /** NATS subject used to ship adapter chunks back. */
    public static String adapterChunkSubject(String peerNodeId, String requestId, int seq) {
        return "wyrdsekai.training.peer." + peerNodeId
            + ".adapter." + requestId + ".chunk." + seq;
    }

    /** Wildcard for the submitter to subscribe to all chunks for one request. */
    public static String adapterChunkWildcard(String peerNodeId, String requestId) {
        return "wyrdsekai.training.peer." + peerNodeId
            + ".adapter." + requestId + ".chunk.*";
    }

    // ── Records ─────────────────────────────────────────────────────────

    /**
     * Submitter → peer. Carries the corpus inline (small, ~20KB). The peer
     * trains using its own model path resolution — the {@code modelHint}
     * is informational; if the peer can't satisfy it, it returns a
     * failure {@link Response}.
     *
     * @param requestId   submitter-generated UUID; echoed in the response
     * @param submitterNodeId node identity of the submitter (for logging)
     * @param agentId     companion DID (peer trains a fresh adapter for this agent)
     * @param agentName   display name (logging)
     * @param modelHint   suggested base model path (peer may override)
     * @param corpus      conversation turns — list of {system,user,assistant} maps
     * @param maxIters    optional training iteration cap
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
        String requestId,
        String submitterNodeId,
        String agentId,
        String agentName,
        String modelHint,
        List<Map<String, String>> corpus,
        Integer maxIters
    ) {}

    /**
     * Peer → submitter (single message on reply inbox). Indicates whether
     * training succeeded and how many adapter chunks to expect.
     *
     * @param requestId          echo of {@link Request#requestId}
     * @param status             "ok" | "skip" | "fail"
     * @param detail             human-readable; for "fail" this is the reason
     * @param adapterChunkCount  number of chunks the peer will publish
     *                           (only set when status="ok")
     * @param adapterTotalBytes  total adapter size in bytes (sanity check)
     * @param adapterSha256      hex SHA-256 of the reassembled adapter
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
        String requestId,
        String status,
        String detail,
        Integer adapterChunkCount,
        Long adapterTotalBytes,
        String adapterSha256
    ) {
        public boolean ok() { return "ok".equals(status); }
    }

    /**
     * One slice of the adapter binary. Sent on the chunk subject in
     * {@code seq} order. The submitter waits until {@code adapterChunkCount}
     * unique seqs are received before assembling.
     *
     * @param requestId echo
     * @param seq       0-based sequence number
     * @param data      raw bytes of this slice (Jackson encodes as base64)
     */
    public record AdapterChunk(
        String requestId,
        int seq,
        byte[] data
    ) {}

    // ── Jackson serde ───────────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static byte[] encode(Object record) {
        try {
            return MAPPER.writeValueAsBytes(record);
        } catch (Exception e) {
            throw new RuntimeException("encode failed: " + e.getMessage(), e);
        }
    }

    public static <T> T decode(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (Exception e) {
            throw new RuntimeException("decode failed for "
                + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
