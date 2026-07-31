package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton holder for the composed {@link ZoneDirectory}.
 *
 * <p>Wired once at server boot after the composite is built; exposed to
 * downstream consumers that otherwise would need to thread the directory
 * through constructor chains (room scripts via {@code BridgeDataProvider},
 * REST endpoints, admin CLI helpers). Pattern mirrors
 * {@link ZoneAddressResolverService} and the rate-limited null-WARN from
 * the 2026-04-19 bootstrap audit.</p>
 */
public final class ZoneDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(ZoneDirectoryService.class);
    private static final AtomicReference<ZoneDirectory> INSTANCE = new AtomicReference<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile Instant lastNullWarn = Instant.MIN;
    private static final Duration NULL_WARN_WINDOW = Duration.ofMinutes(1);

    private ZoneDirectoryService() {}

    public static synchronized void init(ZoneDirectory directory) {
        INSTANCE.set(directory);
        log.info("ZoneDirectoryService initialised ({})", directory.getClass().getSimpleName());
    }

    /** @return the singleton, or {@code null} with a rate-limited WARN. */
    public static ZoneDirectory get() {
        var inst = INSTANCE.get();
        if (inst == null) warnUninitialised();
        return inst;
    }

    private static synchronized void warnUninitialised() {
        var now = Instant.now();
        if (now.isAfter(lastNullWarn.plus(NULL_WARN_WINDOW))) {
            log.warn("ZoneDirectoryService.get() called before init — directory queries will degrade. "
                + "Ensure Main.java sets the composite directory at startup.");
            lastNullWarn = now;
        }
    }

    public static void resetForTests() {
        INSTANCE.set(null);
        lastNullWarn = Instant.MIN;
    }

    // ── Rendering helpers for scripts ─────────────────────────────────

    /**
     * Render directory results as a compact JSON array, matching the
     * shape the {@code atrium.js} room script expects. One entry per
     * DID with summary fields only — full manifest is available via
     * {@link ZoneDirectory#lookup(String)} if the script needs more.
     *
     * <p>Modes:</p>
     * <ul>
     *   <li>{@code "recent"} — last N refreshed. {@code arg} = limit (int-as-string).</li>
     *   <li>{@code "tag:<name>"} — DIDs tagged with {@code name}.</li>
     *   <li>{@code "capability:<name>"} — DIDs advertising capability.</li>
     *   <li>{@code "search:<text>"} — full-text (rendezvous does hybrid
     *       keyword+semantic; other backends substring). Requires rendezvous to
     *       serve {@code /api/directory/search} — falls back to empty otherwise.</li>
     * </ul>
     */
    public static String renderDiscover(String mode, String arg) {
        var dir = get();
        if (dir == null) return "[]";
        try {
            int limit = safeLimit(arg, 20);
            if (mode == null || "recent".equalsIgnoreCase(mode)) {
                return renderManifests(dir.recent(limit));
            } else if (mode.startsWith("tag:")) {
                var tag = mode.substring(4);
                return renderDids(dir, dir.discoverByTag(tag), limit);
            } else if (mode.startsWith("capability:")) {
                var cap = mode.substring(11);
                return renderDids(dir, dir.discoverByCapability(cap), limit);
            } else if (mode.startsWith("search:")) {
                // Search isn't in the ZoneDirectory interface — only rendezvous
                // implements it. Scripts that want search should hit the
                // rendezvous REST directly; here we degrade to a no-op.
                log.debug("renderDiscover(search:…): not supported at directory interface");
                return "[]";
            }
            return "[]";
        } catch (Exception e) {
            log.debug("renderDiscover({}, {}) failed: {}", mode, arg, e.getMessage());
            return "[]";
        }
    }

    private static int safeLimit(String arg, int defaultValue) {
        if (arg == null || arg.isBlank()) return defaultValue;
        try {
            return Math.max(1, Math.min(50, Integer.parseInt(arg.trim())));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String renderDids(ZoneDirectory dir,
                                      List<String> dids, int limit) {
        var out = new ArrayList<ZoneManifestV1>();
        for (var did : dids) {
            if (out.size() >= limit) break;
            dir.lookup(did).ifPresent(out::add);
        }
        return renderManifests(out);
    }

    private static String renderManifests(List<ZoneManifestV1> manifests) {
        try {
            var arr = MAPPER.createArrayNode();
            for (var m : manifests) {
                var node = arr.addObject();
                node.put("did", m.did());
                node.put("zoneLabel", m.zoneLabel());
                if (m.displayName() != null) node.put("displayName", m.displayName());
                if (m.tagline() != null) node.put("tagline", m.tagline());
                if (m.icon() != null) node.put("icon", m.icon());
                if (m.tags() != null && !m.tags().isEmpty()) {
                    var tagsArr = node.putArray("tags");
                    m.tags().forEach(tagsArr::add);
                }
                if (m.refreshedAt() != null) node.put("refreshedAt", m.refreshedAt());
            }
            return MAPPER.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }
}
