package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.crypto.ShamirSecretSharing;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * The Safe — topology-gated secret keeper (§74.11).
 * Stores secrets using threshold secret sharing (Shamir's SS).
 * Three-layer security:
 *   Layer 1 (Topology): peerwise latency gates — physics-based, unforgeable
 *   Layer 2 (Cryptography): K-of-N threshold secret sharing
 *   Layer 3 (Behavior): vitality-gated (Confidence + Alignment)
 *
 * <p> W13 — production single-node mode: {@link #local()}
 * exposes a singleton Safe whose credential slots (K=N=1 shares, see
 * {@link #storeSlot}/{@link #readSlot}) persist across restarts in an
 * encrypted-at-rest 600-mode file ({@code dataDir/credentials.safe}). The
 * file key is derived from the node identity's Ed25519 seed (Main passes it
 * to {@link #initLocal}); when no key material is available the file falls
 * back to plaintext-at-600 — honest local mode, the filesystem permission is
 * then the only at-rest protection, and boot logs say so loudly.</p>
 */
public class TheSafe {

    private static final Logger log = LoggerFactory.getLogger(TheSafe.class);

    /** A stored secret with its share metadata and topology requirements. */
    public record StoredSecret(
        String secretId,
        String owner,
        int threshold,
        int totalShares,
        List<ShamirSecretSharing.Share> shares,
        double requiredConfidence,
        double requiredAlignment,
        double maxLatencyMs,              // 0.0 = no topology gate
        List<String> shareNodeAssignments // parallel to shares; null = no node restrictions
    ) {
        /** Backward-compatible constructor without topology requirements. */
        public StoredSecret(String secretId, String owner, int threshold, int totalShares,
                            List<ShamirSecretSharing.Share> shares,
                            double requiredConfidence, double requiredAlignment) {
            this(secretId, owner, threshold, totalShares, shares,
                 requiredConfidence, requiredAlignment, 0.0, null);
        }
    }

    private final Map<String, StoredSecret> secrets = new LinkedHashMap<>();

    /**
     * Store a secret, splitting it into shares (no topology gate).
     */
    public StoredSecret store(String secretId, byte[] secret, String owner,
                               int threshold, int totalShares,
                               double requiredConfidence, double requiredAlignment) {
        var shares = ShamirSecretSharing.split(secret, totalShares, threshold);
        var stored = new StoredSecret(secretId, owner, threshold, totalShares,
            shares, requiredConfidence, requiredAlignment);
        secrets.put(secretId, stored);
        return stored;
    }

    /**
     * Store a secret with topology-gated access (§74.11).
     * Each share is assigned to a specific node. Retrieval requires all share-holding
     * nodes to be connected with latency below maxLatencyMs.
     *
     * @param shareNodeAssignments node IDs assigned to each share (parallel to shares list)
     * @param maxLatencyMs         maximum allowed latency to any share-holding node
     */
    public StoredSecret store(String secretId, byte[] secret, String owner,
                               int threshold, int totalShares,
                               double requiredConfidence, double requiredAlignment,
                               List<String> shareNodeAssignments, double maxLatencyMs) {
        var shares = ShamirSecretSharing.split(secret, totalShares, threshold);
        var stored = new StoredSecret(secretId, owner, threshold, totalShares,
            shares, requiredConfidence, requiredAlignment,
            maxLatencyMs, List.copyOf(shareNodeAssignments));
        secrets.put(secretId, stored);
        return stored;
    }

    /**
     * Retrieve a secret (no topology check).
     * Must provide at least `threshold` valid share indices and meet vitality requirements.
     */
    public Optional<byte[]> retrieve(String secretId, List<Integer> shareIndices,
                                      double entityConfidence, double entityAlignment) {
        return retrieve(secretId, shareIndices, entityConfidence, entityAlignment, Map.of());
    }

    /**
     * Retrieve a secret with topology validation (§74.11).
     * Enforces three-layer security:
     *   1. Topology gate: each requested share's assigned node must be connected
     *      with latency ≤ maxLatencyMs (physics-based, unforgeable)
     *   2. Threshold gate: at least K valid shares required
     *   3. Vitality gate: entity Confidence and Alignment above thresholds
     *
     * @param peerLatencies current latency (ms) per connected node ID
     *                      (sourced from TopologyRegister at call site)
     */
    public Optional<byte[]> retrieve(String secretId, List<Integer> shareIndices,
                                      double entityConfidence, double entityAlignment,
                                      Map<String, Double> peerLatencies) {
        var stored = secrets.get(secretId);
        if (stored == null) return Optional.empty();

        // Layer 3: Vitality gate
        if (entityConfidence < stored.requiredConfidence()
                || entityAlignment < stored.requiredAlignment()) {
            return Optional.empty();
        }

        // Layer 1: Topology gate — check latency for each share's assigned node
        if (stored.maxLatencyMs() > 0 && stored.shareNodeAssignments() != null) {
            for (int idx : shareIndices) {
                if (idx >= 0 && idx < stored.shareNodeAssignments().size()) {
                    var nodeId = stored.shareNodeAssignments().get(idx);
                    if (nodeId != null && !nodeId.isEmpty()) {
                        var latency = peerLatencies.get(nodeId);
                        if (latency == null) {
                            return Optional.empty(); // Node not connected
                        }
                        if (latency > stored.maxLatencyMs()) {
                            return Optional.empty(); // Latency too high
                        }
                    }
                }
            }
        }

        // Layer 2: Threshold gate
        if (shareIndices.size() < stored.threshold()) {
            return Optional.empty();
        }

        // Collect shares by index
        var selectedShares = new ArrayList<ShamirSecretSharing.Share>();
        for (int idx : shareIndices) {
            if (idx >= 0 && idx < stored.shares().size()) {
                selectedShares.add(stored.shares().get(idx));
            }
        }

        if (selectedShares.size() < stored.threshold()) {
            return Optional.empty();
        }

        // Take only threshold number of shares
        var reconstructionShares = selectedShares.subList(0,
            Math.min(selectedShares.size(), stored.threshold()));

        try {
            return Optional.of(ShamirSecretSharing.reconstruct(reconstructionShares));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** List stored secret IDs (without revealing contents). */
    public List<String> listSecretIds() {
        return new ArrayList<>(secrets.keySet());
    }

    /** Check if a secret exists. */
    public boolean hasSecret(String secretId) {
        return secrets.containsKey(secretId);
    }

    /** Get metadata about a secret (without shares). */
    public Optional<String> describeSecret(String secretId) {
        var stored = secrets.get(secretId);
        if (stored == null) return Optional.empty();
        var sb = new StringBuilder();
        sb.append(String.format(
            "[%s] owner: %s, threshold: %d/%d, requires: confidence≥%.2f, alignment≥%.2f",
            secretId, stored.owner(), stored.threshold(), stored.totalShares(),
            stored.requiredConfidence(), stored.requiredAlignment()));
        if (stored.maxLatencyMs() > 0) {
            sb.append(String.format(", topology: latency≤%.0fms", stored.maxLatencyMs()));
        }
        return Optional.of(sb.toString());
    }

    /** Total number of stored secrets. */
    public int secretCount() {
        return secrets.size();
    }

    // ─── W13: local single-node persistence (credential slots) ──────────

    private static final Object LOCAL_LOCK = new Object();
    private static TheSafe LOCAL;

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Where this instance persists its slots; null = in-memory only (default). */
    private Path persistPath;
    /** AES-256 file key derived from node-identity seed; null = plaintext-600 fallback. */
    private byte[] fileKey;
    /** Plaintext mirror of slot values, kept only for persistence serialization. */
    private final Map<String, byte[]> localSlots = new LinkedHashMap<>();

    /**
     * Initialize (or re-key) the local singleton Safe backed by {@code file}.
     * Called once at boot from Main with the node identity's private-key seed
     * as {@code keyMaterial}; existing slots are loaded from disk. A file
     * previously written plaintext (no key was available) is transparently
     * re-persisted encrypted on the first keyed init.
     *
     * @param keyMaterial secret bytes to derive the AES file key from, or
     *                    {@code null} for the documented plaintext-600 fallback
     */
    public static TheSafe initLocal(Path file, byte[] keyMaterial) {
        synchronized (LOCAL_LOCK) {
            var safe = new TheSafe();
            safe.persistPath = file;
            safe.fileKey = keyMaterial == null ? null : deriveFileKey(keyMaterial);
            safe.loadLocal();
            LOCAL = safe;
            return safe;
        }
    }

    /**
     * The local singleton Safe. If Main's wiring block hasn't keyed it yet
     * (bare boots, CLI one-shots), lazily initializes at the canonical path
     * ({@code dataDir/credentials.safe}) in plaintext-600 fallback mode so
     * reads always work; a later {@link #initLocal} with key material
     * replaces the instance and upgrades the file to encrypted-at-rest.
     */
    public static TheSafe local() {
        synchronized (LOCAL_LOCK) {
            if (LOCAL == null) {
                initLocal(SystemPaths.dataDir().resolve("credentials.safe"), null);
            }
            return LOCAL;
        }
    }

    /** Drop the local singleton (tests). */
    public static void resetLocalForTests() {
        synchronized (LOCAL_LOCK) {
            LOCAL = null;
        }
    }

    /**
     * Store a credential slot for single-node use: one Shamir share, K=N=1,
     * no vitality or topology gates (this node IS the quorum). Persists to
     * disk when this instance is file-backed. Overwrites an existing slot.
     */
    public synchronized void storeSlot(String slot, String value) {
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("credential slot must not be blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("credential value must not be null");
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        putSlotShare(slot, bytes);
        localSlots.put(slot, bytes);
        persistLocal();
    }

    /**
     * Register a K=N=1 secret. {@link ShamirSecretSharing#split} rejects
     * k&lt;2 (the polynomial degenerates), so the single-share case is
     * constructed directly: one share at x=1 whose y IS the secret —
     * {@link ShamirSecretSharing#reconstruct} with a lone share yields y
     * unchanged (empty Lagrange product). No vitality/topology gates: this
     * node is the whole quorum.
     */
    private void putSlotShare(String slot, byte[] bytes) {
        var share = new ShamirSecretSharing.Share(1, bytes.clone());
        secrets.put(slot, new StoredSecret(slot, "local", 1, 1,
            List.of(share), 0.0, 0.0));
    }

    /** Read a slot stored via {@link #storeSlot}. Empty when absent. */
    public synchronized Optional<String> readSlot(String slot) {
        if (slot == null || slot.isBlank()) return Optional.empty();
        return retrieve(slot, List.of(0), 1.0, 1.0)
            .map(b -> new String(b, StandardCharsets.UTF_8));
    }

    /** Remove a slot. Returns true when something was deleted. Persists. */
    public synchronized boolean removeSlot(String slot) {
        if (slot == null) return false;
        var removedSecret = secrets.remove(slot) != null;
        var removedSlot = localSlots.remove(slot) != null;
        if (removedSecret || removedSlot) {
            persistLocal();
            return true;
        }
        return false;
    }

    /** Slot ids stored on this instance (never the values). */
    public synchronized List<String> listSlots() {
        return new ArrayList<>(localSlots.keySet());
    }

    // ─── persistence internals ───────────────────────────────────────────

    /**
     * On-disk format (version 1), always 600-mode:
     * <pre>
     * {"version":1, "mode":"aes-gcm", "data":"base64(iv || AES-GCM(slotsJson))"}
     * {"version":1, "mode":"plain",   "slots":{"slot":"base64(value)"}}
     * </pre>
     * where slotsJson is {@code {"slot":"base64(value)"}}.
     */
    private void persistLocal() {
        if (persistPath == null) return;
        try {
            var mapper = new ObjectMapper();
            var slotsNode = mapper.createObjectNode();
            for (var e : localSlots.entrySet()) {
                slotsNode.put(e.getKey(), Base64.getEncoder().encodeToString(e.getValue()));
            }
            var root = mapper.createObjectNode();
            root.put("version", 1);
            if (fileKey != null) {
                root.put("mode", "aes-gcm");
                root.put("data", Base64.getEncoder().encodeToString(
                    gcmEncrypt(mapper.writeValueAsBytes(slotsNode), fileKey)));
            } else {
                root.put("mode", "plain");
                root.set("slots", slotsNode);
            }
            if (persistPath.getParent() != null) {
                Files.createDirectories(persistPath.getParent());
            }
            // Write-temp-then-move so a crash never leaves a half-written safe;
            // permissions restricted BEFORE the file lands at the real name.
            var tmp = persistPath.resolveSibling(persistPath.getFileName() + ".tmp");
            Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            restrictToOwner(tmp);
            try {
                Files.move(tmp, persistPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, persistPath, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(persistPath);
        } catch (IOException e) {
            // Fail loud — a credential the steward believes stored but that
            // evaporates on restart is exactly the silent-inert-default the
            // wire-everything rules forbid.
            throw new RuntimeException("Failed to persist credentials safe to " + persistPath, e);
        }
    }

    /** Load slots from disk into memory (K=N=1 re-share). Missing file = empty safe. */
    private void loadLocal() {
        if (persistPath == null || !Files.isRegularFile(persistPath)) return;
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(persistPath.toFile());
            var mode = root.path("mode").asText("plain");
            var slotsNode = switch (mode) {
                case "aes-gcm" -> {
                    if (fileKey == null) {
                        log.warn("credentials.safe at {} is encrypted but no key material is "
                            + "available — slots unreadable until keyed init", persistPath);
                        yield null;
                    }
                    var data = Base64.getDecoder().decode(root.path("data").asText(""));
                    yield mapper.readTree(gcmDecrypt(data, fileKey));
                }
                case "plain" -> root.get("slots");
                default -> {
                    log.warn("credentials.safe at {} has unknown mode '{}' — ignoring", persistPath, mode);
                    yield null;
                }
            };
            if (slotsNode == null || !slotsNode.isObject()) return;
            var it = ((ObjectNode) slotsNode).fields();
            while (it.hasNext()) {
                var entry = it.next();
                var bytes = Base64.getDecoder().decode(entry.getValue().asText());
                putSlotShare(entry.getKey(), bytes);
                localSlots.put(entry.getKey(), bytes);
            }
            // Honest upgrade: a plaintext file loaded by a keyed instance gets
            // re-persisted encrypted right away.
            if ("plain".equals(mode) && fileKey != null && !localSlots.isEmpty()) {
                persistLocal();
                log.info("credentials.safe upgraded from plaintext-600 to encrypted-at-rest ({} slots)",
                    localSlots.size());
            }
        } catch (Exception e) {
            // Wrong node identity / corrupt file: start empty but DO NOT delete
            // the file — the next storeSlot overwrites it, and until then the
            // operator can still recover it by hand.
            log.warn("credentials.safe at {} unreadable ({}) — starting with empty safe; "
                + "file left in place", persistPath, e.getMessage());
        }
    }

    /** AES-256 file key = SHA-256(context || keyMaterial). */
    private static byte[] deriveFileKey(byte[] keyMaterial) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("wyrdsekai-credentials-safe:v1".getBytes(StandardCharsets.UTF_8));
            digest.update(keyMaterial);
            return digest.digest();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static byte[] gcmEncrypt(byte[] plaintext, byte[] key) {
        try {
            var iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext);
            var out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("credentials.safe encryption failed", e);
        }
    }

    private static byte[] gcmDecrypt(byte[] ivAndCiphertext, byte[] key) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS,
                    Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_BYTES)));
            return cipher.doFinal(ivAndCiphertext, GCM_IV_BYTES,
                ivAndCiphertext.length - GCM_IV_BYTES);
        } catch (Exception e) {
            throw new RuntimeException(
                "credentials.safe decryption failed — file may be from another node identity", e);
        }
    }

    /** chmod 600 — POSIX where available, owner-only File flags elsewhere (Windows). */
    private static void restrictToOwner(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            var f = p.toFile();
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, true);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(false, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, true);
        }
    }
}
