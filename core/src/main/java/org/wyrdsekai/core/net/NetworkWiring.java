package org.wyrdsekai.core.net;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import com.typesafe.config.ConfigFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;

/**
 * (P3) — production assembly of the {@link NetworkCapability}.
 *
 * <p>Mirrors {@link org.wyrdsekai.core.coding.EgressGate#defaultInstance()}: the
 * provider falls back to {@link #defaultInstance()} when no capability was
 * explicitly wired, so the four {@code CompanionActor} provider-construction
 * sites need no surgery. The gate is read from {@code wyrdsekai.net.*}; ssh/scp
 * run via the real argv exec; credentials resolve from a steward-controlled
 * keyfile convention (below); household-bus transport is left unwired for now
 * (courier satchel returns a clean "not wired" until the NATS transport lands).</p>
 *
 * <h3>Credential (key-ref) resolution</h3>
 * A {@code key-ref} in an allowlist entry is a HANDLE, never key material. The
 * default resolver maps it to a 0600 private-key FILE the steward placed:
 * <ul>
 *   <li>an absolute path or {@code file:<path>} → used verbatim;</li>
 *   <li>{@code household:<nodeId>} → {@code $WYRDSEKAI_DATA_DIR/net-keys/household/<nodeId>};</li>
 *   <li>{@code chest:<slot>} → {@code $WYRDSEKAI_DATA_DIR/net-keys/<slot>}.</li>
 * </ul>
 * The resolver only returns a path that EXISTS and is a regular file — a missing
 * key surfaces as {@code deny:no-credential}, which the agent narrates.
 */
public final class NetworkWiring {

    private static final Logger log = LoggerFactory.getLogger(NetworkWiring.class);

    private NetworkWiring() {}

    /** Resolve a key-ref to an on-disk private-key file under the data dir convention. */
    public static NetworkCapability.CredentialResolver keyfileResolver(Path dataDir) {
        return keyRef -> {
            if (keyRef == null || keyRef.isBlank()) return Optional.empty();
            Path candidate;
            if (keyRef.startsWith("file:")) {
                candidate = Path.of(keyRef.substring("file:".length()));
            } else if (keyRef.startsWith("/")) {
                candidate = Path.of(keyRef);
            } else if (keyRef.startsWith("household:")) {
                candidate = dataDir.resolve("net-keys").resolve("household")
                    .resolve(sanitize(keyRef.substring("household:".length())));
            } else if (keyRef.startsWith("chest:")) {
                candidate = dataDir.resolve("net-keys").resolve(sanitize(keyRef.substring("chest:".length())));
            } else {
                candidate = dataDir.resolve("net-keys").resolve(sanitize(keyRef));
            }
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().toString());
            }
            log.debug("[NetworkWiring] key-ref '{}' → no keyfile at {}", keyRef, candidate);
            return Optional.empty();
        };
    }

    /** Reject path-traversal / separators in the ref tail. */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.@-]", "_");
    }

    private static Path resolveDataDir() {
        // #data-dir (2026-07-19) — route through SystemPaths so the
        // wyrdsekai.dataDir test property and WYRDSEKAI_DATA_DIR are honored
        // uniformly (a direct env read here missed the property override).
        return SystemPaths.dataDir();
    }

    private static volatile NetworkCapability defaultInstance;

    /**
     * Cached production capability from {@code ConfigFactory.load()}. Gate from
     * config, real ssh/scp exec, keyfile-convention resolver, household
     * transport unwired (null). Household copy will report "not wired" until a
     * transport is injected via {@link #setHouseholdTransport}.
     */
    public static NetworkCapability defaultInstance() {
        var cached = defaultInstance;
        if (cached != null) return cached;
        synchronized (NetworkWiring.class) {
            if (defaultInstance == null) {
                currentGate = buildMergedGate();
                defaultInstance = new NetworkCapability(
                    currentGate, keyfileResolver(resolveDataDir()), null, householdTransport);
            }
            return defaultInstance;
        }
    }

    /**
     * The live merged gate: HOCON {@code wyrdsekai.net.*} entries PLUS the
     * steward's persisted {@link NetworkAllowStore} entries ({@code scroll net
     * allow …}). Store entries come first so a steward's runtime grant wins
     * over a same-host config entry (first match takes the credential).
     */
    private static NetworkGate buildMergedGate() {
        NetworkGate configGate;
        try {
            configGate = NetworkGate.fromConfig(ConfigFactory.load());
        } catch (Exception e) {
            log.warn("[NetworkWiring] config load failed — empty gate: {}", e.getMessage());
            configGate = NetworkGate.empty();
        }
        var merged = new ArrayList<NetworkAllowEntry>();
        try {
            merged.addAll(new NetworkAllowStore(resolveDataDir()).entries());
        } catch (Exception e) {
            log.warn("[NetworkWiring] allow-store read failed — config entries only: {}",
                e.getMessage());
        }
        merged.addAll(configGate.allowlist());
        var defaults = new HashMap<String, Boolean>();
        for (var kind : List.of("ssh", "scp", "http", "https")) {
            defaults.put(kind, configGate.defaultFor(kind));
        }
        return new NetworkGate(merged, defaults);
    }

    private static volatile NetworkGate currentGate;

    /**
     * The gate the default capability currently enforces (building it on first
     * ask). Read-only view for affordance gating — which network items are
     * worth surfacing to a companion.
     */
    public static NetworkGate currentGate() {
        var g = currentGate;
        if (g != null) return g;
        defaultInstance();
        return currentGate;
    }

    /** True once a household-bus transport was injected (courier satchel live). */
    public static boolean householdTransportWired() {
        return householdTransport != null;
    }

    /**
     * Drop the cached capability + gate so the next call rebuilds from config
     * + the allow store. Called by {@link NetworkAllowStore} on every steward
     * mutation — this is what makes {@code scroll net allow} hot-reload.
     */
    public static synchronized void invalidate() {
        defaultInstance = null;
        currentGate = null;
    }

    /** Optional household-bus transport, injected at boot once the NATS bridge exists. */
    private static volatile NetworkCapability.HouseholdTransport householdTransport;

    /** Wire the household transport (courier satchel) — rebuilds the cached default. */
    public static synchronized void setHouseholdTransport(NetworkCapability.HouseholdTransport t) {
        householdTransport = t;
        invalidate();
    }

    /** Test hook — clear the cached instance so config/transport changes re-resolve. */
    static synchronized void resetForTest() {
        invalidate();
    }
}
