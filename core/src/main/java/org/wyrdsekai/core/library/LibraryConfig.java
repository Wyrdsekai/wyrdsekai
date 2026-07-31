package org.wyrdsekai.core.library;

/**
 * Configuration for the Library subsystem.
 * Standalone record — no dependency on application config framework.
 */
public record LibraryConfig(
    int syncIntervalHours,
    int cleanupUnusedDays,
    int ftsSearchLimit,
    OutputSanitizer.SanitizationMode sanitizationMode
) {
    public static LibraryConfig defaults() {
        return new LibraryConfig(24, 30, 20, OutputSanitizer.SanitizationMode.WARN);
    }
}
