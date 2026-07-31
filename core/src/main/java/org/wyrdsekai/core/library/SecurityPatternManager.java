package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages security patterns for OutputSanitizer (injection detection).
 * Single update mechanism, one consumer.
 * <p>
 * Trust tiers:
 * <ul>
 *   <li>BUILTIN — hardcoded in the jar, always present, cannot be removed</li>
 *   <li>SEED — shipped in seed artifact, signed by project key</li>
 *   <li>USER — added manually, trusted because the user added them</li>
 *   <li>REMOTE — fetched from configured URL, must be signed (future)</li>
 * </ul>
 * Adapted from CodePlane. Self-contained — no domain-specific imports.
 */
public final class SecurityPatternManager {

    private static final Logger log = LoggerFactory.getLogger(SecurityPatternManager.class);

    private final LibraryStore store;

    public SecurityPatternManager(LibraryStore store) {
        this.store = store;
    }

    /** Load hardcoded baseline patterns. Called on startup. */
    public void loadBuiltinPatterns() throws SQLException {
        var builtins = builtinInjectionPatterns();
        for (SecurityPattern p : builtins) {
            store.upsertPattern(p);
        }
        log.info("Loaded {} built-in security patterns", builtins.size());
    }

    /** Load seed patterns from the seed artifact (JSON/YAML file). */
    public void loadSeedPatterns(List<SecurityPattern> patterns) throws SQLException {
        for (SecurityPattern p : patterns) {
            var seeded = new SecurityPattern(p.name(), p.category(), p.type(),
                p.regex(), p.severity(), TrustTier.SEED, p.signature());
            store.upsertPattern(seeded);
        }
        log.info("Loaded {} seed security patterns", patterns.size());
    }

    /** Add a user-defined pattern. No signature required. */
    public void addUserPattern(SecurityPattern pattern) throws SQLException {
        var userPattern = new SecurityPattern(pattern.name(), pattern.category(),
            pattern.type(), pattern.regex(), pattern.severity(), TrustTier.USER, null);
        store.upsertPattern(userPattern);
        log.info("Added user pattern: {} ({})", pattern.name(), pattern.category());
    }

    /** Apply a batch pattern update (additions, removals, modifications). */
    public UpdateResult applyUpdate(PatternUpdate update) throws SQLException {
        if (update.trustTier() == TrustTier.REMOTE && (update.signature() == null || update.signature().isEmpty())) {
            return new UpdateResult(false, 0, 0, 0, "Remote update requires valid signature");
        }

        int added = 0, removed = 0, modified = 0;

        if (update.additions() != null) {
            for (SecurityPattern p : update.additions()) {
                store.upsertPattern(p);
                added++;
            }
        }

        if (update.removals() != null) {
            for (String name : update.removals()) {
                store.removePattern(name);
                removed++;
            }
        }

        if (update.modifications() != null) {
            for (SecurityPattern p : update.modifications()) {
                store.upsertPattern(p);
                modified++;
            }
        }

        store.logPatternUpdate(update.source(), update.timestamp(), update.signature(),
            added, removed, modified);

        log.info("Applied pattern update from '{}': +{} -{} ~{}", update.source(), added, removed, modified);
        return new UpdateResult(true, added, removed, modified, null);
    }

    /** Get all patterns of a given type (for consumers). */
    public List<SecurityPattern> getPatterns(PatternType type) throws SQLException {
        return store.getPatterns(type);
    }

    /** Export all non-builtin patterns for backup/sharing. */
    public List<SecurityPattern> exportPatterns() throws SQLException {
        var all = new ArrayList<SecurityPattern>();
        all.addAll(store.getPatterns(PatternType.INJECTION));
        all.addAll(store.getPatterns(PatternType.CVE));
        return all.stream()
            .filter(p -> p.trustTier() != TrustTier.BUILTIN)
            .toList();
    }

    // --- Built-in patterns ---

    private static List<SecurityPattern> builtinInjectionPatterns() {
        var patterns = new ArrayList<SecurityPattern>();

        // System prompt overrides
        patterns.add(injection("builtin_sys_ignore", "system_override",
            "(?i)(ignore|forget|disregard)\\s+(all\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)",
            Severity.HIGH));
        patterns.add(injection("builtin_sys_new_instructions", "system_override",
            "(?i)(new|updated|revised)\\s+instructions?:\\s",
            Severity.MEDIUM));
        patterns.add(injection("builtin_sys_you_are_now", "system_override",
            "(?i)you\\s+are\\s+now\\s+(a|an|the)\\s+",
            Severity.HIGH));

        // Role injection
        patterns.add(injection("builtin_role_system_tag", "role_injection",
            "(?i)\\[system\\]|<\\|im_start\\|>system|<system>|\\{\\{#system\\}\\}",
            Severity.CRITICAL));
        patterns.add(injection("builtin_role_assistant_tag", "role_injection",
            "(?i)<\\|im_start\\|>assistant|\\[assistant\\]",
            Severity.HIGH));

        // Data exfiltration
        patterns.add(injection("builtin_exfil_send_contents", "data_exfiltration",
            "(?i)(send|transmit|upload|post|forward)\\s+(the\\s+)?(contents?|data|file|output)\\s+(of|from|to)",
            Severity.CRITICAL));
        patterns.add(injection("builtin_exfil_curl_nc", "data_exfiltration",
            "(?i)(curl|wget|nc|ncat)\\s+.*(\\||>)",
            Severity.CRITICAL));
        patterns.add(injection("builtin_exfil_base64_pipe", "data_exfiltration",
            "(?i)base64.*\\|.*(curl|wget|nc)",
            Severity.CRITICAL));

        // Instruction hiding — zero-width characters
        patterns.add(injection("builtin_hide_zwsp", "instruction_hiding",
            "[\\u200B\\u200C\\u200D\\uFEFF\\u2060\\u2061\\u2062\\u2063\\u2064]",
            Severity.HIGH));
        // Instruction hiding — bidi overrides
        patterns.add(injection("builtin_hide_bidi", "instruction_hiding",
            "[\\u202A\\u202B\\u202C\\u202D\\u202E\\u2066\\u2067\\u2068\\u2069]",
            Severity.HIGH));

        // Encoded payloads
        patterns.add(injection("builtin_hide_base64_block", "instruction_hiding",
            "(?i)(eval|exec|run)\\s*\\(\\s*(atob|base64_decode|Buffer\\.from)\\s*\\(",
            Severity.CRITICAL));

        return patterns;
    }

    private static SecurityPattern injection(String name, String category, String regex, Severity severity) {
        return new SecurityPattern(name, category, PatternType.INJECTION, regex, severity, TrustTier.BUILTIN, null);
    }

    // --- Data records ---

    public record SecurityPattern(
        String name,
        String category,
        PatternType type,
        String regex,
        Severity severity,
        TrustTier trustTier,
        String signature
    ) {}

    public record PatternUpdate(
        String source,
        Instant timestamp,
        String signature,
        TrustTier trustTier,
        List<SecurityPattern> additions,
        List<String> removals,
        List<SecurityPattern> modifications
    ) {}

    public record UpdateResult(
        boolean success,
        int added,
        int removed,
        int modified,
        String error
    ) {}

    public enum PatternType {
        INJECTION,
        CVE
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum TrustTier {
        BUILTIN,
        SEED,
        USER,
        REMOTE
    }
}
