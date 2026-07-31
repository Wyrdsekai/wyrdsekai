package org.wyrdsekai.core.observability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Observation log for agent turns — the agent's perspective made visible.
 *
 * Each inference turn is captured as a ShadowEntry: what the agent saw
 * (assembled prompt), what it felt (vitality), what it retrieved (fragments),
 * what the LLM returned, and what it said.
 *
 * Writes to JSONL file (one JSON line per entry) and keeps a ring buffer
 * of the last N entries for REST/WS access.
 *
 * Enable via WYRDSEKAI_SHADOW_LOG env var (path) or pass a Path directly.
 * If disabled (null path), entries are still buffered in memory.
 */
public class ShadowLog {

    private static final Logger log = LoggerFactory.getLogger(ShadowLog.class);
    private static final int MAX_BUFFER = 100;

    /** Global instance — initialized by Main.java, accessed by CompanionActor. */
    private static volatile ShadowLog instance;

    private final ConcurrentLinkedDeque<ShadowEntry> buffer = new ConcurrentLinkedDeque<>();
    private final Path logPath;
    private final ObjectMapper mapper;

    /**
     * Per-turn observation entry.
     *
     * @param timestamp        when the turn started
     * @param agentId          agent DID or entity ID
     * @param agentName        agent display name
     * @param roomId           current room
     * @param triggerEntityId   who triggered this turn (null for greetings)
     * @param triggerText       what they said
     * @param vitality          12-tank state at time of inference
     * @param retrievedFragments fragment IDs retrieved for this turn
     * @param promptMessages    the full assembled prompt (role + content pairs)
     * @param rawResponse       what the LLM returned
     * @param spokenText        what the agent actually said (after action parsing)
     * @param promptTokens      tokens used in prompt
     * @param completionTokens  tokens generated
     * @param chargeDetected    emotional charge detected on trigger (null if none)
     */
    public record ShadowEntry(
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("roomId") String roomId,
        @JsonProperty("triggerEntityId") String triggerEntityId,
        @JsonProperty("triggerText") String triggerText,
        @JsonProperty("vitality") Map<String, Double> vitality,
        @JsonProperty("retrievedFragments") List<String> retrievedFragments,
        @JsonProperty("promptMessages") List<PromptMessage> promptMessages,
        @JsonProperty("rawResponse") String rawResponse,
        @JsonProperty("spokenText") String spokenText,
        @JsonProperty("promptTokens") int promptTokens,
        @JsonProperty("completionTokens") int completionTokens,
        @JsonProperty("chargeDetected") String chargeDetected
    ) {}

    /** Simplified prompt message for shadow logging. */
    public record PromptMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content
    ) {}

    public ShadowLog(Path logPath) {
        this.logPath = logPath;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        if (logPath != null) {
            log.info("Shadow log enabled: {}", logPath.toAbsolutePath());
        }
    }

    /** Create from env var or null. */
    public static ShadowLog fromEnv() {
        var envPath = WyrdConfig.get().resolve(
            "WYRDSEKAI_SHADOW_LOG", "observability.shadow_log_path", () -> null);
        return new ShadowLog(envPath != null ? Path.of(envPath) : null);
    }

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init(ShadowLog shadowLog) {
        instance = shadowLog;
    }

    /** Get the global instance (null if not initialized or shadow disabled). */
    public static ShadowLog get() {
        return instance;
    }

    /** Record a turn. Thread-safe. */
    public void record(ShadowEntry entry) {
        buffer.addLast(entry);
        while (buffer.size() > MAX_BUFFER) {
            buffer.pollFirst();
        }

        if (logPath != null) {
            try {
                var line = mapper.writeValueAsString(entry) + "\n";
                Files.writeString(logPath, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Failed to write shadow log entry: {}", e.getMessage());
            }
        }
    }

    /** Get the last N entries (newest last). */
    public List<ShadowEntry> recent(int n) {
        var all = List.copyOf(buffer);
        return n >= all.size() ? all : all.subList(all.size() - n, all.size());
    }

    /** Get all buffered entries. */
    public List<ShadowEntry> all() {
        return List.copyOf(buffer);
    }

    /** Get the most recent entry, or null. */
    public ShadowEntry latest() {
        return buffer.peekLast();
    }

    /** Total entries recorded (including those evicted from buffer). */
    public int bufferSize() {
        return buffer.size();
    }

    /** Whether file logging is enabled. */
    public boolean isFileLoggingEnabled() {
        return logPath != null;
    }

    /** The log file path (null if memory-only). */
    public Path logPath() {
        return logPath;
    }
}
