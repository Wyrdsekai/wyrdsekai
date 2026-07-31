package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * §M4-C — append-only outcome ledger for fired
 * proactive predictions. Each line records what happened after the agent
 * acted on a scheduled prediction:
 *
 * <ul>
 *   <li>{@link Kind#FOLLOWED_UP} — user engaged with the topic within window</li>
 *   <li>{@link Kind#DISMISSED}  — user explicitly waved it off</li>
 *   <li>{@link Kind#IGNORED}    — window expired with no engagement</li>
 * </ul>
 *
 * <p>Downstream consumer is the §M4-D Beta-Binomial calibrator, which will
 * read the JSONL stream to update per-category accept/reject thresholds.
 *
 * <p>Write-only by design: no replay, no in-memory mirror. Each ledger
 * append is a full self-contained record.
 */
public final class PredictionOutcomeLedger {

    private static final Logger log = LoggerFactory.getLogger(PredictionOutcomeLedger.class);

    public enum Kind { FOLLOWED_UP, DISMISSED, IGNORED }

    public record Entry(
        String predictionId,
        String agentId,
        String category,
        Kind kind,
        Instant firedAt,
        Instant observedAt,
        String signal       // matched token / dismissal phrase / "expired"
    ) {}

    private final Path ledgerFile;

    public PredictionOutcomeLedger(Path dataDir) {
        this.ledgerFile = dataDir == null
            ? null
            : dataDir.resolve("m4").resolve("outcomes.jsonl");
    }

    /** Append a single outcome record. Best-effort — IO failures are logged, not thrown. */
    public void append(Entry entry) {
        if (ledgerFile == null || entry == null) return;
        try {
            Files.createDirectories(ledgerFile.getParent());
            var json = "{"
                + "\"predictionId\":\"" + esc(entry.predictionId()) + "\","
                + "\"agentId\":\"" + esc(entry.agentId()) + "\","
                + "\"category\":\"" + esc(entry.category()) + "\","
                + "\"kind\":\"" + entry.kind().name() + "\","
                + "\"firedAt\":\"" + entry.firedAt() + "\","
                + "\"observedAt\":\"" + entry.observedAt() + "\","
                + "\"signal\":\"" + esc(entry.signal()) + "\"}\n";
            Files.writeString(ledgerFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("M4-C outcome: prediction={} kind={} agent={} signal=\"{}\"",
                entry.predictionId(), entry.kind(), entry.agentId(), entry.signal());
        } catch (IOException e) {
            log.warn("Failed to append outcome for {}: {}", entry.predictionId(), e.getMessage());
        }
    }

    public Path ledgerFile() { return ledgerFile; }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
