package org.wyrdsekai.core.familiar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A summoned instance of a {@link ThoughtForm}, given a specific task.
 *
 * <p>. A familiar is <strong>thin</strong> — no soul of its
 * own, no memory across invocations (unless named). For its lifetime it is
 * defined entirely by its form's system prompt, the parent-provided task, and
 * the accumulated turn log as work progresses.</p>
 *
 * <p>When any tank exhausts or the eval criterion reports DONE or maxTrials
 * is hit, the familiar dies gracefully and summary-returns to its parent.</p>
 *
 * <p>Named familiars persist across summonings (optional {@link #name} field)
 * and can accumulate a thin self-context over time. That's the gradient from
 * disposable tool → named individual → (eventually) resident companion.</p>
 */
public record Familiar(
    String id,
    String formId,
    String formVersion,
    String parentAgentDid,
    String task,
    Tanks tanks,
    List<String> tools,             // tool IDs available (form's default + any loans)
    int trialsUsed,
    Status status,
    List<TurnEntry> log,
    Optional<Object> result,
    Optional<String> summary,
    Optional<String> name,          // named-familiar path — Optional.empty() for ephemeral
    Instant startedAt,
    Optional<Instant> endedAt
) {

    public enum Status {
        RUNNING,   // actively working
        DONE,      // completed successfully
        STUCK,     // max trials reached
        TIMEOUT,   // tank exhausted
        DEAD,      // parent cancelled or supervisor killed
        YIELDED    // paused; may resume (§6 priority scheduling)
    }

    /**
     * A single turn in the familiar's work log — what the model said, what
     * tools it called, what they returned.
     */
    public record TurnEntry(
        int turn,
        String content,            // model output for this turn
        List<ToolCall> toolCalls,  // parsed tool invocations (may be empty)
        Instant at,
        int tokensUsed
    ) {
        public TurnEntry {
            if (toolCalls == null) toolCalls = List.of();
            else toolCalls = List.copyOf(toolCalls);
            if (at == null) at = Instant.now();
        }
    }

    public record ToolCall(
        String toolName,
        String argsJson,
        Optional<String> resultJson
    ) {
        public ToolCall {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName required");
            }
            if (argsJson == null) argsJson = "{}";
            if (resultJson == null) resultJson = Optional.empty();
        }
    }

    public Familiar {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (formId == null || formId.isBlank()) throw new IllegalArgumentException("formId required");
        if (parentAgentDid == null || parentAgentDid.isBlank()) {
            throw new IllegalArgumentException("parentAgentDid required");
        }
        if (task == null) throw new IllegalArgumentException("task required");
        if (tanks == null) tanks = Tanks.defaults();
        tools = tools == null ? List.of() : List.copyOf(tools);
        log = log == null ? List.of() : List.copyOf(log);
        if (result == null) result = Optional.empty();
        if (summary == null) summary = Optional.empty();
        if (name == null) name = Optional.empty();
        if (status == null) status = Status.RUNNING;
        if (startedAt == null) startedAt = Instant.now();
        if (endedAt == null) endedAt = Optional.empty();
    }

    /** Summon a fresh familiar from a form. */
    public static Familiar summon(ThoughtForm form, String parentDid, String task, Tanks tanks) {
        return new Familiar(
            UUID.randomUUID().toString(),
            form.id(),
            form.version(),
            parentDid,
            task,
            tanks,
            List.of(),           // no loans yet
            0,
            Status.RUNNING,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Instant.now(),
            Optional.empty()
        );
    }

    /** Append a turn to the log and decrement tanks. */
    public Familiar withTurn(TurnEntry turn, Tanks newTanks) {
        var nextLog = new ArrayList<>(log);
        nextLog.add(turn);
        return new Familiar(id, formId, formVersion, parentAgentDid, task, newTanks,
            tools, trialsUsed, status, nextLog, result, summary, name, startedAt, endedAt);
    }

    /** Terminate with a status + summary. */
    public Familiar terminate(Status finalStatus, String finalSummary, Object finalResult) {
        return new Familiar(id, formId, formVersion, parentAgentDid, task, tanks,
            tools, trialsUsed, finalStatus, log,
            Optional.ofNullable(finalResult), Optional.ofNullable(finalSummary),
            name, startedAt, Optional.of(Instant.now()));
    }

    /** Increment trial counter (used on failure-retry path). */
    public Familiar incrementTrial() {
        return new Familiar(id, formId, formVersion, parentAgentDid, task, tanks,
            tools, trialsUsed + 1, status, log, result, summary, name, startedAt, endedAt);
    }

    /** Is this familiar still active? */
    public boolean isAlive() {
        return status == Status.RUNNING || status == Status.YIELDED;
    }
}
