package org.wyrdsekai.core.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;

/**
 * the steward-editable HALF of the network allowlist.
 *
 * <p>{@code wyrdsekai.net.allowlist} in HOCON covers install-time entries, but
 * the Study's {@code scroll net allow/list/revoke} verb needs a persisted,
 * hot-reloadable store the room script can write WITHOUT a zone bounce (the
 * scroll's flat {@code KEY=VALUE} config file can't carry a structured entry
 * that binds a credential ref). Entries live as JSON at
 * {@code $WYRDSEKAI_DATA_DIR/net-allowlist.json}; every mutation invalidates
 * {@link NetworkWiring}'s cached capability so the merged gate (config entries
 * + store entries) takes effect on the next {@code world.net.*} call.</p>
 *
 * <p>Validation happens HERE, not in the room script: the host must be
 * hostname-legal, the kinds must be a subset of {ssh, scp, http, https}, and a
 * credentialed kind (ssh/scp) must carry a {@code key-ref} that RESOLVES to an
 * existing keyfile — a steward cannot persist a dangling credential pointer
 * (spec §2.3: "the verb VALIDATES the host + checks the key-ref resolves").</p>
 */
public final class NetworkAllowStore {

    private static final Logger log = LoggerFactory.getLogger(NetworkAllowStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_KINDS = Set.of("ssh", "scp", "http", "https");

    /** Outcome of an allow/revoke attempt — {@code error} is null on success. */
    public record Outcome(boolean ok, String error, NetworkAllowEntry entry) {
        static Outcome fail(String error) { return new Outcome(false, error, null); }
        static Outcome success(NetworkAllowEntry e) { return new Outcome(true, null, e); }
    }

    private final Path file;
    private final NetworkCapability.CredentialResolver keyResolver;
    private final Object lock = new Object();

    public NetworkAllowStore(Path dataDir) {
        this.file = dataDir.resolve("net-allowlist.json");
        this.keyResolver = NetworkWiring.keyfileResolver(dataDir);
    }

    // ── singleton over the live data dir ──────────────────────────────

    private static volatile NetworkAllowStore instance;

    public static NetworkAllowStore get() {
        var cached = instance;
        if (cached != null) return cached;
        synchronized (NetworkAllowStore.class) {
            if (instance == null) {
                instance = new NetworkAllowStore(SystemPaths.dataDir());
            }
            return instance;
        }
    }

    /** Test hook — drop the cached singleton so a changed data dir re-resolves. */
    public static synchronized void resetForTest() {
        instance = null;
    }

    // ── reads ──────────────────────────────────────────────────────────

    /** All persisted entries. Empty on a fresh install or unreadable file. */
    public List<NetworkAllowEntry> entries() {
        synchronized (lock) {
            return readEntries();
        }
    }

    // ── mutations ──────────────────────────────────────────────────────

    /**
     * Add (or replace, keyed by host) an allowlist entry. Validates host,
     * kinds, and — for ssh/scp — that {@code keyRef} resolves to an existing
     * keyfile under the data-dir convention.
     */
    public Outcome allow(String host, List<String> kinds, String keyRef, String commandPrefix) {
        var h = host == null ? "" : host.trim();
        if (h.isEmpty() || !h.matches("[A-Za-z0-9*][A-Za-z0-9_.*-]*")) {
            return Outcome.fail("invalid host '" + host + "'");
        }
        var normalizedKinds = new LinkedHashSet<String>();
        if (kinds != null) {
            for (var k : kinds) {
                if (k == null || k.isBlank()) continue;
                normalizedKinds.add(k.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (normalizedKinds.isEmpty()) {
            return Outcome.fail("no kinds given — expected a subset of ssh,scp,http,https");
        }
        for (var k : normalizedKinds) {
            if (!VALID_KINDS.contains(k)) {
                return Outcome.fail("unknown kind '" + k + "' — expected ssh,scp,http,https");
            }
        }
        boolean credentialed = normalizedKinds.contains("ssh") || normalizedKinds.contains("scp");
        var ref = keyRef == null || keyRef.isBlank() ? null : keyRef.trim();
        if (credentialed) {
            if (ref == null) {
                return Outcome.fail("ssh/scp entries need a key ref "
                    + "(household:<node> or chest:<slot>)");
            }
            if (keyResolver.resolveKeyfile(ref).isEmpty()) {
                return Outcome.fail("key ref '" + ref + "' does not resolve to a keyfile — "
                    + "place the private key under the data dir's net-keys/ first");
            }
        }
        var prefix = commandPrefix == null || commandPrefix.isBlank() ? null : commandPrefix.trim();
        var entry = new NetworkAllowEntry(h, normalizedKinds, ref, List.of(), prefix);

        synchronized (lock) {
            var all = new ArrayList<>(readEntries());
            all.removeIf(e -> e.host().equalsIgnoreCase(h));
            all.add(entry);
            try {
                writeEntries(all);
            } catch (IOException e) {
                log.warn("[NetworkAllowStore] failed to persist allowlist: {}", e.getMessage());
                return Outcome.fail("could not persist the allowlist: " + e.getMessage());
            }
        }
        NetworkWiring.invalidate();
        log.info("[NetworkAllowStore] allow {} kinds={} keyRef={}", h, normalizedKinds, ref);
        return Outcome.success(entry);
    }

    /** Remove the entry for {@code host} (exact, case-insensitive). */
    public Outcome revoke(String host) {
        var h = host == null ? "" : host.trim();
        if (h.isEmpty()) return Outcome.fail("no host given");
        NetworkAllowEntry removed = null;
        synchronized (lock) {
            var all = new ArrayList<>(readEntries());
            for (var e : all) {
                if (e.host().equalsIgnoreCase(h)) { removed = e; break; }
            }
            if (removed == null) return Outcome.fail("no allowlist entry for '" + h + "'");
            all.remove(removed);
            try {
                writeEntries(all);
            } catch (IOException e) {
                log.warn("[NetworkAllowStore] failed to persist allowlist: {}", e.getMessage());
                return Outcome.fail("could not persist the allowlist: " + e.getMessage());
            }
        }
        NetworkWiring.invalidate();
        log.info("[NetworkAllowStore] revoke {}", h);
        return Outcome.success(removed);
    }

    // ── file I/O ───────────────────────────────────────────────────────

    private List<NetworkAllowEntry> readEntries() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            var root = MAPPER.readTree(Files.readAllBytes(file));
            if (root == null || !root.isArray()) return List.of();
            var out = new ArrayList<NetworkAllowEntry>();
            for (var node : root) {
                var host = node.path("host").asText("");
                if (host.isBlank()) continue;
                var kinds = new LinkedHashSet<String>();
                for (var k : node.path("kinds")) {
                    kinds.add(k.asText().toLowerCase(Locale.ROOT));
                }
                var keyRef = node.hasNonNull("keyRef") ? node.get("keyRef").asText() : null;
                var prefix = node.hasNonNull("commandPrefix")
                    ? node.get("commandPrefix").asText() : null;
                out.add(new NetworkAllowEntry(host, kinds, keyRef, List.of(), prefix));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("[NetworkAllowStore] unreadable {} — treating as empty: {}",
                file, e.getMessage());
            return List.of();
        }
    }

    private void writeEntries(List<NetworkAllowEntry> all) throws IOException {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var e : all) {
            var node = MAPPER.createObjectNode();
            node.put("host", e.host());
            var kindsArr = node.putArray("kinds");
            for (var k : e.kinds()) kindsArr.add(k);
            if (e.keyRef() != null) node.put("keyRef", e.keyRef());
            if (e.commandPrefix() != null) node.put("commandPrefix", e.commandPrefix());
            arr.add(node);
        }
        Files.createDirectories(file.getParent());
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(arr));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }
}
