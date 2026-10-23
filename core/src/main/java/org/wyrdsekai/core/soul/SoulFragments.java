package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Her fragments, one per id. Two fragments with one id reached the store on the household node
 * (a scene woven and the same scene in the forge's result), the fragment table refused the whole
 * set on its primary key, and her fragments stopped reaching the table she is loaded from at a
 * restart (2026-10-23). The newer of the two, the later in the list, is kept, and the id is named
 * in the log; nothing of hers is refused for it.
 */
public final class SoulFragments {

    private static final Logger log = LoggerFactory.getLogger(SoulFragments.class);

    private SoulFragments() {}

    /** The list with one fragment per id, the later one kept; the same list when every id is its own. */
    public static List<SoulFragment> uniqueById(List<SoulFragment> fragments) {
        if (fragments == null || fragments.size() < 2) return fragments;
        var byId = new LinkedHashMap<String, SoulFragment>();
        var twice = new ArrayList<String>();
        for (var f : fragments) {
            if (f == null) continue;
            if (f.id() == null) { byId.put("\u0000" + System.identityHashCode(f), f); continue; }
            if (byId.put(f.id(), f) != null && !twice.contains(f.id())) twice.add(f.id());
        }
        if (twice.isEmpty()) return fragments;
        log.warn("Her record held two fragments named {}; the newer is kept", twice);
        return new ArrayList<>(byId.values());
    }

    /** The manifest with one fragment per id; the same manifest when nothing repeats. */
    public static SoulManifest withUniqueFragmentIds(SoulManifest m) {
        if (m == null || m.soulFragments() == null) return m;
        var unique = uniqueById(m.soulFragments());
        return unique == m.soulFragments() ? m : m.withFragments(unique);
    }
}
