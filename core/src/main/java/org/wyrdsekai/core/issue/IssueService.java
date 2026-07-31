package org.wyrdsekai.core.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.core.agent.DriveSnapshotRegistry;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.observability.RedactingLayout;
import org.wyrdsekai.core.persistence.ConversationTurnStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * records {@code /issue} and {@code /feedback} entries
 * with an automatic context bundle (recent conversation turns, drive
 * snapshot, zone, build version, WARN/ERROR log tail) and persists them to
 * {@code <dataRoot>/issues.jsonl}.
 *
 * <p>Capture is fail-soft throughout: a missing turn store, absent log file,
 * or unpublished drive snapshot degrades that field to null/empty — filing a
 * report must never itself fail. Feedback entries skip conversation and log
 * capture entirely (spec §2 privacy contract).</p>
 *
 * <p>Local-only by default: nothing here transmits anywhere. Export renders
 * a self-contained markdown bundle the steward chooses to share.</p>
 */
public final class IssueService {

    private static final Logger log = LoggerFactory.getLogger(IssueService.class);
    // Issue holds only primitives/strings/maps — no time module needed.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_TURNS = 10;
    private static final int MAX_LOG_LINES = 50;
    /** Read window from the end of the log before WARN/ERROR filtering. */
    private static final int LOG_SCAN_LINES = 600;

    private static volatile IssueService instance;

    private final Path file;
    private final String jdbcUrl;   // nullable — no conversation capture without it
    private final Path logFile;     // nullable — no log tail without it
    private final RedactingLayout redactor = new RedactingLayout();
    private final List<Issue> issues = new ArrayList<>();

    private IssueService(Path dataRoot, String jdbcUrl, Path logFile) {
        this.file = dataRoot.resolve("issues.jsonl");
        this.jdbcUrl = jdbcUrl;
        this.logFile = logFile;
        load();
    }

    /** Idempotent singleton init — called from CoreServices and tests. */
    public static synchronized void init(Path dataRoot, String jdbcUrl, Path logFile) {
        if (instance == null) {
            try {
                Files.createDirectories(dataRoot);
                instance = new IssueService(dataRoot, jdbcUrl, logFile);
            } catch (IOException e) {
                log.warn("IssueService init failed ({}): {}", dataRoot, e.getMessage());
            }
        }
    }

    /** Nullable — surfaces must degrade gracefully when capture is unwired. */
    public static IssueService get() {
        return instance;
    }

    /** Test seam. */
    public static synchronized void reset() {
        instance = null;
    }

    /**
     * File an entry. For {@link Issue#KIND_ISSUE} the context bundle is
     * captured now; {@link Issue#KIND_FEEDBACK} records text only.
     *
     * @param companionDid  companion whose drive snapshot to capture (nullable)
     * @param bondholderDid reporter DID for conversation-turn capture (nullable)
     */
    public synchronized Issue file(String kind, String text, String reporter,
                                   String surface, String companionDid,
                                   String bondholderDid) {
        boolean fullCapture = Issue.KIND_ISSUE.equals(kind);
        var issue = new Issue(
            UUID.randomUUID().toString().substring(0, 8),
            kind, Issue.STATUS_OPEN,
            System.currentTimeMillis(),
            reporter, surface, text,
            captureZoneId(),
            AppVersion.get().toString(),
            fullCapture ? companionDid : null,
            fullCapture ? captureTurns(companionDid, bondholderDid) : null,
            fullCapture ? captureDriveSnapshot(companionDid) : null,
            fullCapture ? captureLogTail() : null);
        issues.add(issue);
        append(issue);
        log.info("[issue] filed {} {} via {} by {}", kind, issue.id(), surface, reporter);
        return issue;
    }

    /** All entries, newest first; {@code openOnly} filters closed ones. */
    public synchronized List<Issue> list(boolean openOnly) {
        var out = new ArrayList<Issue>();
        for (int i = issues.size() - 1; i >= 0; i--) {
            var it = issues.get(i);
            if (!openOnly || Issue.STATUS_OPEN.equals(it.status())) out.add(it);
        }
        return out;
    }

    /** Find by exact id or unique prefix. Empty when absent or ambiguous. */
    public synchronized Optional<Issue> find(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) return Optional.empty();
        Issue match = null;
        for (var it : issues) {
            if (it.id().equals(idOrPrefix)) return Optional.of(it);
            if (it.id().startsWith(idOrPrefix)) {
                if (match != null) return Optional.empty();
                match = it;
            }
        }
        return Optional.ofNullable(match);
    }

    public synchronized Optional<Issue> close(String idOrPrefix) {
        var found = find(idOrPrefix);
        if (found.isEmpty()) return Optional.empty();
        var closed = found.get().withStatus(Issue.STATUS_CLOSED);
        issues.replaceAll(it -> it.id().equals(closed.id()) ? closed : it);
        rewrite();
        return Optional.of(closed);
    }

    /** Self-contained markdown bundle — what `wyrd issue export` ships. */
    public synchronized Optional<String> exportMarkdown(String idOrPrefix) {
        return find(idOrPrefix).map(IssueService::renderMarkdown);
    }

    static String renderMarkdown(Issue it) {
        var sb = new StringBuilder();
        sb.append("# ").append(it.kind()).append(' ').append(it.id())
          .append(" (").append(it.status()).append(")\n\n");
        sb.append("- **when:** ").append(Instant.ofEpochMilli(it.tsMs())).append('\n');
        sb.append("- **reporter:** ").append(orDash(it.reporter())).append('\n');
        sb.append("- **surface:** ").append(orDash(it.surface())).append('\n');
        sb.append("- **zone:** ").append(orDash(it.zoneId())).append('\n');
        sb.append("- **build:** ").append(orDash(it.build())).append('\n');
        if (it.companionDid() != null) {
            sb.append("- **companion:** ").append(it.companionDid()).append('\n');
        }
        sb.append("\n## Report\n\n").append(it.text()).append('\n');
        if (it.recentTurns() != null && !it.recentTurns().isEmpty()) {
            sb.append("\n## Recent conversation (newest first)\n\n");
            for (var t : it.recentTurns()) {
                sb.append("- `").append(t.role()).append("` ")
                  .append(Instant.ofEpochMilli(t.tsMs())).append(": ")
                  .append(t.content().replace("\n", " ")).append('\n');
            }
        }
        if (it.driveSnapshot() != null && !it.driveSnapshot().isEmpty()) {
            sb.append("\n## Drive snapshot\n\n```json\n");
            try {
                sb.append(MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(it.driveSnapshot()));
            } catch (IOException e) {
                sb.append(it.driveSnapshot());
            }
            sb.append("\n```\n");
        }
        if (it.logTail() != null && !it.logTail().isEmpty()) {
            sb.append("\n## Log tail (WARN/ERROR)\n\n```\n");
            it.logTail().forEach(l -> sb.append(l).append('\n'));
            sb.append("```\n");
        }
        return sb.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    // ── context capture (each fail-soft) ──────────────────────────────────

    private static String captureZoneId() {
        try {
            return WyrdConfig.get().zoneId();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Issue.TurnRef> captureTurns(String companionDid, String bondholderDid) {
        if (jdbcUrl == null || bondholderDid == null) return null;
        try {
            var turns = new ConversationTurnStore(jdbcUrl)
                .recentTurns(bondholderDid, MAX_TURNS);
            if (turns.isEmpty()) return null;
            return turns.stream()
                .map(t -> new Issue.TurnRef(t.role(), t.content(), t.tsMs()))
                .toList();
        } catch (Exception e) {
            log.debug("[issue] turn capture skipped: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> captureDriveSnapshot(String companionDid) {
        if (companionDid == null) return null;
        try {
            return DriveSnapshotRegistry.get(companionDid).map(snap -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("updatedAt", snap.updatedAt().toString());
                out.put("drives", MAPPER.convertValue(snap.drives(), Map.class));
                out.put("vitality", MAPPER.convertValue(snap.vitality(), Map.class));
                return out;
            }).orElse(null);
        } catch (Exception e) {
            log.debug("[issue] drive snapshot skipped: {}", e.getMessage());
            return null;
        }
    }

    private List<String> captureLogTail() {
        if (logFile == null || !Files.isReadable(logFile)) return null;
        try {
            var all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            var window = all.subList(Math.max(0, all.size() - LOG_SCAN_LINES), all.size());
            var hits = window.stream()
                .filter(l -> l.contains("WARN") || l.contains("ERROR"))
                // Redact at CAPTURE, not export (2026-07-31): the raw log is
                // not credential-safe (RedactingLayout was never wired into
                // logback), and an issue row lives in issues.jsonl + travels
                // in exports the steward hands to strangers. Scrub tokens,
                // keys, and PII patterns before the line is ever stored.
                .map(l -> redactor.redact(l).redactedText())
                .toList();
            if (hits.isEmpty()) return null;
            return hits.subList(Math.max(0, hits.size() - MAX_LOG_LINES), hits.size());
        } catch (Exception e) {
            log.debug("[issue] log tail skipped: {}", e.getMessage());
            return null;
        }
    }

    // ── persistence (JSONL; append on file, rewrite on status change) ────

    private void load() {
        if (!Files.isReadable(file)) return;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(l -> !l.isBlank()).forEach(l -> {
                try {
                    issues.add(MAPPER.readValue(l, Issue.class));
                } catch (IOException e) {
                    log.warn("[issue] skipping malformed row: {}", e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[issue] load failed ({}): {}", file, e.getMessage());
        }
    }

    private void append(Issue issue) {
        try {
            Files.writeString(file, MAPPER.writeValueAsString(issue) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[issue] append failed ({}): {}", file, e.getMessage());
        }
    }

    private void rewrite() {
        try {
            var sb = new StringBuilder();
            for (var it : issues) {
                sb.append(MAPPER.writeValueAsString(it)).append('\n');
            }
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("[issue] rewrite failed ({}): {}", file, e.getMessage());
        }
    }
}
