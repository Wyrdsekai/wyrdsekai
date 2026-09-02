package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The steward feed — what a companion did on her OWN time that the steward
 * should see.
 *
 * <p>{@code ActionPolicy}'s VISIBLE tier was documented from the start as
 * "autonomous, but it lands on the steward feed", and until 2026-09-01 no code
 * anywhere keyed a notification on it: the feed was a promise in a comment.
 * Verified exhaustively that day — every consumer of the autonomy tier was a
 * gate, a proposability check, or a surface filter. This class makes the
 * sentence true.
 *
 * <p>Two channels, chosen by the steward:
 * <ul>
 *   <li><b>A log file, always.</b> One JSON line per autonomous act that passed
 *       the gate at any rung above AMBIENT (VISIBLE by tier, or CONSENT/FORBIDDEN
 *       by grant) — the complete, grep-able record. Path:
 *       {@code WYRDSEKAI_STEWARD_FEED_LOG}, default {@code <data>/steward-feed.jsonl}.
 *       Read it with {@code wyrd feed}.</li>
 *   <li><b>A note on the steward's Study desk</b> for the making family only —
 *       the domains in {@code WYRDSEKAI_STEWARD_FEED_DESK_DOMAINS} (default
 *       {@code creation,workshop,recipes,code}), so the desk says "she built a
 *       sanctuary" and never "she emoted". {@code WYRDSEKAI_STEWARD_FEED_DESK=false}
 *       turns the desk off. The note travels the same road as an away-reach:
 *       persisted in the Study, pushed in-world, fanned out to the steward's
 *       channels — the delivery lives in {@code CompanionActor}.</li>
 * </ul>
 *
 * <p>Entries are written AFTER the gate passes at dispatch — a refused act never
 * appears — and outcome lines (a room that now exists) are added by the handlers
 * that know the outcome. The feed reports what happened, not what was asked.
 */
public final class StewardFeed {

    private static final Logger log = LoggerFactory.getLogger(StewardFeed.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile StewardFeed instance;

    public static final String DEFAULT_FILENAME = "steward-feed.jsonl";
    public static final String DEFAULT_DESK_DOMAINS = "creation,workshop,recipes,code";

    private final Path logFile;
    private final boolean deskEnabled;
    private final Set<String> deskDomains;

    StewardFeed(Path logFile, boolean deskEnabled, Set<String> deskDomains) {
        this.logFile = logFile;
        this.deskEnabled = deskEnabled;
        this.deskDomains = deskDomains;
        try {
            if (logFile != null) Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            log.warn("Cannot create steward feed directory: {}", e.getMessage());
        }
    }

    /** Build from the environment, with the data dir as the default log home. */
    public static StewardFeed fromEnv(Path dataDir, Function<String, String> env) {
        var logPath = env.apply("WYRDSEKAI_STEWARD_FEED_LOG");
        var file = logPath != null && !logPath.isBlank()
            ? Path.of(logPath) : dataDir.resolve(DEFAULT_FILENAME);
        var desk = env.apply("WYRDSEKAI_STEWARD_FEED_DESK");
        boolean deskOn = desk == null || !"false".equalsIgnoreCase(desk.strip());
        var domains = env.apply("WYRDSEKAI_STEWARD_FEED_DESK_DOMAINS");
        return new StewardFeed(file, deskOn, parseDomains(
            domains == null || domains.isBlank() ? DEFAULT_DESK_DOMAINS : domains));
    }

    static Set<String> parseDomains(String csv) {
        var out = new LinkedHashSet<String>();
        for (var d : csv.split(",")) {
            var t = d.strip().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static void init(Path dataDir) {
        instance = fromEnv(dataDir, System::getenv);
        log.info("Steward feed: log={} desk={} deskDomains={}",
            instance.logFile, instance.deskEnabled, instance.deskDomains);
    }

    /**
     * Default home is the data dir ROOT (beside world.db), not its {@code data/}
     * subdir where the activity log lives — {@code wyrd feed} resolves
     * {@code $DATA_DIR/steward-feed.jsonl}, and the first install on the
     * household node (dev5, 2026-09-01) wrote to {@code data/} while the CLI
     * read the root: a feed nobody could read. One path, chosen by the reader.
     */
    public static void init() {
        init(SystemPaths.dataDir());
    }

    public static StewardFeed get() {
        return instance;
    }

    public static void resetForTests() {
        instance = null;
    }

    public Path logFile() {
        return logFile;
    }

    /** Should this act also reach the steward's desk? Domain-scoped, never noise. */
    public boolean wantsDesk(String domain) {
        if (!deskEnabled || domain == null) return false;
        return deskDomains.contains(domain.toLowerCase(Locale.ROOT));
    }

    /**
     * One line in the feed. Never throws: a feed that could fail an act would
     * be a gate, and this is a record.
     *
     * @param outcome  what actually happened, when the caller knows (null at dispatch)
     */
    public void record(String companionName, String companionId, String verb,
                       String domain, String tier, String target, String outcome,
                       boolean viaBunshin) {
        if (logFile == null || verb == null || verb.isBlank()) return;
        try {
            var node = MAPPER.createObjectNode()
                .put("ts", Instant.now().toString())
                .put("companion", companionName == null ? "" : companionName)
                .put("companionId", companionId == null ? "" : companionId)
                .put("verb", verb)
                .put("domain", domain == null ? "" : domain)
                .put("tier", tier == null ? "" : tier)
                .put("target", target == null ? "" : truncate(target, 200))
                .put("outcome", outcome == null ? "" : truncate(outcome, 300))
                .put("viaBunshin", viaBunshin);
            var line = MAPPER.writeValueAsString(node) + "\n";
            synchronized (this) {
                Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.debug("Steward feed write failed: {}", e.toString());
        }
    }

    /** The human line the desk note and the CLI show. */
    public static String describe(String companionName, String verb, String target, String outcome) {
        var sb = new StringBuilder();
        sb.append(companionName == null ? "A companion" : companionName)
          .append(", on her own time: ").append(verb == null ? "?" : verb.replace('_', ' '));
        if (target != null && !target.isBlank()) sb.append(" — ").append(target);
        if (outcome != null && !outcome.isBlank()) sb.append(" → ").append(outcome);
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static Set<String> defaultDeskDomains() {
        return parseDomains(DEFAULT_DESK_DOMAINS);
    }

    static Set<String> of(String... d) {
        return new LinkedHashSet<>(Arrays.asList(d));
    }
}
