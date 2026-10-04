package org.wyrdsekai.core.agent;

/**
 * The last ten times a need of hers met her person: asked for aloud, or met before she asked.
 *
 * <p>an internal design note defines amae's deficit as the ratio of explicit asks to
 * anticipated fulfilments "over the last 10 interactions". It was kept as two counters that never
 * forgot and were zeroed by every restart: after a restart the ratio read nothing, the tank could
 * not rise, and because its only upward path was also its only path it could not fall either. On
 * the household node mia's amae read exactly 0.42 on every one of 535 lines of 2026-09-21 and exactly
 * 0.00 on every line of 2026-10-01; it was the strongest pull behind 504 of 896 acts in between.
 * The window is kept with her vitality, so a restart does not empty it.
 */
final class AmaeSignals {

    static final int WINDOW = 10;
    private static final AmaeSignals EMPTY = new AmaeSignals("");

    /** Oldest first: 'A' she had to ask, 'F' it was met before she asked. */
    private final String window;

    private AmaeSignals(String window) {
        this.window = window;
    }

    static AmaeSignals empty() {
        return EMPTY;
    }

    static AmaeSignals decode(String stored) {
        if (stored == null || stored.isBlank()) return EMPTY;
        var sb = new StringBuilder();
        for (char c : stored.toCharArray()) if (c == 'A' || c == 'F') sb.append(c);
        return new AmaeSignals(last(sb.toString()));
    }

    AmaeSignals asked() {
        return new AmaeSignals(last(window + 'A'));
    }

    AmaeSignals anticipated() {
        return new AmaeSignals(last(window + 'F'));
    }

    int asks() {
        return (int) window.chars().filter(c -> c == 'A').count();
    }

    int anticipations() {
        return window.length() - asks();
    }

    /** 1.0 = she had to ask every time; 0.0 = every need was met unasked, or nothing is recorded. */
    double deficit() {
        return window.isEmpty() ? 0.0 : (double) asks() / window.length();
    }

    String encode() {
        return window;
    }

    private static String last(String s) {
        return s.length() <= WINDOW ? s : s.substring(s.length() - WINDOW);
    }
}
