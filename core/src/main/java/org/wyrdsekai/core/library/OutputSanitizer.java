package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Scans tool responses for prompt injection before they reach agents.
 * Consumes patterns from SecurityPatternManager (INJECTION type).
 * <p>
 * Three modes:
 * <ul>
 *   <li>BLOCK — strip matching content from the response</li>
 *   <li>WARN — pass through but flag the match</li>
 *   <li>LOG_ONLY — log the match, no modification or flag</li>
 * </ul>
 * Adapted from CodePlane. Self-contained — no domain-specific imports.
 */
public final class OutputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(OutputSanitizer.class);

    private final SecurityPatternManager patternManager;
    private volatile SanitizationMode mode;
    private volatile List<CompiledPattern> compiledPatterns;

    public OutputSanitizer(SecurityPatternManager patternManager, SanitizationMode mode) {
        this.patternManager = patternManager;
        this.mode = mode;
        this.compiledPatterns = List.of();
    }

    /** Reload patterns from the SecurityPatternManager. Call after pattern updates. */
    public void reloadPatterns() throws SQLException {
        var raw = patternManager.getPatterns(SecurityPatternManager.PatternType.INJECTION);
        var compiled = new ArrayList<CompiledPattern>();
        for (var sp : raw) {
            try {
                compiled.add(new CompiledPattern(sp, Pattern.compile(sp.regex())));
            } catch (PatternSyntaxException e) {
                log.warn("Invalid regex for pattern '{}': {}", sp.name(), e.getMessage());
            }
        }
        this.compiledPatterns = List.copyOf(compiled);
        log.info("OutputSanitizer loaded {} injection patterns", compiled.size());
    }

    /** Scan a tool response for prompt injection. */
    public SanitizationResult sanitize(String toolName, String response) {
        if (response == null || response.isEmpty()) {
            return new SanitizationResult(true, List.of(), response);
        }

        var matches = new ArrayList<PatternMatch>();
        var patterns = this.compiledPatterns;

        for (CompiledPattern cp : patterns) {
            var matcher = cp.compiled().matcher(response);
            while (matcher.find()) {
                matches.add(new PatternMatch(
                    cp.pattern().name(), cp.pattern().category(),
                    cp.pattern().severity(), matcher.start(), matcher.end(),
                    response.substring(matcher.start(), Math.min(matcher.end(), matcher.start() + 100))));
            }
        }

        if (matches.isEmpty()) {
            return new SanitizationResult(true, List.of(), response);
        }

        // Log all matches
        for (PatternMatch m : matches) {
            log.warn("Injection detected in tool '{}' output: pattern={} category={} severity={} snippet='{}'",
                toolName, m.patternName(), m.category(), m.severity(),
                m.matchedSnippet().length() > 50
                    ? m.matchedSnippet().substring(0, 50) + "..."
                    : m.matchedSnippet());
        }

        String sanitizedResponse = response;
        boolean clean = false;

        switch (mode) {
            case BLOCK -> {
                StringBuilder sb = new StringBuilder(response);
                var sorted = matches.stream()
                    .sorted((a, b) -> Integer.compare(b.startPos(), a.startPos()))
                    .toList();
                for (PatternMatch m : sorted) {
                    sb.replace(m.startPos(), m.endPos(), "[BLOCKED]");
                }
                sanitizedResponse = sb.toString();
            }
            case WARN -> {
                sanitizedResponse = response;
            }
            case LOG_ONLY -> {
                sanitizedResponse = response;
                clean = true;
            }
        }

        return new SanitizationResult(clean, matches, sanitizedResponse);
    }

    public void setMode(SanitizationMode mode) {
        this.mode = mode;
        log.info("OutputSanitizer mode changed to {}", mode);
    }

    public SanitizationMode getMode() {
        return mode;
    }

    public int patternCount() {
        return compiledPatterns.size();
    }

    // --- Data records ---

    public record SanitizationResult(
        boolean clean,
        List<PatternMatch> matches,
        String sanitizedResponse
    ) {}

    public record PatternMatch(
        String patternName,
        String category,
        SecurityPatternManager.Severity severity,
        int startPos,
        int endPos,
        String matchedSnippet
    ) {}

    public enum SanitizationMode {
        BLOCK,
        WARN,
        LOG_ONLY
    }

    private record CompiledPattern(
        SecurityPatternManager.SecurityPattern pattern,
        Pattern compiled
    ) {}
}
