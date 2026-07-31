package org.wyrdsekai.core.issue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * One captured issue or feedback entry. Persisted as a
 * JSONL row by {@link IssueService}; the context fields are snapshot at
 * filing time so the report stays useful after the moment has passed.
 *
 * <p>{@code kind} is {@code "issue"} (full context bundle) or
 * {@code "feedback"} (text only — no conversation/log capture, see spec §2).
 * {@code status} is {@code "open"} or {@code "closed"}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Issue(
    @JsonProperty("id") String id,
    @JsonProperty("kind") String kind,
    @JsonProperty("status") String status,
    @JsonProperty("tsMs") long tsMs,
    @JsonProperty("reporter") String reporter,
    @JsonProperty("surface") String surface,
    @JsonProperty("text") String text,
    @JsonProperty("zoneId") String zoneId,
    @JsonProperty("build") String build,
    @JsonProperty("companionDid") String companionDid,
    @JsonProperty("recentTurns") List<TurnRef> recentTurns,
    @JsonProperty("driveSnapshot") Map<String, Object> driveSnapshot,
    @JsonProperty("logTail") List<String> logTail
) {
    public static final String KIND_ISSUE = "issue";
    public static final String KIND_FEEDBACK = "feedback";
    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";

    /** One conversation turn captured into the bundle. */
    public record TurnRef(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("tsMs") long tsMs
    ) {}

    public Issue withStatus(String newStatus) {
        return new Issue(id, kind, newStatus, tsMs, reporter, surface, text,
            zoneId, build, companionDid, recentTurns, driveSnapshot, logTail);
    }
}
