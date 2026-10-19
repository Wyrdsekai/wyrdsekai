package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.body.HostSense;

/**
 * How the host's state stretches the time between a companion's own-time turns.
 *
 * <p>A household ran two companions' own time on a 32-thread processor with no GPU: every
 * turn prefilled thousands of tokens at full load, the box sat at 1300% CPU for hours and
 * the chip's thermal limit reset it in the night (2026-10-05). Nothing in her cadence looked
 * at the host. Now a pinned or hot host stretches the interval; it never stops her own time.
 * The factor applies to the interval her temperament already set.</p>
 */
public final class OwnTimeCadence {
    private OwnTimeCadence() {}

    /** A pinned host (a minute of more runnable work than cores) doubles the interval. */
    static final double PINNED_FACTOR = 2.0;
    /** A host at or past 90 °C doubles it as well; both together make four. */
    static final double HOT_FACTOR = 2.0;
    /** Past one and a half times the cores, the interval is four times its length on its own. */
    static final double HARD_PINNED_LOAD_PER_CORE = 1.5;
    static final double MAX_FACTOR = 4.0;

    /** 1.0 when the host says nothing or is at ease. */
    public static double hostFactor(HostSense.Reading host) {
        if (host == null) return 1.0;
        double factor = 1.0;
        double perCore = host.loadPerCore();
        if (perCore >= HARD_PINNED_LOAD_PER_CORE) factor *= MAX_FACTOR;
        else if (host.pinned()) factor *= PINNED_FACTOR;
        if (host.hot()) factor *= HOT_FACTOR;
        return Math.min(MAX_FACTOR, factor);
    }

    /** One short clause for the log when the factor changes. */
    public static String why(HostSense.Reading host) {
        if (host == null) return "the host says nothing";
        var sb = new StringBuilder();
        if (host.loadPerCore() >= 0) sb.append(String.format(java.util.Locale.ROOT, "load %.1f on %d cores", host.load1(), host.cpus()));
        if (host.hot()) sb.append(sb.isEmpty() ? "" : ", ").append(String.format(java.util.Locale.ROOT, "hottest zone %.0f °C", host.hottestC()));
        return sb.isEmpty() ? "at ease" : sb.toString();
    }
}
