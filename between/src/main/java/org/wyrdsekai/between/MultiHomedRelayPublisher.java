package org.wyrdsekai.between;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.federation.ZoneManifest;
import org.wyrdsekai.core.config.RelayLegConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * routes the federation send/receive path
 * across a zone's relay legs.
 *
 * <p>This is the live wiring of {@link RelayPathSelector}. It sits on the
 * directed cross-zone subjects (tell / transit / etc., the ones published via
 * the {@code relayPublisher} hook) — NOT the session/inference proxy, which
 * keeps using its own connection.</p>
 *
 * <ul>
 *   <li><b>Outbound</b> ({@link #publish}): parse the target zone from the
 *       subject; if the peer's advertised relay set is known, ask the selector
 *       for the single best SHARED relay and send only there; otherwise
 *       broadcast over every <i>non-public</i> leg (privacy rail R1 — a
 *       private-floor zone never egresses over a public relay). No shared
 *       relay → drop (§3.3.2, never silently reroute).</li>
 *   <li><b>Inbound</b> ({@link #subscribeAll}): subscribe the handler on every
 *       leg so a message arrives no matter which relay carried it, with a
 *       content-hash dedup so a peer reachable over &gt;1 shared leg is handled
 *       once.</li>
 * </ul>
 */
public final class MultiHomedRelayPublisher {

    private static final Logger log = LoggerFactory.getLogger(MultiHomedRelayPublisher.class);

    /** One leg: its config (url/visibility) + the live transport to that relay. */
    public record Leg(RelayLegConfig cfg, RelaySessionTransport transport) {}

    private final List<Leg> legs;
    private final RelayLegConfig.Visibility floor;
    /** zone id → that peer's advertised relays (from the directory); may return null/empty. */
    private final Function<String, List<ZoneManifest.RelayAdvert>> peerRelays;
    private final RelaySeenSet inboundDedup = new RelaySeenSet(8192);

    public MultiHomedRelayPublisher(List<Leg> legs, RelayLegConfig.Visibility floor,
                                    Function<String, List<ZoneManifest.RelayAdvert>> peerRelays) {
        this.legs = List.copyOf(legs);
        this.floor = floor == null ? RelayLegConfig.Visibility.PRIVATE : floor;
        this.peerRelays = peerRelays != null ? peerRelays : z -> null;
    }

    /** Number of configured legs (1 = effectively single-relay). */
    public int legCount() { return legs.size(); }

    /**
     * Route a directed cross-zone publish. Subject form is
     * {@code federation.{targetZone}.{verb}} (legacy) or
     * {@code federation.{fp}.{label}.{verb}} (canonical) — we read the zone
     * token and select.
     */
    public void publish(String subject, byte[] data) {
        var target = targetZoneFromSubject(subject);
        var advs = target != null ? peerRelays.apply(target) : null;

        List<Leg> chosen;
        if (advs != null && !advs.isEmpty()) {
            var pick = RelayPathSelector.pick(cfgs(), advs, /*peerFallbackUrl=*/null, floor);
            if (pick.isEmpty()) {
                log.warn("Multi-homing: no shared relay with zone '{}' for '{}' — dropping (no silent "
                    + "fallback)", target, subject);
                return;
            }
            var url = RelayPathSelector.norm(pick.get().url());
            chosen = legs.stream().filter(l -> RelayPathSelector.norm(l.cfg().url()).equals(url)).toList();
        } else {
            // Peer advert unknown → broadcast over non-public legs (R1-safe).
            chosen = legs.stream()
                .filter(l -> !(floor == RelayLegConfig.Visibility.PRIVATE && l.cfg().isPublic()))
                .toList();
        }

        boolean sent = false;
        for (var l : chosen) {
            if (l.transport() != null && l.transport().isConnected()) {
                l.transport().publish(subject, data);
                sent = true;
            }
        }
        if (!sent) {
            log.debug("Multi-homing: no connected leg to publish '{}' (target='{}')", subject, target);
        }
    }

    /**
     * Subscribe {@code handler} on every leg, deduped so a duplicate arriving
     * over a second shared leg is handled once. Best-effort across legs.
     */
    public void subscribeAll(String subject, Consumer<byte[]> handler) {
        Consumer<byte[]> deduped = data -> {
            if (legs.size() > 1 && !inboundDedup.firstSight(contentHash(subject, data))) return;
            handler.accept(data);
        };
        for (var l : legs) {
            // #8 (2026-07-19 OSS hardening) — subscribe on EVERY leg with a
            // transport, not only currently-connected ones. RelaySessionTransport
            // now records a subscription made while disconnected and binds it on
            // (re)connect, so a leg that is offline at boot no longer silently
            // misses all inbound traffic until a restart.
            if (l.transport() != null) {
                try { l.transport().subscribe(subject, deduped); }
                catch (Exception e) { log.debug("subscribeAll '{}' on {} failed: {}",
                    subject, l.cfg().url(), e.getMessage()); }
            }
        }
    }

    private List<RelayLegConfig> cfgs() {
        return legs.stream().map(Leg::cfg).toList();
    }

    /**
     * Extract the target zone token from a federation subject. Handles both
     * {@code federation.{zone}.{verb}} and the canonical
     * {@code federation.{fp}.{label}.{verb}} (the {fp} token is the zone key
     * the selector matches the directory on).
     */
    static String targetZoneFromSubject(String subject) {
        if (subject == null) return null;
        var parts = subject.split("\\.");
        if (parts.length < 3 || !"federation".equals(parts[0])) return null;
        return parts[1];
    }

    private static String contentHash(String subject, byte[] data) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(subject.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(data);
            var d = md.digest();
            var sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(Integer.toHexString(d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            // No digest → never dedup (treat as unique).
            return null;
        }
    }
}
