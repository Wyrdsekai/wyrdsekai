package org.wyrdsekai.between.federation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bookkeeping behind the zone ping: which ping is out to which partner, and when each
 * partner was last heard from. A zone gives no other liveness signal; the body's map attaches
 * one door per active partner from this, open while the partner keeps answering.
 *
 * <p>Pure: the actor sends the pings and feeds the replies in. Any message from a partner
 * counts as hearing from it, not only a ping reply, so a chatty partner is never numb between
 * pings.</p>
 */
public final class ZoneDoors {

    /**
     * One partner zone as the body sees it. {@code lastSeen} is null when it has never answered;
     * {@code hosts} are the addresses its manifest gave, for shutting the door.
     */
    public record Door(String zoneId, String zoneName, Instant lastSeen, List<String> hosts, Instant firstAsked) {
        /**
         * Whether the door can be judged yet. A partner that has answered can; one that has
         * never answered can only be called closed once it has had a fair chance to: on the
         * first live federation the door was born numb, with a "went quiet" mark, because it
         * was attached seconds before the first ping came back.
         */
        public boolean knowable(Instant now, java.time.Duration fairChance) {
            return lastSeen != null || (firstAsked != null && java.time.Duration.between(firstAsked, now).compareTo(fairChance) >= 0);
        }
    }

    private final Map<String, String> pendingPings = new HashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Instant> firstAsked = new ConcurrentHashMap<>();

    /** Forgets last round's unanswered pings and mints one query id per partner. */
    public Map<String, String> nextRound(List<String> partners) {
        return nextRound(partners, Instant.now());
    }

    public Map<String, String> nextRound(List<String> partners, Instant now) {
        pendingPings.clear();
        for (var p : partners) {
            pendingPings.put("ping-" + UUID.randomUUID(), p);
            firstAsked.putIfAbsent(p, now);
        }
        return Map.copyOf(pendingPings);
    }

    /** A reply came back for a query id. True when it was one of ours, in which case the partner is now seen. */
    public boolean replied(String queryId, Instant now) {
        var partner = pendingPings.remove(queryId);
        if (partner == null) return false;
        heardFrom(partner, now);
        return true;
    }

    /** A partner spoke to us, in any form: the door to it is open now. */
    public void heardFrom(String partnerZoneId, Instant now) {
        if (partnerZoneId != null && !partnerZoneId.isBlank()) lastSeen.put(partnerZoneId, now);
    }

    public Instant lastSeen(String partnerZoneId) { return lastSeen.get(partnerZoneId); }

    /** The doors, in the partners' order, named where a manifest gave a name. */
    public List<Door> doors(List<String> partners, Map<String, String> names) {
        return doors(partners, names, Map.of());
    }

    public List<Door> doors(List<String> partners, Map<String, String> names, Map<String, List<String>> hosts) {
        var out = new ArrayList<Door>();
        for (var p : partners) {
            var name = names == null ? null : names.get(p);
            var h = hosts == null ? null : hosts.get(p);
            out.add(new Door(p, name == null || name.isBlank() ? p : name, lastSeen.get(p), h == null ? List.of() : List.copyOf(h),
                firstAsked.get(p)));
        }
        return out;
    }

    /** The host part of a URL such as {@code nats://host:4222} or {@code https://host/path}; null when there is none. */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            var u = java.net.URI.create(url.strip());
            var h = u.getHost();
            if (h == null && u.getSchemeSpecificPart() != null) {
                var ssp = u.getSchemeSpecificPart().replaceFirst("^//", "");
                int end = ssp.indexOf('/'); if (end > 0) ssp = ssp.substring(0, end);
                int at = ssp.lastIndexOf('@'); if (at >= 0) ssp = ssp.substring(at + 1);
                int colon = ssp.lastIndexOf(':'); if (colon > 0 && ssp.indexOf(':') == colon) ssp = ssp.substring(0, colon);
                h = ssp.isBlank() ? null : ssp;
            }
            return h;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
