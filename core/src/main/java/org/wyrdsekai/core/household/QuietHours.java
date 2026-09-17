package org.wyrdsekai.core.household;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The household's quiet hours, kept by the rooms themselves.
 *
 * <p>{@code WYRDSEKAI_QUIET_HOURS=22:00-07:00}. During quiet hours a visitor is not let
 * into a room and may not speak in one; residents and companions are not bound. Until
 * 2026-09-13 quiet hours existed only as an advisory line in a companion's own policy
 * prompt — manners, not architecture. A visitor who arrived at 3 a.m. was bound by
 * nothing but whoever happened to be visiting.</p>
 */
public final class QuietHours {

    private QuietHours() {}

    public record Window(LocalTime start, LocalTime end) {
        public boolean contains(LocalTime t) {
            if (start.equals(end)) return false;
            if (start.isBefore(end)) return !t.isBefore(start) && t.isBefore(end);
            return !t.isBefore(start) || t.isBefore(end);          // overnight wrap
        }
    }

    /** Parse "22:00-07:00"; empty for blank or malformed input. */
    public static Optional<Window> parse(String spec) {
        if (spec == null || spec.isBlank()) return Optional.empty();
        var parts = spec.trim().split("-");
        if (parts.length != 2) return Optional.empty();
        try {
            return Optional.of(new Window(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static volatile String configured;
    private static volatile java.util.function.Supplier<String> record;
    private static volatile String cached;
    private static volatile long cachedAt;
    private static final long CACHE_MS = 30_000;

    /** Set once at boot from the household config; blank means no quiet hours. */
    public static void configure(String spec) {
        configured = spec;
        cached = null;
    }

    /**
     * The record's say (2026-09-16, sleep plan item 2): a window the steward set in the
     * household config table overrides the boot-time setting, and {@code off} turns quiet
     * hours off. Read through a short cache; the rooms ask on every entry.
     */
    public static void install(java.util.function.Supplier<String> fromRecord) {
        record = fromRecord;
        cached = null;
    }

    /** Forget the cached record value, after the steward changed it. */
    public static void refresh() {
        cached = null;
    }

    /** The window in force: the record's if set ("off" = none), else the boot-time config. */
    public static String spec() {
        var r = record;
        if (r != null) {
            var now = System.currentTimeMillis();
            var c = cached;
            if (c == null || now - cachedAt > CACHE_MS) {
                try { c = r.get(); } catch (RuntimeException e) { c = null; }
                cached = c == null ? "" : c;
                cachedAt = now;
                c = cached;
            }
            if (c != null && !c.isBlank()) return "off".equalsIgnoreCase(c.trim()) ? "" : c;
        }
        return configured == null ? "" : configured;
    }

    public static Optional<Window> window() {
        return parse(spec());
    }

    /** Is it quiet now, by the node's clock? */
    public static boolean isQuiet() {
        return isQuiet(LocalTime.now());
    }

    public static boolean isQuiet(LocalTime now) {
        return window().map(w -> w.contains(now)).orElse(false);
    }

    /** "The household keeps quiet hours until 07:00", for a door or a hush. */
    public static String until() {
        return window().map(w -> "The household keeps quiet hours until " + w.end() + ".").orElse("");
    }
}
