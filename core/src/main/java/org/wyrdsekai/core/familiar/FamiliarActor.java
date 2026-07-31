package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Actor that runs a {@link Familiar}'s loop — inference → (optional tool
 * execution) → inference → ... until done, stuck, or tanks exhausted.
 *
 * <p>. The familiar is a thin, task-bound
 * worker. It has no soul; its personality is the {@link ThoughtForm}'s
 * system prompt, nothing more. Its lifetime is bounded by its
 * {@link Tanks}.</p>
 *
 * <p>This is the foundational runtime. Step 1 of SPEC §22. Later steps
 * add tool execution (§13 validators), bunshin parallelism (§3 priority
 * scheduling), and named-familiar persistence.</p>
 *
 * <p>Completion detection (v1): the loop looks for the sentinel string
 * {@code "##DONE##"} in a turn's output. When present, the familiar
 * terminates with Status.DONE and the preceding content is its result.
 * Later versions will use the form's {@code evalCriteria} for model-judged
 * completion.</p>
 */
public class FamiliarActor extends AbstractBehavior<FamiliarActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(FamiliarActor.class);

    /** Sentinel the familiar's prompt instructs it to emit on completion. */
    public static final String DONE_MARKER = "##DONE##";

    // ── Protocol ────────────────────────────────────────────────────────────

    public sealed interface Command {}

    /**
     * Dispatcher invoked when the familiar emits a tool call.
     * Pluggable so the parent decides what subset
     * of skills + MCP tools the familiar is permitted to call — which must
     * be a subset of the form's declared toolSurface (§13 rule 6).
     */
    @FunctionalInterface
    public interface ToolDispatcher {
        /** Invoke a tool and return the result as a string. */
        String invoke(String tool, Map<String, Object> args);
        /** No-op dispatcher — the familiar has no tools. */
        ToolDispatcher NONE = (t, a) -> "[no tools available]";
    }

    /** Start the familiar's work loop (no tool access). */
    public record Summon(
        ThoughtForm form,
        String parentAgentDid,
        String task,
        Tanks tanks,
        ActorRef<Report> replyTo
    ) implements Command {}

    /** Start the familiar's work loop with a tool dispatcher (§5.1). */
    public record SummonWithTools(
        ThoughtForm form,
        String parentAgentDid,
        String task,
        Tanks tanks,
        ToolDispatcher dispatcher,
        ActorRef<Report> replyTo
    ) implements Command {}

    /** Parent asks for current status without interrupting. */
    public record StatusQuery(ActorRef<Report> replyTo) implements Command {}

    /** Parent injects guidance mid-task; applied before next inference turn. */
    public record Nudge(String hint) implements Command {}

    /** Parent requests graceful shutdown; familiar returns partial-result summary. */
    public record Cancel() implements Command {}

    /** Parent requests hard stop; intervention path. */
    public record Kill() implements Command {}

    /** Internal: inference turn returned. */
    private record InferenceCame(InferenceRouter.InferResponse response) implements Command {}

    /** Internal: wall-clock timeout fired. */
    private record WallClockExpired() implements Command {}

    // ── Result envelope sent back to the parent ─────────────────────────────

    /**
     * Summary returned to the parent when the familiar terminates (or on
     * a status query while still running).
     */
    public record Report(
        Familiar state,                 // full state including log
        String narrativeSummary,        // human-readable
        boolean terminated              // false only for StatusQuery responses
    ) {
        public Report {
            if (state == null) throw new IllegalArgumentException("state required");
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    private final ActorRef<InferenceRouter.Command> inferenceRouter;
    private final ActorRef<InferenceRouter.InferResponse> inferenceAdapter;
    private final TimerScheduler<Command> timers;

    private Familiar familiar;
    private ThoughtForm form;
    private ActorRef<Report> replyTo;
    private Instant summonedAt;
    private final List<InferenceClient.ChatMessage> conversation = new ArrayList<>();
    private String pendingNudge;           // injected on next turn
    private int turnCount = 0;
    private ToolDispatcher toolDispatcher = ToolDispatcher.NONE;

    private FamiliarActor(ActorContext<Command> context,
                           TimerScheduler<Command> timers,
                           ActorRef<InferenceRouter.Command> inferenceRouter) {
        super(context);
        this.timers = timers;
        this.inferenceRouter = inferenceRouter;
        this.inferenceAdapter = context.messageAdapter(
            InferenceRouter.InferResponse.class, InferenceCame::new);
    }

    public static Behavior<Command> create(ActorRef<InferenceRouter.Command> inferenceRouter) {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers -> new FamiliarActor(ctx, timers, inferenceRouter)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Summon.class, this::onSummon)
            .onMessage(SummonWithTools.class, this::onSummonWithTools)
            .onMessage(InferenceCame.class, this::onInferenceCame)
            .onMessage(WallClockExpired.class, this::onWallClockExpired)
            .onMessage(StatusQuery.class, this::onStatusQuery)
            .onMessage(Nudge.class, this::onNudge)
            .onMessage(Cancel.class, this::onCancel)
            .onMessage(Kill.class, this::onKill)
            .build();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private Behavior<Command> onSummon(Summon msg) {
        return initLoop(msg.form(), msg.parentAgentDid(), msg.task(), msg.tanks(),
            msg.replyTo(), ToolDispatcher.NONE);
    }

    private Behavior<Command> onSummonWithTools(SummonWithTools msg) {
        return initLoop(msg.form(), msg.parentAgentDid(), msg.task(), msg.tanks(),
            msg.replyTo(),
            msg.dispatcher() == null ? ToolDispatcher.NONE : msg.dispatcher());
    }

    private Behavior<Command> initLoop(ThoughtForm f, String parentDid, String task,
                                         Tanks tanks, ActorRef<Report> replyTo,
                                         ToolDispatcher dispatcher) {
        this.form = f;
        this.familiar = Familiar.summon(f, parentDid, task, tanks);
        this.replyTo = replyTo;
        this.summonedAt = Instant.now();
        this.toolDispatcher = dispatcher;

        log.info("Familiar {} summoned (form={}@{}, parent={}, tanks={}, tools={})",
            familiar.id(), form.name(), form.version(), parentDid, tanks,
            dispatcher == ToolDispatcher.NONE ? "off" : f.toolSurface());

        timers.startSingleTimer("wall-clock", new WallClockExpired(),
            Duration.ofSeconds(tanks.wallClock()));

        // Seed the conversation with the form's system prompt + task, plus
        // a completion contract. If tools are available, include the tool
        // call shape + the allowed tool surface.
        var systemPrompt = new StringBuilder(form.systemPrompt());
        systemPrompt.append("\n\n--- Completion ---\n")
            .append("When the task is complete, end your response with ")
            .append(DONE_MARKER).append(" on a line by itself. ")
            .append("Until then, keep working step by step.");
        if (dispatcher != ToolDispatcher.NONE && !form.toolSurface().isEmpty()) {
            systemPrompt.append("\n\n--- Tools ---\n")
                .append("You may call tools by emitting a JSON block: ")
                .append("{\"tool\":\"<name>\",\"args\":{...}}. ")
                .append("Available tools: ").append(form.toolSurface()).append(". ")
                .append("The result will be returned to you as a user turn.");
        }
        conversation.add(new InferenceClient.ChatMessage("system", systemPrompt.toString()));
        conversation.add(new InferenceClient.ChatMessage("user",
            "Task: " + task
            + (form.evalCriteria() == null || form.evalCriteria().isBlank()
                ? "" : "\n\nSuccess criterion: " + form.evalCriteria())));

        nextInferenceTurn();
        return this;
    }

    private void nextInferenceTurn() {
        // If we were nudged, inject the nudge as a user message before the turn.
        if (pendingNudge != null) {
            conversation.add(new InferenceClient.ChatMessage("user",
                "[guidance from parent]: " + pendingNudge));
            pendingNudge = null;
        }

        var requestId = "familiar-" + familiar.id() + "-t" + turnCount;
        inferenceRouter.tell(new InferenceRouter.ChatRequest(
            requestId, null, List.copyOf(conversation),
            Math.min(familiar.tanks().tokens(), 1024),  // per-turn cap
            0.4,
            inferenceAdapter));
    }

    private Behavior<Command> onInferenceCame(InferenceCame msg) {
        if (!familiar.isAlive()) return this; // late message, ignore

        switch (msg.response()) {
            case InferenceRouter.InferOk ok -> {
                turnCount++;
                var content = ok.content() == null ? "" : ok.content();
                var tokensUsed = ok.completionTokens();
                var wallClockSoFar = (int) Duration.between(summonedAt, Instant.now()).toSeconds();
                var wallClockRemaining = Math.max(0, familiar.tanks().wallClock() - wallClockSoFar);

                var newTanks = new Tanks(
                    Math.max(0, familiar.tanks().tokens() - tokensUsed),
                    Math.max(0, familiar.tanks().steps() - 1),
                    wallClockRemaining,
                    familiar.tanks().nestDepth(),
                    Math.max(0, familiar.tanks().cu() - 1)
                );

                var turn = new Familiar.TurnEntry(turnCount, content, List.of(), Instant.now(), tokensUsed);
                familiar = familiar.withTurn(turn, newTanks);

                // Append the model's output to the rolling conversation for the
                // next turn, so it sees its own prior reasoning.
                conversation.add(new InferenceClient.ChatMessage("assistant", content));

                // Done via explicit sentinel?
                if (content.contains(DONE_MARKER)) {
                    var resultText = content.replace(DONE_MARKER, "").strip();
                    finish(Familiar.Status.DONE, resultText, resultText);
                    return Behaviors.stopped();
                }

                // Tool call embedded? Dispatch and feed result back.
                var toolCall = parseToolCall(content);
                if (toolCall != null) {
                    var toolName = toolCall.name();
                    if (!form.toolSurface().contains(toolName)) {
                        conversation.add(new InferenceClient.ChatMessage("user",
                            "[tool denied] '" + toolName
                                + "' is not in your declared tool surface ("
                                + form.toolSurface() + "). Try another approach."));
                    } else {
                        String result;
                        try {
                            result = toolDispatcher.invoke(toolName, toolCall.args());
                        } catch (Exception e) {
                            result = "[tool error] " + e.getMessage();
                        }
                        conversation.add(new InferenceClient.ChatMessage("user",
                            "[tool:" + toolName + "] " + truncate(result, 2000)));
                    }
                    // Continue the loop unless tanks are out
                    if (newTanks.exhausted()) {
                        finish(Familiar.Status.TIMEOUT,
                            "Tanks exhausted mid-tool-call: " + newTanks.exhaustedReason(),
                            content);
                        return Behaviors.stopped();
                    }
                    nextInferenceTurn();
                    return this;
                }

                // Eval-criteria heuristic: if the form declares a criterion and
                // the output already contains the criterion tokens, treat as
                // done. Cheap — a future pass will use LLM-judge.
                if (evalCriterionLooksMet(content, form.evalCriteria())) {
                    finish(Familiar.Status.DONE, content.strip(), content.strip());
                    return Behaviors.stopped();
                }

                // Tank exhausted?
                if (newTanks.exhausted()) {
                    var why = newTanks.exhaustedReason();
                    log.info("Familiar {} exhausted ({}) after {} turns", familiar.id(), why, turnCount);
                    finish(Familiar.Status.TIMEOUT,
                        "Familiar ran out of resources (" + why + ") after " + turnCount + " turns. "
                            + "Last output: " + truncate(content, 200),
                        content);
                    return Behaviors.stopped();
                }

                // Trial limit? In v1 we count turns-without-progress but since
                // the loop keeps iterating by design, we reserve this for a
                // later per-goal trial mechanism. For now, only mark STUCK
                // if the model emits the same content twice in a row.
                if (isRepeatedStuck()) {
                    finish(Familiar.Status.STUCK,
                        "Familiar appears stuck — repeating itself. Last output: " + truncate(content, 200),
                        content);
                    return Behaviors.stopped();
                }

                // Else: continue the loop.
                nextInferenceTurn();
            }
            case InferenceRouter.InferError err -> {
                log.warn("Familiar {} inference error: {}", familiar.id(), err.error());
                finish(Familiar.Status.DEAD, "Inference failed: " + err.error(), null);
                return Behaviors.stopped();
            }
        }
        return this;
    }

    // ── Tool-call parsing ──────────────────────────────────────────────────

    /** A parsed tool-call extracted from the familiar's output. */
    private record ToolCallRequest(String name, Map<String, Object> args) {}

    /**
     * Parse the first tool-call-shaped JSON in the output. Expected shape:
     * <pre>{"tool":"&lt;name&gt;","args":{...}}</pre>
     * Returns null if no tool call is found.
     */
    private static ToolCallRequest parseToolCall(String text) {
        if (text == null || text.isBlank()) return null;
        var lower = text;
        int idx = 0;
        while (true) {
            int braceIdx = lower.indexOf('{', idx);
            if (braceIdx < 0) return null;
            int end = findMatchingBrace(lower, braceIdx);
            if (end < 0) return null;
            var candidate = lower.substring(braceIdx, end + 1);
            if (candidate.contains("\"tool\"")) {
                try {
                    var mapper = new ObjectMapper();
                    var node = mapper.readTree(candidate);
                    var toolField = node.get("tool");
                    if (toolField != null && toolField.isTextual()) {
                        var name = toolField.asText();
                        Map<String, Object> args = Map.of();
                        var argsField = node.get("args");
                        if (argsField != null && argsField.isObject()) {
                            @SuppressWarnings("unchecked")
                            var parsed = (Map<String, Object>) mapper
                                .convertValue(argsField, Map.class);
                            if (parsed != null) args = parsed;
                        }
                        return new ToolCallRequest(name, args);
                    }
                } catch (Exception e) {
                    // Fall through to next brace
                }
            }
            idx = end + 1;
            if (idx >= lower.length()) return null;
        }
    }

    private static int findMatchingBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = open; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c == '"' && prev != '\\') inStr = !inStr;
            else if (!inStr) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            prev = c;
        }
        return -1;
    }

    // ── Eval-criterion heuristic ───────────────────────────────────────────

    /**
     * Cheap substring-based check: if the eval criterion mentions distinctive
     * tokens and the output contains most of them, treat it as met. This is
     * a bridge toward model-judged completion — not load-bearing for real
     * tasks yet, but saves a turn or two when the familiar clearly has the
     * answer.
     */
    private static boolean evalCriterionLooksMet(String content, String criterion) {
        if (content == null || criterion == null || criterion.isBlank()) return false;
        if (content.length() < 40) return false;   // too short to be a real answer
        var lower = content.toLowerCase();
        var tokens = Arrays.stream(criterion.toLowerCase().split("\\W+"))
            .filter(t -> t.length() > 4)          // distinctive tokens only
            .distinct()
            .toList();
        if (tokens.isEmpty()) return false;
        long hits = tokens.stream().filter(lower::contains).count();
        // Demand two thirds of distinctive tokens present before accepting.
        return hits * 3 >= tokens.size() * 2 && hits >= 2;
    }

    private boolean isRepeatedStuck() {
        if (familiar.log().size() < 2) return false;
        var last = familiar.log().get(familiar.log().size() - 1).content();
        var prev = familiar.log().get(familiar.log().size() - 2).content();
        return last != null && last.equals(prev);
    }

    private Behavior<Command> onWallClockExpired(WallClockExpired msg) {
        if (!familiar.isAlive()) return this;
        log.info("Familiar {} wall-clock expired after {} turns", familiar.id(), turnCount);
        finish(Familiar.Status.TIMEOUT,
            "Familiar hit wall-clock ceiling after " + turnCount + " turns", null);
        return Behaviors.stopped();
    }

    private Behavior<Command> onStatusQuery(StatusQuery msg) {
        msg.replyTo().tell(new Report(familiar, "in progress (turn " + turnCount + ")", false));
        return this;
    }

    private Behavior<Command> onNudge(Nudge msg) {
        this.pendingNudge = msg.hint();
        log.info("Familiar {} nudged: {}", familiar.id(), truncate(msg.hint(), 80));
        return this;
    }

    private Behavior<Command> onCancel(Cancel msg) {
        log.info("Familiar {} cancelled by parent", familiar.id());
        finish(Familiar.Status.DEAD, "Cancelled by parent at turn " + turnCount, null);
        return Behaviors.stopped();
    }

    private Behavior<Command> onKill(Kill msg) {
        log.warn("Familiar {} killed (intervention)", familiar.id());
        finish(Familiar.Status.DEAD, "Killed at turn " + turnCount, null);
        return Behaviors.stopped();
    }

    private void finish(Familiar.Status status, String summary, Object result) {
        // §9 — for STUCK / TIMEOUT terminations, replace the raw result slot
        // with a structured StuckReport so the parent can reason about
        // approaches + obstacles + suggestion.
        var enrichedResult = result;
        if (status == Familiar.Status.STUCK || status == Familiar.Status.TIMEOUT) {
            enrichedResult = StuckReport.fromFamiliar(familiar, summary);
        }
        familiar = familiar.terminate(status, summary, enrichedResult);
        timers.cancel("wall-clock");
        replyTo.tell(new Report(familiar, summary, true));
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
