package org.wyrdsekai.core.nostr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 5 external adapter for Nostr.
 *
 * <p>Companion scripts call {@code world.nostr.publish({content, tags?, kind?})}
 * which routes here. This adapter:
 * <ol>
 *   <li>Resolves the companion's keypair (either DID-derived via HKDF, or a
 *       per-DID override stashed in The Safe at {@code nostr.keypairs.<did>}).</li>
 *   <li>Builds a signed {@link NostrEvent}.</li>
 *   <li>Fans out to the configured relay pool.</li>
 *   <li>Returns a normalized AdapterResponse with per-relay outcomes.</li>
 * </ol>
 *
 * <p>Inbound subscriptions and Study-side opt-in UX are Phase 2b — this
 * Phase 2a build covers outbound publish end-to-end.
 *
 * <p>Per-companion rate limiting (60 events/minute default) prevents a
 * runaway script from spamming relays. Limits are per-process, not durable.
 */
public final class NostrAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(NostrAdapter.class);

    /**
     * Per-DID rate limiter — millis at which the next event is allowed.
     * Cleared on process restart; intentional (drops are bounded by uptime).
     */
    private final ConcurrentHashMap<String, RateState> rateLimits = new ConcurrentHashMap<>();

    private final NostrRelayPool pool;
    private final int maxEventsPerMinute;
    /** Resolves a DID → 32-byte seed for HKDF (typically the Ed25519 private key bytes). */
    private volatile SeedResolver seedResolver;

    public NostrAdapter(NostrRelayPool pool, SeedResolver seedResolver, int maxEventsPerMinute) {
        this.pool = pool;
        this.seedResolver = seedResolver == null ? did -> null : seedResolver;
        this.maxEventsPerMinute = maxEventsPerMinute;
    }

    /**
     * Replace the seed resolver at runtime. Used by {@code Main} to upgrade
     * the bootstrap-time placeholder (which returns null and yields
     * {@code credential_missing}) to a real {@code NodeIdentity}-backed
     * resolver once the identity is loaded.
     */
    public void setSeedResolver(SeedResolver resolver) {
        this.seedResolver = resolver == null ? did -> null : resolver;
    }

    @Override public String namespace() { return "nostr"; }

    @Override public Set<String> capabilities() {
        return Set.of("publish");
    }

    /** The Safe slot pattern. Resolved per-DID via {@code nostr.keypairs.<did>}. */
    @Override public String credentialSlot() { return "nostr.keypairs"; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        return switch (req.method()) {
            case "publish" -> handlePublish(req);
            default -> AdapterResponse.fail("unknown_method",
                "nostr adapter: unknown method '" + req.method() + "'", false);
        };
    }

    private AdapterResponse handlePublish(AdapterRequest req) {
        var args = req.args();
        var content = stringArg(args, "content");
        if (content == null) {
            return AdapterResponse.fail("bad_request", "publish: 'content' is required", false);
        }
        var kind = intArg(args, "kind", 1);
        var tags = tagsArg(args.get("tags"));

        // Resolve the companion DID's Nostr key. Priority:
        //   1. Steward-supplied nsec hex in Safe under nostr.keypairs.<did>
        //   2. Default: HKDF-derive from the companion's Ed25519 seed
        var did = stringArg(args, "did");
        if (did == null || did.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "publish: 'did' is required (provided by the runtime)", false);
        }

        if (!rateAllow(did)) {
            return AdapterResponse.fail("rate_limited",
                "nostr publish: " + maxEventsPerMinute + " events/min cap for " + did,
                true);
        }

        NostrKey key;
        try {
            key = resolveKeyForDid(did);
        } catch (Exception e) {
            return AdapterResponse.fail("credential_missing",
                "nostr publish: could not resolve key for " + did + ": " + e.getMessage(),
                false);
        }
        if (key == null) {
            return AdapterResponse.fail("credential_missing",
                "nostr publish: no key available for " + did, false);
        }

        var createdAt = Instant.now().getEpochSecond();
        var event = NostrEvent.buildAndSign(key, kind, tags, content, createdAt);
        var result = pool.publish(event);
        if (!result.any()) {
            return AdapterResponse.fail("publish_failed",
                "nostr publish: all relays rejected or unreachable; errors=" + result.errors(),
                true);
        }
        return AdapterResponse.ok(Map.of(
            "eventId", event.id(),
            "pubkey", event.pubkey(),
            "npub", key.npub(),
            "createdAt", createdAt,
            "kind", kind,
            "relays", result.toMap()));
    }

    // ─────────── helpers ───────────

    /**
     * Resolve a Nostr key for the given DID. First try the steward override
     * (a 32-char hex private key in The Safe at slot {@code nostr.keypairs.<did>});
     * otherwise HKDF-derive from the Ed25519 seed supplied by
     * {@link SeedResolver}.
     */
    private NostrKey resolveKeyForDid(String did) {
        var slot = "nostr.keypairs." + did;
        var override = CredentialResolver.get().resolve(slot, did);
        if (override.isPresent()) {
            var raw = override.get().trim();
            // Allow either nsec1... bech32 or 64-char hex.
            if (raw.startsWith("nsec1")) {
                var decoded = Bech32.decode32(raw);
                if (!"nsec".equals(decoded.hrp())) {
                    throw new IllegalArgumentException("override has wrong hrp: " + decoded.hrp());
                }
                return NostrKey.fromHexPrivateKey(HexFormat.of().formatHex(decoded.data()));
            }
            return NostrKey.fromHexPrivateKey(raw);
        }
        // Default: derive from DID's Ed25519 seed.
        var seed = seedResolver.seedForDid(did);
        if (seed == null) return null;
        return NostrKey.deriveFromEd25519PrivateKey(seed);
    }

    private boolean rateAllow(String did) {
        var now = System.currentTimeMillis();
        var window = 60_000L;
        var state = rateLimits.compute(did, (k, prev) -> {
            if (prev == null || now - prev.windowStartMs > window) {
                return new RateState(now, 1);
            }
            return new RateState(prev.windowStartMs, prev.count + 1);
        });
        return state.count <= maxEventsPerMinute;
    }

    private static String stringArg(Map<String, Object> args, String key) {
        var v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static int intArg(Map<String, Object> args, String key, int defaultVal) {
        var v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return defaultVal; }
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> tagsArg(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof Collection<?> coll)) return List.of();
        var out = new ArrayList<List<String>>(coll.size());
        for (var entry : coll) {
            if (!(entry instanceof Collection<?> inner)) continue;
            var t = new ArrayList<String>(inner.size());
            for (var s : inner) t.add(s == null ? "" : s.toString());
            out.add(t);
        }
        return out;
    }

    private record RateState(long windowStartMs, int count) {}

    /**
     * Pluggable bridge between a DID and its 32-byte Ed25519 secret seed
     * (the {@code privateKey()} of the JDK Ed25519 keypair). The default
     * derivation uses this seed to HKDF a secp256k1 Nostr key.
     *
     * <p>Wired in production by {@link NostrAdapterBootstrap}; tests pass a
     * lambda that returns a fixed byte array.
     */
    @FunctionalInterface public interface SeedResolver {
        byte[] seedForDid(String did);
    }
}
