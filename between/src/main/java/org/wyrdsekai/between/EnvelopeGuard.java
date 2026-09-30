package org.wyrdsekai.between;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.EnvelopeVerificationMode;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks every signed envelope before a handler sees it (audit 2026-09-28: {@link NatsBridge} used to hand
 * envelopes over unverified).
 *
 * <ul>
 *   <li>Household subjects {@code between.{localZone}.>}: the sender's key is the roster key
 *       ({@code households}) or, for a node not on the roster, the key pinned on its first signed
 *       {@code hello}. An unknown sender is dropped. A {@code hello} is checked against the pinned key
 *       when there is one; a {@code hello} that presents a different key is dropped.</li>
 *   <li>Another zone's subjects {@code between.{zone}.>}: the key pinned for that zone by an active
 *       federation agreement.</li>
 *   <li>{@code federation.>}: the sender is a zone, not a node; FederationActor verifies it against the
 *       zone's pinned key. Here only the timestamp window and the replay cache apply.</li>
 * </ul>
 *
 * Every envelope must carry a timestamp within {@link #maxSkew} of this clock, and an envelope already
 * accepted on the same channel is dropped as a replay. Drops are logged at WARN.
 */
public final class EnvelopeGuard {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeGuard.class);

    public static final Duration DEFAULT_MAX_SKEW = Duration.ofMinutes(5);
    private static final int REPLAY_CAPACITY = 32_768;
    private static final long WARN_EVERY_MS = 60_000;

    @FunctionalInterface
    public interface KeyLookup {
        Optional<byte[]> keyFor(String id);
    }

    /** Called when a roster node rotates its key with a message signed by the old one. */
    @FunctionalInterface
    public interface RosterRotation {
        void rotated(String nodeId, byte[] newKey);
    }

    private final String localZoneId;
    private final KeyLookup roster;
    private final PeerKeyPins pins;
    private final KeyLookup zoneKeys;
    private final RosterRotation rosterRotation;
    private final EnvelopeVerificationMode mode;
    private final Duration maxSkew;
    private final Clock clock;
    private final Map<String, Long> seen = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > REPLAY_CAPACITY;
        }
    };
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();
    /** Roster lookups hit the database; household traffic is frequent, so answers are kept briefly. */
    private final Map<String, CachedKey> rosterCache = new ConcurrentHashMap<>();
    private static final long ROSTER_CACHE_MS = 10_000;

    private record CachedKey(Optional<byte[]> key, long at) {}

    public EnvelopeGuard(String localZoneId, KeyLookup roster, PeerKeyPins pins, KeyLookup zoneKeys,
                         RosterRotation rosterRotation, EnvelopeVerificationMode mode,
                         Duration maxSkew, Clock clock) {
        this.localZoneId = localZoneId;
        this.roster = roster != null ? roster : id -> Optional.empty();
        this.pins = pins != null ? pins : new PeerKeyPins(null);
        this.zoneKeys = zoneKeys != null ? zoneKeys : id -> Optional.empty();
        this.rosterRotation = rosterRotation;
        this.mode = mode;
        this.maxSkew = maxSkew;
        this.clock = clock;
        if (mode != EnvelopeVerificationMode.HARD) {
            log.warn("Between: envelope verification is {} (WYRDSEKAI_ENVELOPE_VERIFY). Forged, unsigned and "
                + "replayed messages are {} Set it to hard, or remove it, once every machine is upgraded.",
                mode, mode == EnvelopeVerificationMode.OFF ? "not checked at all." : "logged but still delivered.");
        }
    }

    /** The key this node holds for a household peer: the roster's, else the one pinned at first hello. */
    public Optional<byte[]> householdKey(String nodeId) {
        var r = rosterKey(nodeId);
        return r.isPresent() ? r : pins.get(nodeId);
    }

    private Optional<byte[]> rosterKey(String nodeId) {
        long now = clock.millis();
        var cached = rosterCache.get(nodeId);
        if (cached != null && now - cached.at() < ROSTER_CACHE_MS) return cached.key();
        var key = roster.keyFor(nodeId);
        if (rosterCache.size() > 4096) rosterCache.clear();
        rosterCache.put(nodeId, new CachedKey(key, now));
        return key;
    }

    /**
     * @param subject the NATS subject it arrived on
     * @param channel which subscription delivered it; the replay cache is per channel
     * @return true to deliver
     */
    public boolean admit(String subject, String channel, BetweenEnvelope env) {
        if (mode == EnvelopeVerificationMode.OFF) return true;
        var refusal = check(subject, env);
        if (refusal == null && !firstSight(env, channel)) refusal = "replayed (already delivered once)";
        return decide(refusal, subject, env);
    }

    /** A reply to our own request: same checks, no replay cache (each reply goes to a fresh inbox). */
    public boolean admitReply(String requestSubject, BetweenEnvelope env) {
        if (mode == EnvelopeVerificationMode.OFF) return true;
        return decide(check(requestSubject, env), requestSubject, env);
    }

    private boolean decide(String refusal, String subject, BetweenEnvelope env) {
        if (refusal == null) return true;
        var src = env == null ? null : env.src();
        warn(src + "|" + refusal.replaceAll("[0-9]", ""), "Between: {} envelope from '{}' on {}: {}",
            mode == EnvelopeVerificationMode.HARD ? "DROPPED" : "accepting (soft mode) a bad", src, subject, refusal);
        return mode != EnvelopeVerificationMode.HARD;
    }

    /** Null when the envelope is good; otherwise why not. */
    String check(String subject, BetweenEnvelope env) {
        if (env == null || env.payload() == null) return "not an envelope";
        if (env.sig() == null || env.sig().isBlank() || env.src() == null || env.src().isBlank()) {
            return "unsigned";
        }
        if (env.ts() == null) return "no timestamp";
        var skewMs = Math.abs(clock.millis() - env.ts().toEpochMilli());
        if (skewMs > maxSkew.toMillis()) {
            return "timestamp is " + (skewMs / 1000) + " s away from this machine's clock (window "
                + maxSkew.toSeconds() + " s); check the clocks";
        }
        var parts = subject.split("\\.");
        if (parts.length >= 2 && "federation".equals(parts[0])) {
            return null; // zone-level signature: FederationActor checks it against the zone's pinned key
        }
        if (parts.length >= 2 && "between".equals(parts[0]) && !localZoneId.equals(parts[1])) {
            var key = zoneKeys.keyFor(parts[1]);
            if (key.isEmpty()) return "zone '" + parts[1] + "' has no active agreement with a pinned key";
            return signedBy(env, key.get()) ? null : "signature does not match zone '" + parts[1] + "'";
        }
        if (parts.length >= 6 && "between".equals(parts[0]) && !"*".equals(parts[2])
                && !parts[2].equals(env.src())) {
            return "subject names sender '" + parts[2] + "' but the envelope is from '" + env.src() + "'";
        }
        return checkHousehold(env);
    }

    private String checkHousehold(BetweenEnvelope env) {
        var src = env.src();
        var payload = env.payload();
        var type = payload.path("type").asText("");
        boolean hello = "hello".equals(type) || "hello_ack".equals(type);
        byte[] offered = null;
        if (hello) {
            if (!src.equals(payload.path("nodeId").asText(""))) return "hello names a different node id";
            try {
                offered = Base64.getDecoder().decode(payload.path("publicKey").asText(""));
            } catch (IllegalArgumentException e) {
                return "hello carries an unreadable key";
            }
        }
        var rosterKey = rosterKey(src);
        var key = rosterKey.isPresent() ? rosterKey : pins.get(src);
        if (key.isEmpty()) {
            if (!hello || offered.length == 0) return "unknown sender (not on the roster, no pinned key)";
            if (!signedBy(env, offered)) return "hello signature does not match the key it carries";
            if (!pins.pinIfAbsent(src, offered)) return "hello presents a different key than the pinned one";
            return null;
        }
        if (hello && !Arrays.equals(offered, key.get())) {
            return "hello presents a different key than the pinned one (a key change needs key_rotation)";
        }
        if (!signedBy(env, key.get())) return "signature does not match the pinned key";
        if (KeyRotation.TYPE.equals(type)) {
            var newKey = KeyRotation.provenNewKey(src, key.get(), payload);
            if (newKey == null) return "key_rotation without a valid proof from the new key";
            if (rosterKey.isPresent()) {
                if (rosterRotation != null) rosterRotation.rotated(src, newKey);
                rosterCache.remove(src);
            } else {
                pins.rotate(src, key.get(), newKey);
            }
            log.info("Between: node {} rotated its key (signed by the old key)", src);
        }
        return null;
    }

    private static boolean signedBy(BetweenEnvelope env, byte[] key) {
        try {
            return env.verify(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean firstSight(BetweenEnvelope env, String channel) {
        var id = env.sig() + "|" + channel;
        long now = clock.millis();
        synchronized (seen) {
            var at = seen.get(id);
            if (at != null && now - at <= 2 * maxSkew.toMillis()) return false;
            seen.put(id, now);
            return true;
        }
    }

    private void warn(String key, String fmt, Object... args) {
        long now = clock.millis();
        var prev = lastWarn.get(key);
        if (prev != null && now - prev < WARN_EVERY_MS) return;
        lastWarn.put(key, now);
        if (lastWarn.size() > 4096) lastWarn.clear();
        log.warn(fmt, args);
    }
}
