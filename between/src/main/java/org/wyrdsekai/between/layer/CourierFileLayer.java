package org.wyrdsekai.between.layer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.net.NetworkCapability;
import org.wyrdsekai.core.net.NetworkWiring;

/**
 * the courier satchel's household-bus transport.
 *
 * <p>Transfers a file to a household-ENROLLED peer over NATS request/reply —
 * no ssh, no host keys; the trust boundary is the roster. Three-step chunked
 * protocol on the {@code courier.copy} topic ({@code op=begin/chunk/commit}),
 * sha256-verified end to end, atomic move into place on commit.</p>
 *
 * <p><b>Sender authentication:</b> every request envelope's Ed25519 signature
 * is verified against the {@link HouseholdStore} roster entry for the source
 * node. This matters because the relay bridge forwards {@code between.{zone}.>}
 * wholesale — "it arrived on the bus" includes relay-attached peers, and a
 * disk write must be provable to come from an enrolled NODE, not merely a
 * bus participant. Unknown or unverifiable senders are refused.</p>
 *
 * <p><b>Landing-path policy (receiver-side):</b> a RELATIVE {@code remotePath}
 * lands under the courier inbox ({@code $WYRDSEKAI_DATA_DIR/courier/…});
 * traversal out of the inbox is rejected. An ABSOLUTE path is honored only
 * when the receiving steward has set {@code wyrdsekai.net.courier.allow-absolute
 * = true} — by default a remote node (even an enrolled one) cannot overwrite
 * arbitrary files on this machine. The commit reply carries the path the file
 * ACTUALLY landed at, so the sending agent narrates honestly.</p>
 */
public final class CourierFileLayer implements NetworkCapability.HouseholdTransport {

    private static final Logger log = LoggerFactory.getLogger(CourierFileLayer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String LAYER = "courier";
    static final String TOPIC = "copy";
    private static final int CHUNK_BYTES = 256 * 1024;
    private static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;
    private static final long TRANSFER_EXPIRE_MS = 10 * 60 * 1000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final NatsBridge nats;
    private final String localNodeId;
    private final Function<String, Optional<byte[]>> rosterKey;
    private final Path dataDir;
    private final boolean allowAbsolute;
    private final long maxBytes;

    private final ConcurrentHashMap<String, Transfer> inbound = new ConcurrentHashMap<>();

    CourierFileLayer(NatsBridge nats, String localNodeId,
                     Function<String, Optional<byte[]>> rosterKey,
                     Path dataDir, boolean allowAbsolute, long maxBytes) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.rosterKey = rosterKey;
        this.dataDir = dataDir;
        this.allowAbsolute = allowAbsolute;
        this.maxBytes = maxBytes;
    }

    /**
     * Production assembly: read the courier config knobs, subscribe the
     * receiver, and hand the transport to {@link NetworkWiring} so the
     * courier satchel's {@code world.net.household_copy} goes live.
     */
    public static CourierFileLayer start(NatsBridge nats, String localNodeId,
                                          HouseholdStore roster) {
        boolean allowAbsolute = false;
        long maxBytes = DEFAULT_MAX_BYTES;
        try {
            var config = ConfigFactory.load();
            if (config.hasPath("wyrdsekai.net.courier.allow-absolute")) {
                allowAbsolute = config.getBoolean("wyrdsekai.net.courier.allow-absolute");
            }
            if (config.hasPath("wyrdsekai.net.courier.max-bytes")) {
                maxBytes = config.getLong("wyrdsekai.net.courier.max-bytes");
            }
        } catch (Exception e) {
            log.warn("[Courier] config read failed — defaults apply: {}", e.getMessage());
        }
        var layer = new CourierFileLayer(
            nats, localNodeId,
            nodeId -> roster.get(nodeId).map(HouseholdStore.Row::publicKey),
            SystemPaths.dataDir(), allowAbsolute, maxBytes);
        layer.subscribe();
        NetworkWiring.setHouseholdTransport(layer);
        return layer;
    }

    /** Subscribe the receiver side (test-visible; start() calls this). */
    void subscribe() {
        nats.subscribeRequestEnvelope(LAYER, TOPIC, this::onRequest);
    }

    // ── sender ──────────────────────────────────────────────────────────

    @Override
    public Result copyTo(String nodeId, String localPath, String remotePath) {
        if (nodeId == null || nodeId.isBlank()) return Result.fail("no target node");
        var src = Path.of(localPath == null ? "" : localPath);
        if (!Files.isRegularFile(src)) {
            return Result.fail("local file not found: " + localPath);
        }
        byte[] bytes;
        try {
            long size = Files.size(src);
            if (size > maxBytes) {
                return Result.fail("file is " + size + " bytes — over the courier cap of "
                    + maxBytes + " (wyrdsekai.net.courier.max-bytes)");
            }
            bytes = Files.readAllBytes(src);
        } catch (IOException e) {
            return Result.fail("could not read " + localPath + ": " + e.getMessage());
        }
        var transferId = UUID.randomUUID().toString();
        int totalChunks = Math.max(1, (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);

        var begin = MAPPER.createObjectNode();
        begin.put("op", "begin");
        begin.put("node", nodeId);
        begin.put("transferId", transferId);
        begin.put("remotePath", remotePath == null ? "" : remotePath);
        begin.put("size", bytes.length);
        begin.put("totalChunks", totalChunks);
        begin.put("sha256", sha256Hex(bytes));
        var beginReply = call(begin);
        if (beginReply == null || !beginReply.path("ok").asBoolean(false)) {
            return Result.fail(errorOf(beginReply, "peer did not accept the transfer"));
        }

        for (int i = 0; i < totalChunks; i++) {
            int from = i * CHUNK_BYTES;
            int to = Math.min(bytes.length, from + CHUNK_BYTES);
            var chunk = MAPPER.createObjectNode();
            chunk.put("op", "chunk");
            chunk.put("node", nodeId);
            chunk.put("transferId", transferId);
            chunk.put("seq", i);
            chunk.put("dataB64", Base64.getEncoder()
                .encodeToString(Arrays.copyOfRange(bytes, from, to)));
            var reply = call(chunk);
            if (reply == null || !reply.path("ok").asBoolean(false)) {
                return Result.fail(errorOf(reply, "chunk " + i + " refused"));
            }
        }

        var commit = MAPPER.createObjectNode();
        commit.put("op", "commit");
        commit.put("node", nodeId);
        commit.put("transferId", transferId);
        var commitReply = call(commit);
        if (commitReply == null || !commitReply.path("ok").asBoolean(false)) {
            return Result.fail(errorOf(commitReply, "peer refused the commit"));
        }
        var landed = commitReply.path("landedPath").asText(null);
        log.info("[Courier] sent {} → {}:{} ({} bytes)", localPath, nodeId, landed, bytes.length);
        return Result.success(landed);
    }

    private JsonNode call(ObjectNode payload) {
        try {
            return nats.request(LAYER, TOPIC, payload, REQUEST_TIMEOUT)
                .get(REQUEST_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Courier] request failed: {}", e.getMessage());
            return null;
        }
    }

    private static String errorOf(JsonNode reply, String fallback) {
        if (reply == null) return fallback + " (no reply — is the peer online?)";
        var err = reply.path("error").asText("");
        return err.isBlank() ? fallback : err;
    }

    // ── receiver ────────────────────────────────────────────────────────

    private static final class Transfer {
        final String srcNode;
        final Path tmp;
        final Path landing;
        final int totalChunks;
        final long size;
        final String sha256;
        int received;
        long touchedAt = System.currentTimeMillis();

        Transfer(String srcNode, Path tmp, Path landing, int totalChunks, long size, String sha256) {
            this.srcNode = srcNode;
            this.tmp = tmp;
            this.landing = landing;
            this.totalChunks = totalChunks;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private void onRequest(BetweenEnvelope env, String replySubject) {
        var payload = env.payload();
        var target = payload.path("node").asText("");
        if (!target.equalsIgnoreCase(localNodeId)) return; // someone else's transfer

        sweepExpired();

        // Roster authentication — the ONE hard gate before any disk effect.
        var pk = rosterKey.apply(env.src());
        if (pk.isEmpty()) {
            refuse(replySubject, "sender '" + env.src() + "' is not an enrolled household node");
            return;
        }
        boolean verified;
        try {
            verified = env.verify(pk.get());
        } catch (Exception e) {
            verified = false;
        }
        if (!verified) {
            refuse(replySubject, "sender signature did not verify against the roster");
            return;
        }

        var op = payload.path("op").asText("");
        try {
            switch (op) {
                case "begin" -> onBegin(env.src(), payload, replySubject);
                case "chunk" -> onChunk(env.src(), payload, replySubject);
                case "commit" -> onCommit(env.src(), payload, replySubject);
                default -> refuse(replySubject, "unknown courier op '" + op + "'");
            }
        } catch (Exception e) {
            log.warn("[Courier] {} failed: {}", op, e.getMessage());
            refuse(replySubject, e.getMessage() == null ? "transfer error" : e.getMessage());
        }
    }

    private void onBegin(String srcNode, JsonNode payload, String replySubject) throws IOException {
        long size = payload.path("size").asLong(-1);
        int totalChunks = payload.path("totalChunks").asInt(-1);
        var sha256 = payload.path("sha256").asText("");
        var transferId = payload.path("transferId").asText("");
        if (transferId.isBlank() || size < 0 || totalChunks < 1 || sha256.isBlank()) {
            refuse(replySubject, "malformed begin");
            return;
        }
        if (size > maxBytes) {
            refuse(replySubject, "file exceeds this node's courier cap (" + maxBytes + " bytes)");
            return;
        }
        if (totalChunks > (maxBytes / CHUNK_BYTES) + 2) {
            refuse(replySubject, "chunk count implausible for the declared size");
            return;
        }
        Path landing;
        try {
            landing = resolveLanding(dataDir, payload.path("remotePath").asText(""), allowAbsolute);
        } catch (IllegalArgumentException e) {
            refuse(replySubject, e.getMessage());
            return;
        }
        Files.createDirectories(dataDir.resolve("courier").resolve(".incoming"));
        var tmp = dataDir.resolve("courier").resolve(".incoming")
            .resolve(sanitizeId(transferId) + ".part");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        inbound.put(transferId, new Transfer(srcNode, tmp, landing, totalChunks, size, sha256));
        var ok = MAPPER.createObjectNode();
        ok.put("ok", true);
        ok.put("transferId", transferId);
        nats.respond(replySubject, ok);
    }

    private void onChunk(String srcNode, JsonNode payload, String replySubject) throws IOException {
        var transferId = payload.path("transferId").asText("");
        var t = inbound.get(transferId);
        if (t == null) {
            refuse(replySubject, "no such transfer (expired or never begun)");
            return;
        }
        if (!t.srcNode.equals(srcNode)) {
            refuse(replySubject, "transfer belongs to a different sender");
            return;
        }
        int seq = payload.path("seq").asInt(-1);
        synchronized (t) {
            if (seq != t.received) {
                refuse(replySubject, "out-of-order chunk " + seq + " (expected " + t.received + ")");
                return;
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(payload.path("dataB64").asText(""));
            } catch (IllegalArgumentException e) {
                refuse(replySubject, "chunk " + seq + " is not valid base64");
                return;
            }
            if (Files.size(t.tmp) + data.length > t.size) {
                inbound.remove(transferId);
                Files.deleteIfExists(t.tmp);
                refuse(replySubject, "transfer exceeds its declared size");
                return;
            }
            Files.write(t.tmp, data, StandardOpenOption.APPEND);
            t.received++;
            t.touchedAt = System.currentTimeMillis();
        }
        var ok = MAPPER.createObjectNode();
        ok.put("ok", true);
        ok.put("seq", seq);
        nats.respond(replySubject, ok);
    }

    private void onCommit(String srcNode, JsonNode payload, String replySubject) throws IOException {
        var transferId = payload.path("transferId").asText("");
        var t = inbound.remove(transferId);
        if (t == null) {
            refuse(replySubject, "no such transfer (expired or never begun)");
            return;
        }
        if (!t.srcNode.equals(srcNode)) {
            Files.deleteIfExists(t.tmp);
            refuse(replySubject, "transfer belongs to a different sender");
            return;
        }
        synchronized (t) {
            if (t.received != t.totalChunks || Files.size(t.tmp) != t.size) {
                Files.deleteIfExists(t.tmp);
                refuse(replySubject, "incomplete transfer (" + t.received + "/" + t.totalChunks
                    + " chunks)");
                return;
            }
            var actual = sha256Hex(Files.readAllBytes(t.tmp));
            if (!actual.equalsIgnoreCase(t.sha256)) {
                Files.deleteIfExists(t.tmp);
                refuse(replySubject, "sha256 mismatch — transfer corrupted");
                return;
            }
            Files.createDirectories(t.landing.getParent());
            Files.move(t.tmp, t.landing, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("[Courier] received {} bytes from {} → {}", t.size, srcNode, t.landing);
        var ok = MAPPER.createObjectNode();
        ok.put("ok", true);
        ok.put("landedPath", t.landing.toString());
        nats.respond(replySubject, ok);
    }

    private void refuse(String replySubject, String error) {
        var node = MAPPER.createObjectNode();
        node.put("ok", false);
        node.put("error", error);
        nats.respond(replySubject, node);
    }

    private void sweepExpired() {
        var now = System.currentTimeMillis();
        for (var e : inbound.entrySet()) {
            if (now - e.getValue().touchedAt > TRANSFER_EXPIRE_MS) {
                inbound.remove(e.getKey());
                try {
                    Files.deleteIfExists(e.getValue().tmp);
                } catch (IOException ignored) { /* best effort */ }
            }
        }
    }

    // ── landing-path policy (static — unit-tested) ─────────────────────

    /**
     * Resolve where an incoming file may land. Relative paths live under the
     * courier inbox and may not traverse out of it; absolute paths need the
     * receiving steward's explicit {@code allow-absolute} opt-in.
     *
     * @throws IllegalArgumentException when the path is refused — the message
     *         is sent back to the sender verbatim, so it names the remedy.
     */
    static Path resolveLanding(Path dataDir, String remotePath, boolean allowAbsolute) {
        if (remotePath == null || remotePath.isBlank()) {
            throw new IllegalArgumentException("no destination path given");
        }
        var p = Path.of(remotePath);
        if (p.isAbsolute()) {
            if (!allowAbsolute) {
                throw new IllegalArgumentException("absolute destination paths are closed on the "
                    + "receiving node — send a relative path (lands in its courier inbox), or "
                    + "have its steward set wyrdsekai.net.courier.allow-absolute=true");
            }
            return p.normalize();
        }
        var inbox = dataDir.resolve("courier").toAbsolutePath().normalize();
        var landing = inbox.resolve(remotePath).normalize();
        if (!landing.startsWith(inbox)) {
            throw new IllegalArgumentException("destination path escapes the courier inbox");
        }
        if (landing.startsWith(inbox.resolve(".incoming"))) {
            throw new IllegalArgumentException("destination path may not target the staging area");
        }
        return landing;
    }

    private static String sanitizeId(String id) {
        return id.replaceAll("[^A-Za-z0-9-]", "_").toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
