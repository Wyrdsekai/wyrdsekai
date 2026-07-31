package org.wyrdsekai.between;

import org.wyrdsekai.between.federation.ZoneManifest;
import org.wyrdsekai.core.config.RelayLegConfig;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * picks the best SHARED relay for a directed
 * cross-zone send.
 *
 * <p>Given this zone's own legs and a peer's advertised relay set, it returns
 * the single relay both can reach (the intersection), filtered by the privacy
 * rail and ordered by a deterministic cost metric. When there is no shared
 * relay it returns empty — the caller must NOT silently fall back to a
 * non-shared or public path (§3.3.2), it must deny.</p>
 *
 * <p>Pure + deterministic so both peers, computing independently, agree on the
 * same chosen relay (they must, since both publish/subscribe on the chosen
 * subject).</p>
 */
public final class RelayPathSelector {

    private RelayPathSelector() {}

    /** The chosen leg, or a structured "no shared relay" outcome. */
    public record Choice(String url, RelayLegConfig leg) {}

    /**
     * Pick the relay to send to a peer over.
     *
     * @param myLegs       this zone's configured legs (already privacy-filtered
     *                     by {@code WyrdConfig.relayLegs()} — no public leg under
     *                     a private floor).
     * @param peerRelays   the peer's advertised relay set (may be empty/null for
     *                     a pre-multihoming peer → treated as reachable only on
     *                     {@code peerFallbackUrl}).
     * @param peerFallbackUrl the peer's {@code natsUrl} (legacy single-relay), used
     *                     when {@code peerRelays} is empty.
     * @param myFloor      this zone's privacy floor (R1: a PRIVATE-floor zone never
     *                     egresses over a PUBLIC leg).
     * @return the chosen leg, or empty when there is no shared, rail-compliant relay.
     */
    public static Optional<Choice> pick(List<RelayLegConfig> myLegs,
                                        List<ZoneManifest.RelayAdvert> peerRelays,
                                        String peerFallbackUrl,
                                        RelayLegConfig.Visibility myFloor) {
        if (myLegs == null || myLegs.isEmpty()) return Optional.empty();

        // The set of relay URLs the peer is reachable on.
        var peerUrls = new HashSet<String>();
        if (peerRelays != null && !peerRelays.isEmpty()) {
            for (var r : peerRelays) if (r != null && r.url() != null) peerUrls.add(norm(r.url()));
        } else if (peerFallbackUrl != null && !peerFallbackUrl.isBlank()) {
            peerUrls.add(norm(peerFallbackUrl));
        }
        if (peerUrls.isEmpty()) return Optional.empty();

        // Intersection ∩ privacy rail (R1): a private-floor zone must not egress
        // over a public leg, even if shared.
        var candidates = myLegs.stream()
            .filter(leg -> peerUrls.contains(norm(leg.url())))
            .filter(leg -> !(myFloor == RelayLegConfig.Visibility.PRIVATE && leg.isPublic()))
            .sorted(COST)
            .toList();

        if (candidates.isEmpty()) return Optional.empty();   // §3.3.2 — deny, no silent fallback
        var chosen = candidates.get(0);
        return Optional.of(new Choice(chosen.url(), chosen));
    }

    /**
     * Cost metric (§3.3.1), ascending = preferred first:
     *   1. visibility — PRIVATE before PUBLIC (keep traffic off the commons);
     *   2. locality   — LAN/loopback/RFC-1918 before public-routable;
     *   3. lexicographic by url — deterministic tiebreak so both peers agree.
     */
    static final Comparator<RelayLegConfig> COST =
        Comparator.<RelayLegConfig>comparingInt(l -> l.isPublic() ? 1 : 0)
            .thenComparingInt(l -> isLanAddress(l.url()) ? 0 : 1)
            .thenComparing(RelayLegConfig::url);

    /** Normalise a dial URL to host:port for set membership (scheme/creds/path stripped). */
    static String norm(String url) {
        if (url == null) return "";
        var s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (!s.contains(":")) s = s + ":4222";
        return s.toLowerCase(Locale.ROOT);
    }

    /** True if the URL's host is a LAN/loopback/RFC-1918 address (locality preference). */
    static boolean isLanAddress(String url) {
        var hostPort = norm(url);
        var host = hostPort.contains(":") ? hostPort.substring(0, hostPort.lastIndexOf(':')) : hostPort;
        if (host.equals("localhost") || host.startsWith("127.")) return true;
        if (host.endsWith(".lan") || host.endsWith(".local") || host.endsWith(".home")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255
            int dot = host.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(host.substring(4, dot));
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignore) { /* not an IP */ }
            }
        }
        return false;
    }
}
