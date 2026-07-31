package org.wyrdsekai.core.agent.research;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * (#1182) — the runtime half of the living-language re-bake loop.
 *
 * <p>As a zone coordinates, its living lexicon grows (P2): {@link ZoneArgotService#calibrate}
 * promotes widely-adopted terms and bumps the codebook version. When drift since the last adapter
 * bake crosses a threshold, the Tier-B 4B adapter no longer speaks the CURRENT codebook fluently —
 * this service prepares the {@code rebake-argot} recipe run and, on success, marks the new version
 * baked (resetting drift). Comprehension never depends on the adapter (Layer A decodes
 * deterministically), so a stale/failed bake degrades gracefully.
 *
 * <p><b>Security (load-bearing):</b> the zone's derived argot key is NEVER placed in recipe params
 * ({@code params_json} persists to world.db). {@link #prepare} writes the derived key to a 0600
 * keyfile and the recipe receives only the PATH; {@link #complete} shreds it. The
 * {@link KeyDeriver} seam keeps this class testable without the crypto/persistence stack (the
 * production deriver is {@code z -> ZoneSecrets.service().has(z) ? ZoneSecrets.service().derive(z,
 * "argot-v1", 32) : null}).
 *
 * <p>Stateless except for the {@link ZoneArgotService} it reads/marks; safe to construct per call.
 */
public final class ArgotRebakeService {

    /** HKDF purpose label for the argot key — must match the boot wire's argot provider. */
    public static final String ARGOT_PURPOSE = "argot-v1";

    /** Default: one promoted codebook version since the last bake is enough to warrant a re-bake. */
    public static final int DEFAULT_DRIFT_THRESHOLD = 1;

    private static final Set<PosixFilePermission> OWNER_ONLY =
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Derives a zone's secret argot key (32 bytes), or null when the zone has no installed master
     *  (public-seed fallback — a re-bake would add no non-extractability, so we skip). */
    public interface KeyDeriver { byte[] derive(String zoneId); }

    /**
     * The outcome of {@link #prepare}: whether to enqueue, the observed drift, the promoted concepts
     * folded into the corpus, the 0600 keyfile path (null when not enqueuing), and the recipe params
     * (never containing the key itself).
     */
    public record Plan(boolean shouldRebake, int drift, List<String> promotedConcepts,
                       Path keyFile, Map<String, Object> params) {
        static Plan skip(int drift) { return new Plan(false, drift, List.of(), null, Map.of()); }
    }

    private ArgotRebakeService() {}

    /** Pure decision: has the codebook drifted past threshold since the last bake? */
    public static boolean shouldRebake(ZoneArgotService svc, String zoneId, int threshold) {
        if (svc == null || zoneId == null || zoneId.isBlank()) return false;
        return svc.driftSinceBake(zoneId) >= Math.max(1, threshold);
    }

    /**
     * Decide + (if warranted) prepare a re-bake: derive the secret key, write it to a 0600 keyfile
     * under {@code keyDir}, and build the recipe params (zone, keyfile path, promoted concepts).
     * Returns a {@link Plan} with {@code shouldRebake=false} when drift is below threshold OR the zone
     * has no secret master (public seed → a bake adds nothing). The caller enqueues {@code rebake-argot}
     * with {@link Plan#params()} and, on terminal, calls {@link #complete}.
     */
    public static Plan prepare(ZoneArgotService svc, String zoneId, int threshold,
                               KeyDeriver deriver, Path keyDir) throws IOException {
        int drift = svc == null || zoneId == null ? 0 : svc.driftSinceBake(zoneId);
        if (!shouldRebake(svc, zoneId, threshold)) return Plan.skip(drift);

        byte[] key = deriver == null ? null : deriver.derive(zoneId);
        if (key == null || key.length == 0) return Plan.skip(drift);   // public seed — nothing to bake
        try {
            var promoted = svc.promotedConcepts(zoneId);
            Path keyFile = writeKeyFile(zoneId, key, keyDir);
            var params = Map.<String, Object>of(
                "zone_id", zoneId,
                "argot_key_file", keyFile.toString(),
                "promoted_concepts", String.join(",", promoted));
            return new Plan(true, drift, promoted, keyFile, params);
        } finally {
            Arrays.fill(key, (byte) 0);   // don't leave the raw key in the heap
        }
    }

    /**
     * Terminal hook: on success, mark the current codebook version baked (resets drift); always shred
     * the keyfile. Idempotent and null-safe — safe to call from a recipe-completion callback for any
     * outcome.
     */
    public static void complete(ZoneArgotService svc, String zoneId, boolean succeeded, Path keyFile) {
        if (succeeded && svc != null && zoneId != null && !zoneId.isBlank()) {
            svc.markBaked(zoneId);
        }
        shred(keyFile);
    }

    /**
     * Terminal hook variant that records a SPECIFIC baked version — the codebook version captured at
     * enqueue (when the corpus/adapter was scoped), not the possibly-drifted current version by the
     * time the (~30-min) bake completes. Use this from the live completion callback so a term promoted
     * DURING the bake isn't silently counted as baked. {@code bakedVersion < 0} falls back to the
     * current-version {@link #complete(ZoneArgotService, String, boolean, Path)} behaviour.
     */
    public static void complete(ZoneArgotService svc, String zoneId, boolean succeeded, Path keyFile,
                                int bakedVersion) {
        if (succeeded && svc != null && zoneId != null && !zoneId.isBlank()) {
            if (bakedVersion >= 0) svc.markBakedAt(zoneId, bakedVersion);
            else svc.markBaked(zoneId);
        }
        shred(keyFile);
    }

    /** Write the derived key (hex) to a fresh 0600 file {@code keyDir/.argot-key-<zone>.hex}. */
    static Path writeKeyFile(String zoneId, byte[] key, Path keyDir) throws IOException {
        Files.createDirectories(keyDir);
        String safeZone = zoneId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path f = keyDir.resolve(".argot-key-" + safeZone + ".hex");
        Files.deleteIfExists(f);
        // Create owner-only up front so the key is never briefly world-readable.
        try {
            Files.createFile(f, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException nonPosix) {
            Files.createFile(f);   // non-POSIX FS (tests on odd platforms) — best-effort
        }
        Files.writeString(f, HexFormat.of().formatHex(key), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(f, OWNER_ONLY); } catch (UnsupportedOperationException ignored) {}
        return f;
    }

    /** Best-effort secure delete: overwrite with zeros then unlink. */
    static void shred(Path keyFile) {
        if (keyFile == null) return;
        try {
            if (Files.exists(keyFile)) {
                long len = Files.size(keyFile);
                if (len > 0) Files.write(keyFile, new byte[(int) Math.min(len, 4096)]);
                Files.deleteIfExists(keyFile);
            }
        } catch (IOException ignored) {
            try { Files.deleteIfExists(keyFile); } catch (IOException ignored2) {}
        }
    }
}
