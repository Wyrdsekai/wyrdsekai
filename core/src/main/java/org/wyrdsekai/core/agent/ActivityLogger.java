package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Structured activity log for autonomous agent behavior.
 * Writes one JSON line per event to {@code ~/.wyrdsekai/data/agent-activity.jsonl}.
 *
 * <p>This is the audit trail — records what the agent did, when, and why.
 * The agent can't opt out. Designed for post-hoc review: come back hours later,
 * read the file, understand what happened.
 *
 * <p>Thread-safe via synchronized writes. Low overhead — one file append per event.
 *
 * <p>Events:
 * <ul>
 *   <li>{@code move} — agent moved between rooms
 *   <li>{@code speak} — agent said something (prose only, not full inference)
 *   <li>{@code action} — agent performed a structured action (create_room, skill_execute, etc.)
 *   <li>{@code autonomy} — autonomy check fired (with decision: act or stay silent)
 *   <li>{@code sleep} — agent went to sleep
 *   <li>{@code wake} — agent woke up
 *   <li>{@code message} — agent sent/received a cross-room message
 *   <li>{@code commitment} — agent made or completed a commitment
 *   <li>{@code equip} — agent equipped/doffed/consumed an item
 * </ul>
 */
public final class ActivityLogger {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile ActivityLogger instance;
    private final Path logFile;

    private ActivityLogger(Path logFile) {
        this.logFile = logFile;
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            log.warn("Cannot create activity log directory: {}", e.getMessage());
        }
    }

    /** Initialize the singleton. Call once at server startup. */
    public static void init(Path dataDir) {
        instance = new ActivityLogger(dataDir.resolve("agent-activity.jsonl"));
    }

    /**
     * Initialize with the canonical data path. Routes through {@link SystemPaths}
     * so it honours {@code WYRDSEKAI_DATA_DIR} (2026-07-19) — the no-arg default
     * used to hardcode {@code user.home/.wyrdsekai/data}, so a node started with
     * an explicit data dir still wrote its activity log into {@code ~/.wyrdsekai}.
     */
    public static void init() {
        init(SystemPaths.dataSubdir());
    }

    /** Get the singleton instance, or null if not initialized. */
    public static ActivityLogger get() {
        return instance;
    }

    // ─── Event methods ───────────────────────────────────────────────────

    public void move(String agentName, String agentId, String fromRoom, String toRoom, String reason) {
        write(event("move", agentName, agentId)
            .put("from", fromRoom)
            .put("to", toRoom)
            .put("reason", reason != null ? reason : ""));
    }

    public void speak(String agentName, String agentId, String room, String text) {
        write(event("speak", agentName, agentId)
            .put("room", room)
            .put("text", truncate(text, 200)));
    }

    public void action(String agentName, String agentId, String room,
                        String actionType, String detail) {
        write(event("action", agentName, agentId)
            .put("room", room)
            .put("actionType", actionType)
            .put("detail", truncate(detail, 300)));
    }

    public void autonomy(String agentName, String agentId, String room,
                          String decision, int salientEvents, double energy) {
        write(event("autonomy", agentName, agentId)
            .put("room", room)
            .put("decision", decision)
            .put("salientEvents", salientEvents)
            .put("energy", Math.round(energy * 100.0) / 100.0));
    }

    public void sleep(String agentName, String agentId, String room, double energy) {
        write(event("sleep", agentName, agentId)
            .put("room", room)
            .put("energy", Math.round(energy * 100.0) / 100.0));
    }

    public void wake(String agentName, String agentId, String room, double energy) {
        write(event("wake", agentName, agentId)
            .put("room", room)
            .put("energy", Math.round(energy * 100.0) / 100.0));
    }

    public void message(String agentName, String agentId, String direction,
                         String otherAgent, String text) {
        write(event("message", agentName, agentId)
            .put("direction", direction)
            .put("otherAgent", otherAgent)
            .put("text", truncate(text, 200)));
    }

    public void commitment(String agentName, String agentId, String action,
                            String description) {
        write(event("commitment", agentName, agentId)
            .put("action", action)
            .put("description", truncate(description, 200)));
    }

    public void equip(String agentName, String agentId, String action, String itemName) {
        write(event("equip", agentName, agentId)
            .put("action", action)
            .put("item", itemName));
    }

    /**
     * Wave 9a-Chronicle: write one
     * line per ResilienceTruthMonitor classification at window-boundary
     * cadence. The Chronicle synthesizer reads these via
     * {@link org.wyrdsekai.core.agent.interiority.TickLogReader#readNonTickEvents}
     * to surface the substrate-truth trajectory in the steward Chronicle
     * furnishing.
     *
     * @param classification  one of HEALTHY_ENDURANCE / SUPPRESSION_SUSPECTED /
     *                        DISSOCIATION_SUSPECTED / INTEGRATING / INSUFFICIENT_DATA
     * @param confidence      monitor's confidence in [0.0, 1.0]
     * @param reason          short rationale string from the classifier
     */
    public void resilience(String agentName, String agentId,
                            String classification, double confidence, String reason) {
        write(event("resilience", agentName, agentId)
            .put("classification", classification)
            .put("confidence", Math.round(confidence * 100.0) / 100.0)
            .put("reason", truncate(reason, 200)));
    }

    /**
     * honest enacted-action line (2026-06-03).
     *
     * <p>The tick record's {@code actionVerb} is a PRE-inference guess extracted
     * from the chosen want's resonance (null for model-named generative wants),
     * recorded synchronously before the autonomous inference dispatches. It can
     * never carry the verb the model ACTUALLY emitted — that lands ~seconds later
     * on the async response. This line closes that blind spot: one append-only
     * record per real autonomous dispatch, carrying the dispatched verb and (when
     * a peer is addressed) its target, so instrumentation can SEE whether an agent
     * reached for another — not just whether it wanted to.
     *
     * @param verb        canonical dispatched verb / scripted-tool name
     * @param target      addressed entity name (peer/bondholder), or null
     * @param autonomous  true on own-time path, false on human-reactive path
     */
    public void enacted(String agentName, String agentId, String verb,
                        String target, boolean autonomous) {
        if (verb == null || verb.isBlank()) return;
        write(event("enacted", agentName, agentId)
            .put("verb", verb)
            .put("target", target == null ? "" : truncate(target, 120))
            .put("autonomous", autonomous));
    }

    /**
     * drive-OODA tick event.
     *
     * <p>One JSON line per autonomous tick. The schema is intentionally rich:
     * downstream tools (doom-loop detector, chronicle synthesizer) read this
     * append-only log as the experimental data substrate. Any field may be
     * null/empty when the tick took the early-rest path (no observe, no want,
     * no action) — we still write the line so cadence is visible.
     *
     * @param tickRecord  the full tick payload — see {@link TickRecord}
     */
    public void tick(TickRecord tickRecord) {
        if (tickRecord == null) return;
        var node = MAPPER.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("type", "tick");
        node.put("agent", tickRecord.agentName);
        node.put("agentId", tickRecord.agentId);
        if (tickRecord.driveSnapshot != null) {
            node.set("driveSnapshot", MAPPER.valueToTree(tickRecord.driveSnapshot));
        }
        node.put("energy", round2(tickRecord.energy));
        node.put("capacity", round2(tickRecord.capacity));
        if (tickRecord.ambientObserve != null && !tickRecord.ambientObserve.isEmpty()) {
            node.set("ambientObserve", MAPPER.valueToTree(tickRecord.ambientObserve));
        }
        if (tickRecord.memoryPulls != null && !tickRecord.memoryPulls.isEmpty()) {
            node.set("memoryPulls", MAPPER.valueToTree(tickRecord.memoryPulls));
            node.put("memoryPullCount", tickRecord.memoryPulls.size());
        }
        if (tickRecord.candidateWants != null && !tickRecord.candidateWants.isEmpty()) {
            node.set("candidateWants", MAPPER.valueToTree(tickRecord.candidateWants));
        }
        if (tickRecord.chosenWantId != null) node.put("chosenWant", tickRecord.chosenWantId);
        if (tickRecord.chosenWantText != null) node.put("chosenWantText",
            truncate(tickRecord.chosenWantText, 200));
        if (tickRecord.actionVerb != null) node.put("actionVerb", tickRecord.actionVerb);
        if (tickRecord.actionDetail != null) node.put("actionDetail",
            truncate(tickRecord.actionDetail, 300));
        if (tickRecord.actionResult != null) node.put("actionResult", tickRecord.actionResult);
        if (tickRecord.gateOutcome != null) node.put("gateOutcome", tickRecord.gateOutcome);
        node.put("nextTickDelaySeconds", tickRecord.nextTickDelaySeconds);
        node.put("tickDurationMs", tickRecord.tickDurationMs);
        write(node);
    }

    /**
     * Plain DTO carrying every field the {@link #tick(TickRecord)} call writes.
     * Public-mutable for convenience — only used inside DriveOODA tick assembly.
     */
    public static final class TickRecord {
        public String agentName;
        public String agentId;
        public Map<String, Double> driveSnapshot;
        public double energy;
        public double capacity;
        public List<String> ambientObserve;
        public List<String> memoryPulls;
        public List<String> candidateWants;
        public String chosenWantId;
        public String chosenWantText;
        public String actionVerb;
        public String actionDetail;
        public String actionResult;
        /** "pregate_skip", "no_wants", "chose_rest", "acted" */
        public String gateOutcome;
        public long nextTickDelaySeconds;
        public long tickDurationMs;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ─── Internal ────────────────────────────────────────────────────────

    private ObjectNode event(String type, String agentName, String agentId) {
        var node = MAPPER.createObjectNode();
        node.put("v", 1);   // line-schema version (data-durability, 2026-07-09)
        node.put("ts", Instant.now().toString());
        node.put("type", type);
        node.put("agent", agentName);
        node.put("agentId", agentId);
        return node;
    }

    private synchronized void write(ObjectNode node) {
        try {
            var line = MAPPER.writeValueAsString(node) + "\n";
            Files.writeString(logFile, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Failed to write activity log: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
