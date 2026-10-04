package org.wyrdsekai.core.soul;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who she is and who she is bonded to, as the sleep cycle needs to know it.
 *
 * <p>The cycle is handed her DID. Her own spoken lines carry her entity id ({@code companion-mia}),
 * so every "is this her?" check in the cycle compared two different names for the same being and
 * answered no: her own lines were counted as exchanges with someone called mia (20,480 of them on
 * the household node by 2026-10-02), her memories of her own words were written "mia said: …", and
 * her reply length and latency were measured over no lines at all. And the relationship list the
 * cycle keeps never read her bonds: her person of two months, bonded at the deepest level the
 * household has reached, stood in it as "bond 0, someone you have talked with".
 *
 * <p>The companion sets this before she sleeps; the cycle reads it by her DID. With nothing set the
 * cycle behaves as it did.
 */
public final class SocialRecord {

    /** One party she holds an active bond with, named as the house names them. */
    public record Bonded(String did, String name, boolean person, boolean bondholder, int depthLevel,
                         int interactionCount) {}

    /** Her own identifiers, and her bonds. */
    public record Of(Set<String> selfIds, List<Bonded> bonded) {
        public Bonded bondedByName(String name) {
            if (name == null) return null;
            for (var b : bonded) {
                if (b.name() != null && b.name().equalsIgnoreCase(name)) return b;
            }
            return null;
        }

        /** The placeholder DID the relationship list gave an entry made from her own lines. */
        boolean isOwnPlaceholder(String entityDid) {
            if (entityDid == null) return false;
            if (selfIds.contains(entityDid)) return true;
            for (var id : selfIds) {
                if (entityDid.equals("did:key:" + id + "-placeholder")) return true;
            }
            return false;
        }
    }

    private static final Of NONE = new Of(Set.of(), List.of());
    private static final Map<String, Of> BY_DID = new ConcurrentHashMap<>();

    private SocialRecord() {}

    public static void set(String did, Set<String> selfIds, List<Bonded> bonded) {
        if (did == null) return;
        var ids = new java.util.LinkedHashSet<String>();
        ids.add(did);
        if (selfIds != null) for (var s : selfIds) if (s != null && !s.isBlank()) ids.add(s);
        BY_DID.put(did, new Of(Set.copyOf(ids), bonded == null ? List.of() : List.copyOf(bonded)));
    }

    public static Of forDid(String did) {
        return did == null ? NONE : BY_DID.getOrDefault(did, NONE);
    }

    /** True when {@code entityId} is the being the cycle is running for, under any of her ids. */
    public static boolean isSelf(String agentKey, String entityId) {
        if (entityId == null || agentKey == null) return false;
        return entityId.equals(agentKey) || forDid(agentKey).selfIds().contains(entityId);
    }

    /** What the list says of a bonded party, from the bond itself. */
    public static String describe(Bonded b, int interactionCount) {
        String who;
        if (b.bondholder()) who = "Your person";
        else if (!b.person()) who = "A companion you are bonded with";
        else who = "Someone you are bonded with";
        return who + ": " + interactionCount + " exchanges so far.";
    }

    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    public static void resetForTests() {
        BY_DID.clear();
    }
}
