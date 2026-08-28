package org.wyrdsekai.core.room;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;

/**
 * Names for things that appear in a room: simple enough to type, distinct enough to mean
 * one thing.
 *
 * <p>A room is addressed by NAME — {@code get codex}, {@code use codex}. Two objects
 * sharing a name make both unaddressable. Live on the household node 2026-08-20: two
 * backend-authored artifacts were both placed as "codex", and the steward's session shows
 * exactly what that costs — {@code get codex} took one, {@code use codex} worked, then a
 * second {@code get codex} left two in hand and {@code use codex} answered
 * <i>"Error [not_found]: No such object: codex"</i>. He had done nothing wrong.
 *
 * <p>The rule is deliberately boring, because a name a person has to type must be. Keep
 * the desired name if it is free; otherwise append {@code -2}, {@code -3} and so on. No
 * hashes, no ids, no timestamps — {@code library_storyteller-2} is typeable and obvious,
 * {@code codex-41e7c871} is neither.
 */
public final class ObjectNaming {

    /** Cap the suffix search; past this something is wrong with the caller, not the name. */
    private static final int MAX_SUFFIX = 99;

    private ObjectNaming() {}

    /**
     * A name for this object that nothing else in the room answers to.
     *
     * @param desired   the name we would like — a manifest name, a template name
     * @param taken     names already in use in the room (case-insensitive)
     * @param fallback  used when {@code desired} is null or blank
     */
    public static String unique(String desired, Collection<String> taken, String fallback) {
        // Do NOT mangle the name. Room objects legitimately carry multi-word names —
        // "test console", "cushioned bench", "stone water vessel" — and the world is
        // addressed by exactly those strings. An earlier cut ran every name through
        // normalise(), turning "mode dial" into "mode_dial" and breaking
        // `use mode dial set on loud`, because the split between name and args no longer
        // matched anything. normalise() is for turning a PATH or FILENAME into a name;
        // it is not for names that are already names.
        var base = desired == null ? "" : desired.trim();
        if (base.isEmpty()) base = fallback == null ? "" : fallback.trim();
        if (base.isEmpty()) base = "thing";

        var used = new HashSet<String>();
        if (taken != null) {
            for (var t : taken) {
                if (t != null) used.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (!used.contains(base.toLowerCase(Locale.ROOT))) return base;
        for (int n = 2; n <= MAX_SUFFIX; n++) {
            var candidate = base + "-" + n;
            if (!used.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return base + "-" + MAX_SUFFIX;
    }

    /**
     * Trim a name down to something a person can type: no paths, no extension, no spaces
     * or punctuation beyond {@code -} and {@code _}.
     */
    static String normalise(String raw) {
        if (raw == null) return "";
        var s = raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.toLowerCase(Locale.ROOT).endsWith(".js")) s = s.substring(0, s.length() - 3);
        s = s.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_{2,}", "_");
        s = s.replaceAll("^[_-]+", "").replaceAll("[_-]+$", "");
        return s;
    }
}
