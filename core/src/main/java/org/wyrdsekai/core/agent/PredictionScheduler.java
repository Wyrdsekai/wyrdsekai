package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §M4-B — time-anchored scheduler for proactive
 * predictions. Tracks predictions that carry a fire-time hint (extracted from
 * `text` heuristically — e.g. "around 6pm", "in the morning") and exposes a
 * pollable due-set that {@link CompanionActor}'s vitality tick consumes.
 *
 * <p>Persistence: each scheduled fire is appended to
 * {@code data/m4/scheduled.jsonl} so we survive actor restart. On init we
 * replay the file, drop entries whose {@code fireAt} has already passed by
 * more than {@link #STALE_GRACE} (avoid bursty post-restart firing).
 *
 * <p>Cancellation: a prediction can be cancelled by id (e.g. when the user
 * spontaneously asks about the topic before the scheduled fire).
 *
 * <p>Thread-safety: the scheduled map is concurrent; persistence is append-only.
 * Designed to be called from a single actor (the companion's vitality tick),
 * but the data structure is safe for cross-thread observers.
 */
public final class PredictionScheduler {

    private static final Logger log = LoggerFactory.getLogger(PredictionScheduler.class);

    /** Predictions whose fireAt is more than this past now on init are dropped. */
    private static final Duration STALE_GRACE = Duration.ofMinutes(15);

    /** Maximum scheduled fires we persist (oldest evicted). */
    private static final int MAX_SCHEDULED = 200;

    /** A scheduled prediction-fire. */
    public record ScheduledFire(
        String predictionId,
        String agentId,
        String category,
        String text,
        Instant fireAt,
        Instant scheduledAt
    ) {
        public boolean isDue(Instant now) { return !now.isBefore(fireAt); }
    }

    /** key = predictionId, value = scheduled entry. */
    private final ConcurrentMap<String, ScheduledFire> scheduled = new ConcurrentHashMap<>();

    private final Path persistFile;

    public PredictionScheduler(Path dataDir) {
        this.persistFile = dataDir == null
            ? null
            : dataDir.resolve("m4").resolve("scheduled.jsonl");
        replay();
    }

    /**
     * Schedule a prediction's fire. Extracts a fire time from prediction.text;
     * returns Optional.empty() if no time hint could be extracted (the caller
     * should handle the prediction immediately or skip).
     */
    public Optional<ScheduledFire> schedule(OraclePrediction p, String agentId) {
        if (p == null || agentId == null) return Optional.empty();
        var fireAt = extractFireTime(p, Instant.now());
        if (fireAt.isEmpty()) return Optional.empty();
        var entry = new ScheduledFire(
            p.id(), agentId, p.category(), p.text(), fireAt.get(), Instant.now());
        scheduled.put(p.id(), entry);
        evictIfOverCap();
        persist(entry);
        log.info("Scheduled prediction {} for agent {} at {} (category={}, in {}s)",
            p.id(), agentId, fireAt.get(), p.category(),
            Duration.between(Instant.now(), fireAt.get()).toSeconds());
        return Optional.of(entry);
    }

    /** Pull all due fires for an agent, removing them from the scheduled map. */
    public List<ScheduledFire> pollDueFires(String agentId, Instant now) {
        var out = new ArrayList<ScheduledFire>();
        var iter = scheduled.entrySet().iterator();
        while (iter.hasNext()) {
            var e = iter.next();
            var fire = e.getValue();
            if (!fire.agentId().equals(agentId)) continue;
            if (fire.isDue(now)) {
                out.add(fire);
                iter.remove();
            }
        }
        if (!out.isEmpty()) {
            log.debug("Polled {} due fires for agent {}", out.size(), agentId);
        }
        return out;
    }

    /** Cancel a scheduled fire by predictionId (e.g. user asked spontaneously). */
    public boolean cancel(String predictionId) {
        var removed = scheduled.remove(predictionId);
        if (removed != null) {
            log.info("Cancelled scheduled prediction {} (was due at {})",
                predictionId, removed.fireAt());
            return true;
        }
        return false;
    }

    public int scheduledCount() { return scheduled.size(); }

    /** Map-of-all view, for telemetry. */
    public List<ScheduledFire> snapshot() {
        return new ArrayList<>(scheduled.values());
    }

    // ── Time extraction ─────────────────────────────────────

    private static final Pattern AROUND_HOUR = Pattern.compile(
        "(?i)around\\s+(\\d{1,2})\\s*(am|pm)?");
    private static final Pattern AT_HOUR = Pattern.compile(
        "(?i)\\bat\\s+(\\d{1,2})\\s*(am|pm)?");
    private static final Pattern IN_MINUTES = Pattern.compile(
        "(?i)\\bin\\s+(\\d+)\\s*(minute|min|m)s?\\b");
    private static final Pattern IN_HOURS = Pattern.compile(
        "(?i)\\bin\\s+(\\d+)\\s*(hour|hr|h)s?\\b");
    private static final Pattern TIME_OF_DAY = Pattern.compile(
        "(?i)\\b(morning|afternoon|evening|night|noon|midnight)\\b");

    /**
     * Extract a fire-time from the prediction's text. Heuristic — matches the
     * shapes TemporalPatternExtractor produces ("around 6pm", "in the morning",
     * "every day at 9", etc). If no shape matches, returns empty.
     */
    static Optional<Instant> extractFireTime(OraclePrediction p, Instant now) {
        if (p == null || p.text() == null) return Optional.empty();
        var text = p.text();

        var m = IN_MINUTES.matcher(text);
        if (m.find()) {
            try {
                var mins = Integer.parseInt(m.group(1));
                if (mins > 0 && mins < 24 * 60) {
                    return Optional.of(now.plusSeconds(mins * 60L));
                }
            } catch (NumberFormatException ignored) {}
        }
        m = IN_HOURS.matcher(text);
        if (m.find()) {
            try {
                var hrs = Integer.parseInt(m.group(1));
                if (hrs > 0 && hrs <= 24) {
                    return Optional.of(now.plusSeconds(hrs * 3600L));
                }
            } catch (NumberFormatException ignored) {}
        }
        m = AROUND_HOUR.matcher(text);
        if (m.find()) return Optional.of(nextOccurrenceOfHour(parseHour(m), now));
        m = AT_HOUR.matcher(text);
        if (m.find()) return Optional.of(nextOccurrenceOfHour(parseHour(m), now));
        m = TIME_OF_DAY.matcher(text);
        if (m.find()) {
            int hour = switch (m.group(1).toLowerCase()) {
                case "morning"   -> 9;
                case "afternoon" -> 14;
                case "evening"   -> 18;
                case "night"     -> 21;
                case "noon"      -> 12;
                case "midnight"  -> 0;
                default          -> -1;
            };
            if (hour >= 0) return Optional.of(nextOccurrenceOfHour(hour, now));
        }
        return Optional.empty();
    }

    private static int parseHour(Matcher m) {
        int hour = Integer.parseInt(m.group(1));
        var ampm = m.group(2);
        if (ampm != null) {
            ampm = ampm.toLowerCase();
            if ("pm".equals(ampm) && hour < 12) hour += 12;
            else if ("am".equals(ampm) && hour == 12) hour = 0;
        }
        return Math.max(0, Math.min(23, hour));
    }

    private static Instant nextOccurrenceOfHour(int hour24, Instant now) {
        var zone = ZoneId.systemDefault();
        var nowZ = now.atZone(zone);
        var candidate = nowZ.withHour(hour24).withMinute(0).withSecond(0).withNano(0);
        if (!candidate.isAfter(nowZ)) candidate = candidate.plusDays(1);
        return candidate.toInstant();
    }

    // ── Persistence ────────────────────────────────────────

    private void evictIfOverCap() {
        if (scheduled.size() <= MAX_SCHEDULED) return;
        // Drop the entry furthest in the past (or earliest).
        scheduled.entrySet().stream()
            .min(Comparator.comparing(e -> e.getValue().fireAt()))
            .ifPresent(e -> scheduled.remove(e.getKey()));
    }

    private void persist(ScheduledFire entry) {
        if (persistFile == null) return;
        try {
            Files.createDirectories(persistFile.getParent());
            var json = "{"
                + "\"predictionId\":\"" + esc(entry.predictionId()) + "\","
                + "\"agentId\":\"" + esc(entry.agentId()) + "\","
                + "\"category\":\"" + esc(entry.category()) + "\","
                + "\"text\":\"" + esc(entry.text()) + "\","
                + "\"fireAt\":\"" + entry.fireAt() + "\","
                + "\"scheduledAt\":\"" + entry.scheduledAt() + "\"}\n";
            Files.writeString(persistFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to persist scheduled fire {}: {}", entry.predictionId(), e.getMessage());
        }
    }

    private void replay() {
        if (persistFile == null || !Files.exists(persistFile)) return;
        var now = Instant.now();
        int loaded = 0, stale = 0;
        try {
            for (var line : Files.readAllLines(persistFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                var fire = parseLine(line);
                if (fire == null) continue;
                if (fire.fireAt().isBefore(now.minus(STALE_GRACE))) {
                    stale++;
                    continue;
                }
                scheduled.put(fire.predictionId(), fire);
                loaded++;
            }
        } catch (IOException e) {
            log.warn("Failed to replay {}: {}", persistFile, e.getMessage());
            return;
        }
        if (loaded > 0 || stale > 0) {
            log.info("PredictionScheduler replayed {} entries ({} dropped as stale)", loaded, stale);
        }
    }

    private static ScheduledFire parseLine(String line) {
        try {
            var node = Json.mapper().readTree(line);
            return new ScheduledFire(
                node.path("predictionId").asText(""),
                node.path("agentId").asText(""),
                node.path("category").asText(""),
                node.path("text").asText(""),
                Instant.parse(node.path("fireAt").asText()),
                Instant.parse(node.path("scheduledAt").asText()));
        } catch (Exception e) {
            log.debug("Skipping malformed scheduled line: {}", line);
            return null;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
