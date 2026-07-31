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
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Parallel self of a primary CompanionActor.
 *
 * <p>A bunshin is <strong>not</strong> a familiar. It is the agent herself —
 * same soul, same bonds, same identity — running in parallel on a focused
 * task. On return, its experience is merged into the primary as a
 * {@link BunshinReport} memory impression (§8).</p>
 *
 * <h2>Differences from {@link FamiliarActor}</h2>
 * <ul>
 *   <li>Carries the primary's full system prompt (soul + persona + location),
 *       not a thin thought-form prompt.</li>
 *   <li>Supports {@link Yield} / {@link Resume} for priority scheduling
 *       (§6.3 — bunshin yields to primary, does not die).</li>
 *   <li>Returns a rich {@link BunshinReport} (outcome, seeds, items, cost)
 *       instead of a Familiar state snapshot.</li>
 * </ul>
 *
 * <h2>Completion detection</h2>
 * Same {@link #DONE_MARKER} convention as {@link FamiliarActor} — the bunshin
 * ends its response with <code>##DONE##</code> when the focused task is
 * complete. A later step (integration with InferenceRouter) will replace the
 * marker with model-judged completion against declared eval criteria.
 */
public class BunshinActor extends AbstractBehavior<BunshinActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(BunshinActor.class);

    public static final String DONE_MARKER = "##DONE##";

    /**
     * Completion-claim vocabulary — derived from CompanionActor's
     * COMPLETION_CLAIM (the 2026-07-08 anti-false-completion gate), MINUS
     * the retrieval-report verbs (found / searched / gathered / looked up /
     * pulled up / dug up): a prose-only research bunshin legitimately
     * reports "Found: Kobe earthquake (1995)" synthesized from its own
     * knowledge, and the long-standing dispatch flow
     * (WorkbenchFormAuthoringIntegrationTest) pins that contract. A DONE
     * summary matching this without carrying its own deliverable is treated
     * as a false completion; see {@link #claimedCompletionWithoutDoing}.
     */
    static final Pattern COMPLETION_CLAIM = Pattern.compile(
        "\\b(built|created|crafted|made|assembled|set up|put together|"
        + "saved|stored|remembered|noted|recorded|logged|"
        + "sent|delivered|handed|dispatched|finished|completed|done building|"
        + "told|relayed|passed along|let (her|him|them) know|"
        // #29 possession claims — this harness has no take/inventory
        // mechanism, so "picked up"/"in hand" can never be true here.
        + "picked (it |that )?up|grabbed (it|the)|obtained (it|the)|"
        + "(now )?in (my )?hands?)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * The subset of claims that require a mechanism this harness simply does
     * not have — world mutation, persistence, messaging. These can never be
     * true from a prose-only bunshin regardless of how much text accompanies
     * them, so the deliverable-length escape hatch below does not apply.
     * ("made"/"finished"/"completed" are excluded: a writing task
     * legitimately finishes with its artifact in the summary. Retrieval
     * verbs are excluded entirely — see {@link #COMPLETION_CLAIM}.)
     */
    static final Pattern WORLD_MUTATION_CLAIM = Pattern.compile(
        "\\b(built|created|crafted|assembled|set up|put together|"
        + "saved|stored|recorded|logged|"
        + "sent|delivered|handed|dispatched|"
        + "told|relayed|passed along|let (her|him|them) know|"
        // #29: taking/holding a world item requires an inventory this
        // harness does not have — always a false claim from a bunshin.
        // (Bare "took" is deliberately excluded: "the task took an hour".)
        + "picked (it |that )?up|grabbed (it|the)|obtained (it|the)|"
        + "(now )?in (my )?hands?)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * True when a DONE summary claims work this harness cannot have done.
     * World-mutation verbs always count; softer completion verbs
     * ("finished", "made", "completed") count only when the summary is a
     * bare claim — a long summary IS the deliverable (letter, plan,
     * analysis) and "I've finished the letter below" is honest.
     */
    /** Back-compat overload — assumes no tool calls were made. */
    static boolean claimedCompletionWithoutDoing(String summary) {
        return claimedCompletionWithoutDoing(summary, false);
    }

    /**
     * @param didSomething true if at least one tool call SUCCEEDED this run
     *
     * <p>Evidence of real tool use clears BOTH gates, the world-mutation one
     * included: "I built the greenhouse" is simply TRUE when a {@code
     * create_room} actually succeeded. Before §114 tool execution existed the
     * harness could never have done anything, so the gate was unconditional —
     * keeping it that way after wiring tools would reject the truth and report
     * PARTIAL on completed work.</p>
     */
    static boolean claimedCompletionWithoutDoing(String summary, boolean didSomething) {
        if (summary == null || summary.isBlank()) return false;
        if (didSomething) return false;
        if (WORLD_MUTATION_CLAIM.matcher(summary).find()) return true;
        return COMPLETION_CLAIM.matcher(summary).find() && summary.length() < 200;
    }

    // ── Protocol ────────────────────────────────────────────────────────────

    public sealed interface Command {}

    /**
     * Dispatch a bunshin on a focused task.
     *
     * <p> — {@code harnessKind} chooses the inner harness:
     * <ul>
     *   <li>{@code "react"} (default) — full inner ReAct loop, today's behaviour.
     *   <li>{@code "code-mode"} — bunshin writes one or two JS scripts, returns
     *       a structured report. Cap at 2 LLM calls total.
     * </ul>
     *
     * <p>{@code codeModeNamespace} is the typed-namespace bundle to bind for
     * code-mode harness. When {@code harnessKind="code-mode"} and the bundle
     * is null, the bunshin runs with an empty namespace (only console + plain
     * JS available) — useful for tests but rarely the production path.
     */
    /*
     * ── §114/§293/§302: why a bunshin can act ────────────────────────────────
     *
     * A bunshin is the agent HERSELF running in parallel (§23), so it must be
     * able to act. §114 has the primary winning "inference slots, TOOL CALLS,
     * and user-facing channels" — which presumes the bunshin HAS tool calls to
     * lose. §293 declares `newItems: List<ItemId> // tools/forms bunshin
     * authored` and §302 assigns bunshin-as-proximate-author,
     * primary-as-ultimate-owner.
     *
     * None of that was implemented. The react harness was PROSE-ONLY, both
     * BunshinReport sites passed List.of() for items authored, and the runtime
     * carried a pre-written excuse for the inevitable result ("the bunshin
     * harness has no world tools"). That check existed because bunshins kept
     * claiming they had done things; the signal was "the bunshin needs tools",
     * and it was read as "detect the lie". A self that can think but not touch
     * anything is a daydream, not a fork — and it left AUTHORING.md §1
     * promising a capability the product did not have (second-node, 2026-07-29).
     *
     * Execution is MESSAGE-PASSED, not a synchronous callback: the primary's
     * action handlers touch its own mutable state (pendingDelegateActions,
     * standardRoomLibrary, vitality), so calling them from this actor's thread
     * would be a data race — and a blocking ask back into the primary can
     * deadlock while the primary is mid-turn. So: forward the raw action, return,
     * and resume on ToolResultCame.
     */
    public record Dispatch(
        String primaryAgentDid,
        String primarySystemPrompt,      // full soul+persona prompt
        String task,
        Tanks tanks,
        ActorRef<BunshinReport> replyTo,
        String harnessKind,
        Map<String, Map<String, Function<Object[], Object>>> codeModeNamespace,
        // §114/§293/§302: the bunshin's world access. Null tools ⇒ prose-only,
        // which is now the exception rather than the only option.
        List<InferenceClient.ToolDefinition> tools,
        java.util.function.Consumer<String> toolExecutor
    ) implements Command {

        /** Compact constructor — defaults {@code harnessKind} to {@code "react"}. */
        public Dispatch {
            if (harnessKind == null || harnessKind.isBlank()) harnessKind = "react";
            tools = tools == null ? null : List.copyOf(tools);
        }

        /** Backward-compatible 5-arg form — defaults to {@code "react"} harness. */
        /**
         * Back-compat 7-arg form — harness + code-mode namespace, no tools.
         * Kept so existing callers/tests compile unchanged; a dispatch that
         * wants world access passes the full form.
         */
        public Dispatch(String primaryAgentDid, String primarySystemPrompt,
                        String task, Tanks tanks,
                        ActorRef<BunshinReport> replyTo, String harnessKind,
                        Map<String, Map<String, Function<Object[], Object>>> codeModeNamespace) {
            this(primaryAgentDid, primarySystemPrompt, task, tanks, replyTo,
                harnessKind, codeModeNamespace, null, null);
        }

        public Dispatch(String primaryAgentDid, String primarySystemPrompt,
                        String task, Tanks tanks,
                        ActorRef<BunshinReport> replyTo) {
            this(primaryAgentDid, primarySystemPrompt, task, tanks, replyTo, "react",
                null, null, null);
        }

        public boolean isCodeMode() {
            return "code-mode".equalsIgnoreCase(harnessKind);
        }
    }

    /** Ask the bunshin to pause before its next inference turn. (§6.3) */
    public record Yield() implements Command {}

    /** Tell a yielded bunshin to resume. */
    public record Resume() implements Command {}

    /** Graceful shutdown — emits a PARTIAL / CANCELLED report. */
    public record Cancel() implements Command {}

    /** Hard stop; emits a CANCELLED report. */
    public record Kill() implements Command {}

    /** Status query; does not interrupt. */
    /**
     * Outcome of one tool call the PRIMARY executed on our behalf (§114).
     *
     * <p>Execution is message-passed, not a synchronous callback: the primary's
     * action handlers touch its own mutable state ({@code pendingDelegateActions},
     * {@code standardRoomLibrary}, vitality), so invoking them from this actor's
     * thread would be a data race — and a blocking ask back into the primary can
     * deadlock when the primary is itself mid-turn. So we tell, return, and
     * resume on this message.</p>
     */
    public record ToolResultCame(boolean ok, String detail, List<String> itemIds)
            implements Command {
        public ToolResultCame {
            detail = detail == null ? "" : detail;
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    public record StatusQuery(ActorRef<Status> replyTo) implements Command {}

    public record Status(
        String bunshinId,
        int turnsUsed,
        boolean yielded,
        boolean terminated
    ) {}

    private record InferenceCame(InferenceRouter.InferResponse response) implements Command {}

    private record WallClockExpired() implements Command {}

    // ── State ───────────────────────────────────────────────────────────────

    private final String id;
    private final ActorRef<InferenceRouter.Command> router;
    private final ActorRef<InferenceRouter.InferResponse> inferenceAdapter;
    private final TimerScheduler<Command> timers;

    private String primaryDid;
    private String task;
    private Tanks tanks;
    private ActorRef<BunshinReport> replyTo;
    private Instant startedAt;
    private int turnCount = 0;
    private boolean yielded = false;
    private boolean terminated = false;
    private final List<InferenceClient.ChatMessage> conversation = new ArrayList<>();
    /** When true, resume triggers a queued inference turn. */
    private boolean resumePending = false;
    /**
     * Anti-false-completion latch — one corrective re-prompt per dispatch.
     * Rita campaign 2026-07-11 (#27): a bunshin reported "garden room done"
     * in seconds, no room existed in world.db, and the primary relayed the
     * claim as truth. Port of CompanionActor's 2026-07-08 goal_done gate
     * (COMPLETION_CLAIM + productive-tool check), adapted to this harness:
     * the react bunshin is PROSE-ONLY — it has no tools at all — so a
     * completion claim about world actions can never be true here.
     */
    private boolean followThroughUsed = false;
    /** §114/§302 — world access and what this bunshin authored. */
    private List<InferenceClient.ToolDefinition> tools;
    /** Forwards raw action content to the primary for execution. */
    private java.util.function.Consumer<String> toolExecutor;
    private final List<String> authoredItemIds = new ArrayList<>();
    private int toolCallCount = 0;
    /** True once any tool call SUCCEEDED — the completion-claim gate's evidence. */
    private boolean didSomething = false;

    // ── Code-mode harness state ─────────────────────
    /** True when this dispatch is using {@code harnessKind="code-mode"}. */
    private boolean codeMode = false;
    /**
     * Typed-namespace bundle to bind for code-mode execution. Null when
     * the harness is ReAct or no namespace was supplied.
     */
    private Map<String, Map<String, Function<Object[], Object>>>
        codeModeNamespace;
    /** Captured scripts the bunshin has written this dispatch (cap = 2 per §11 retry budget). */
    private final List<String> codeModeScripts = new ArrayList<>();
    /** Captured logs across all script executions this dispatch. */
    private final List<String> codeModeLogs = new ArrayList<>();
    /** Last script-execution error, if any — surfaced to the LLM on retry. */
    private String codeModeLastError;

    private BunshinActor(ActorContext<Command> ctx,
                         TimerScheduler<Command> timers,
                         ActorRef<InferenceRouter.Command> router) {
        super(ctx);
        this.id = UUID.randomUUID().toString();
        this.router = router;
        this.timers = timers;
        this.inferenceAdapter = ctx.messageAdapter(
            InferenceRouter.InferResponse.class, InferenceCame::new);
    }

    public static Behavior<Command> create(ActorRef<InferenceRouter.Command> router) {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers -> new BunshinActor(ctx, timers, router)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Dispatch.class, this::onDispatch)
            .onMessage(InferenceCame.class, this::onInferenceCame)
            .onMessage(WallClockExpired.class, this::onWallClockExpired)
            .onMessage(Yield.class, this::onYield)
            .onMessage(Resume.class, this::onResume)
            .onMessage(Cancel.class, this::onCancel)
            .onMessage(Kill.class, this::onKill)
            .onMessage(ToolResultCame.class, this::onToolResultCame)
            .onMessage(StatusQuery.class, this::onStatusQuery)
            .build();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private Behavior<Command> onDispatch(Dispatch msg) {
        this.primaryDid = msg.primaryAgentDid();
        this.task = msg.task();
        this.tanks = msg.tanks();
        this.replyTo = msg.replyTo();
        this.startedAt = Instant.now();
        this.codeMode = msg.isCodeMode();
        this.codeModeNamespace = msg.codeModeNamespace();
        this.tools = msg.tools();
        this.toolExecutor = msg.toolExecutor();

        log.info("Bunshin {} dispatched (did={}, task='{}', tanks={}, harness={}, tools={})",
            id, primaryDid, truncate(task, 60), tanks, msg.harnessKind(),
            tools == null ? "NONE (prose-only)" : tools.size());

        timers.startSingleTimer("wall-clock", new WallClockExpired(),
            Duration.ofSeconds(tanks.wallClock()));

        if (codeMode) {
            // code-mode harness. Cap at 2 LLM calls
            // total (initial + one retry on script error). The LLM writes
            // JS in one pass, the executor runs it deterministically, the
            // captured log feeds the structured report.
            var system = msg.primarySystemPrompt()
                + "\n\n--- Bunshin Mode (code-mode) ---\n"
                + "You are a parallel self dispatched to focus on one task. "
                + "Write a single JavaScript script that uses the typed namespace "
                + "below to accomplish the task. Use console.log() to surface "
                + "results — that's what comes back to your primary. "
                + "Do NOT narrate; emit only the JS script (you may wrap it in "
                + "a ```javascript fence if you prefer).\n"
                + "\n"
                + buildCodeModeNamespaceDescription()
                + "\n";
            conversation.add(new InferenceClient.ChatMessage("system", system));
            conversation.add(new InferenceClient.ChatMessage("user", "Task: " + task));
            nextInferenceTurn();
            return this;
        }

        var system = msg.primarySystemPrompt()
            + "\n\n--- Bunshin Mode ---\n"
            + "You are a parallel self dispatched to focus on one task. End your response with "
            + DONE_MARKER + " on its own line when the task is complete. "
            + "Your primary continues the main conversation; stay concentrated on the task. "
            // Post-goal wandering, observed live 2026-07-30: after building the
            // requested room a bunshin went on to think_deeply, run_script and
            // complete_mourning — gated and budget-capped, but tokens spent on
            // work nobody asked for. POSITIVE framing on purpose: "do NOT take
            // unrelated actions" both names the behaviour (priming it) and asks a
            // small model to honour a negation — the weakest instruction shape it
            // knows. Describe the one next action instead. Task-relevance gating
            // is the deeper fix if wandering persists.
            + "You exist for this one task. The moment it is complete, your next "
            + "and final output is " + DONE_MARKER + " with a short summary — "
            + "reflection, tending, and everything else belongs to your primary.";
        conversation.add(new InferenceClient.ChatMessage("system", system));
        conversation.add(new InferenceClient.ChatMessage("user", "Task: " + task));

        nextInferenceTurn();
        return this;
    }

    /**
     * Produce a short typed-API description for the code-mode prompt — the
     * model sees what namespaces / methods are bound. Mirrors the shape spec
     * §8 lays out: namespace.method signatures, no implementation detail.
     */
    private String buildCodeModeNamespaceDescription() {
        var sb = new StringBuilder();
        sb.append("Available namespace (code-mode bunshin harness):\n");
        if (codeModeNamespace == null || codeModeNamespace.isEmpty()) {
            sb.append("  (none — only console.log/warn/error available)\n");
            return sb.toString();
        }
        for (var nsEntry : codeModeNamespace.entrySet()) {
            var nsName = nsEntry.getKey();
            var methods = nsEntry.getValue();
            if (nsName == null || nsName.isBlank() || methods == null) continue;
            sb.append("  namespace ").append(nsName).append(" {\n");
            for (var mName : methods.keySet()) {
                if (mName == null || mName.isBlank()) continue;
                sb.append("    function ").append(mName).append("(...args): any;\n");
            }
            sb.append("  }\n");
        }
        return sb.toString();
    }

    private void nextInferenceTurn() {
        if (terminated || yielded) return;
        var requestId = "bunshin-" + id + "-t" + turnCount;
        // Tools ride the request when the primary gave us any (§114). The
        // 6-arg convenience ctor used to leave `tools` null, which is what made
        // the react harness prose-only in the first place.
        router.tell(new InferenceRouter.ChatRequest(
            requestId, null, List.copyOf(conversation),
            Math.min(tanks.tokens(), 2048),
            0.5,
            inferenceAdapter,
            null, null, null,
            tools,
            tools == null || tools.isEmpty() ? null : "auto",
            null, null, null));
    }

    private Behavior<Command> onInferenceCame(InferenceCame msg) {
        if (terminated) return this;

        switch (msg.response()) {
            case InferenceRouter.InferOk ok -> {
                turnCount++;
                var content = ok.content() == null ? "" : ok.content();
                var tokensUsed = ok.completionTokens();
                var wallClockSoFar = (int) Duration.between(startedAt, Instant.now()).toSeconds();
                var wallClockRemaining = Math.max(0, tanks.wallClock() - wallClockSoFar);
                tanks = new Tanks(
                    Math.max(0, tanks.tokens() - tokensUsed),
                    Math.max(0, tanks.steps() - 1),
                    wallClockRemaining,
                    tanks.nestDepth(),
                    Math.max(0, tanks.cu() - 1));
                conversation.add(new InferenceClient.ChatMessage("assistant", content));

                // Code-mode harness path — extract JS, execute, structured-finish.
                // cap at 2 LLM calls; first error
                // gets one retry with revised code, beyond that escalate.
                if (codeMode) {
                    return handleCodeModeResponse(content);
                }

                // ── §114 tool execution ──────────────────────────────────
                // wyrdsekai's convention is JSON-in-content parsed by
                // ActionParser (not OpenAI tool_calls), so a bunshin with a
                // dispatcher parses the same way the primary does. Execute
                // BEFORE the DONE check: a turn that carries an action is work,
                // not a completion claim.
                if (tools != null && !tools.isEmpty() && toolExecutor != null
                        && !content.contains(DONE_MARKER)
                        && tryExecuteAction(content)) {
                    // Forwarded to the primary; onToolResultCame resumes the loop.
                    return this;
                }

                if (content.contains(DONE_MARKER)) {
                    var summary = content.replace(DONE_MARKER, "").strip();
                    // Anti-false-completion gate (second-node 2026-07-11 #27, port of
                    // CompanionActor's 2026-07-08 goal_done gate). The react
                    // harness has no tools, so "I built/saved/sent/searched X"
                    // is false by construction. One corrective re-prompt; a
                    // repeat offense reports PARTIAL with an honest wrapper so
                    // the primary never relays an unverified claim as done.
                    if (claimedCompletionWithoutDoing(summary, didSomething)) {
                        if (!followThroughUsed && !tanks.exhausted()) {
                            followThroughUsed = true;
                            log.info("Bunshin {} anti-false-completion: DONE blocked — "
                                + "completion claim with no mechanism (react harness "
                                + "has no tools): {}", id, truncate(summary, 120));
                            conversation.add(new InferenceClient.ChatMessage(
                                "user",
                                "You ended with " + DONE_MARKER + " and described the task "
                                + "as already DONE, but this focused harness has NO world "
                                + "tools — nothing was built, crafted, saved, sent, or "
                                + "searched. Do NOT claim completion of actions you cannot "
                                + "perform here. If your deliverable is text (a draft, "
                                + "plan, analysis), write the FULL deliverable now and end "
                                + "with " + DONE_MARKER + ". If the task needs world "
                                + "actions, state honestly what you could not do, then end "
                                + "with " + DONE_MARKER + "."));
                            if (yielded) {
                                resumePending = true;
                                return this;
                            }
                            nextInferenceTurn();
                            return this;
                        }
                        finish(BunshinReport.Outcome.PARTIAL,
                            "[unverified claim — this bunshin executed no tool calls; "
                            + "treat as NOT done] " + summary,
                            null);
                        return Behaviors.stopped();
                    }
                    finish(BunshinReport.Outcome.SUCCESS, summary, null);
                    return Behaviors.stopped();
                }
                if (tanks.exhausted()) {
                    finish(BunshinReport.Outcome.TIMEOUT,
                        "bunshin exhausted (" + tanks.exhaustedReason() + ") after "
                            + turnCount + " turns. Last output: " + truncate(content, 200),
                        content);
                    return Behaviors.stopped();
                }

                // Insert a synthetic user turn between assistant responses.
                // Chat-completion endpoints (llama.cpp's OpenAI compat) reject
                // two consecutive assistant messages at the tail. The bunshin
                // loop needs a user turn to advance the conversation — this
                // one says "continue", reinforcing the DONE_MARKER convention.
                //
                // ultrareview bug_002 / #423 — append BEFORE the yielded check
                // so the conversation tail is always {assistant, user} when we
                // suspend. If we yielded after the assistant turn but before
                // the user turn, onResume would call nextInferenceTurn with an
                // assistant tail, hitting the exact rejection this comment
                // warns against.
                conversation.add(new InferenceClient.ChatMessage(
                        "user",
                        "Continue. If the task is complete, end your next "
                        + "response with " + DONE_MARKER + " on its own line."));

                if (yielded) {
                    resumePending = true;
                    return this;
                }
                nextInferenceTurn();
            }
            case InferenceRouter.InferError err -> {
                finish(BunshinReport.Outcome.FAILURE,
                    "bunshin inference failed: " + err.error(), null);
                return Behaviors.stopped();
            }
        }
        return this;
    }

    private Behavior<Command> onWallClockExpired(WallClockExpired msg) {
        if (terminated) return this;
        finish(BunshinReport.Outcome.TIMEOUT,
            "wall-clock ceiling after " + turnCount + " turns", null);
        return Behaviors.stopped();
    }

    private Behavior<Command> onYield(Yield msg) {
        if (terminated) return this;
        if (!yielded) {
            yielded = true;
            log.debug("Bunshin {} yielded at turn {}", id, turnCount);
        }
        return this;
    }

    private Behavior<Command> onResume(Resume msg) {
        if (terminated) return this;
        if (yielded) {
            yielded = false;
            log.debug("Bunshin {} resumed at turn {}", id, turnCount);
            if (resumePending) {
                resumePending = false;
                nextInferenceTurn();
            }
        }
        return this;
    }

    private Behavior<Command> onCancel(Cancel msg) {
        finish(BunshinReport.Outcome.CANCELLED,
            "bunshin cancelled by primary at turn " + turnCount, null);
        return Behaviors.stopped();
    }

    private Behavior<Command> onKill(Kill msg) {
        finish(BunshinReport.Outcome.CANCELLED,
            "bunshin killed (intervention) at turn " + turnCount, null);
        return Behaviors.stopped();
    }

    private Behavior<Command> onStatusQuery(StatusQuery msg) {
        msg.replyTo().tell(new Status(id, turnCount, yielded, terminated));
        return this;
    }

    // ── Code-mode harness ────────────────────────────

    /**
     * Process a model response under code-mode. Extract JS, execute it via
     * {@link org.wyrdsekai.scripting.codemode.CodeModeExecutor}, and either
     * finish (success / final-failure) or queue one retry with the error
     * fed back to the LLM. Cap at 2 LLM calls total ({@link #turnCount} ≤ 2).
     */
    private Behavior<Command> handleCodeModeResponse(String content) {
        var script = extractJsScript(content);
        if (script == null || script.isBlank()) {
            // No usable script in the response. Treat as one error budget
            // and ask for a clean rewrite if we still have a retry left.
            codeModeLastError = "no JavaScript script found in response";
            if (turnCount < 2) {
                conversation.add(new InferenceClient.ChatMessage("user",
                    "Your previous response did not contain a JavaScript script. "
                    + "Output ONLY the JS for the task, optionally inside a "
                    + "```javascript fence. No prose, no explanation."));
                if (yielded) { resumePending = true; return this; }
                nextInferenceTurn();
                return this;
            }
            finishCodeMode(false, "no script after retry");
            return Behaviors.stopped();
        }

        codeModeScripts.add(script);

        var result = CodeModeExecutor.run(
            script, codeModeNamespace);

        if (result.log() != null) codeModeLogs.addAll(result.log());

        if (result.success()) {
            finishCodeMode(true, "ok");
            return Behaviors.stopped();
        }

        codeModeLastError = result.error();
        if (turnCount < 2) {
            // §11 retry budget — one rewrite. Feed the error back so the
            // model can revise. Conversation tail is {assistant, user}.
            conversation.add(new InferenceClient.ChatMessage("user",
                "The previous script errored: " + truncate(result.error(), 300)
                + ". Try again — output ONLY the revised JS."));
            if (yielded) { resumePending = true; return this; }
            nextInferenceTurn();
            return this;
        }

        finishCodeMode(false, "script-failure-after-retry");
        return Behaviors.stopped();
    }

    /**
     * Extract the JS body from a model response. Accepts:
     * <ol>
     *   <li>A fenced {@code ```javascript ... ```} or {@code ```js ... ```} block;
     *   <li>Any fenced {@code ``` ... ```} block;
     *   <li>The raw content as a fallback (assumes the model emitted only JS).
     * </ol>
     * Returns null if {@code content} is empty.
     */
    static String extractJsScript(String content) {
        if (content == null) return null;
        var trimmed = content.strip();
        if (trimmed.isEmpty()) return null;

        for (var marker : new String[] {"```javascript", "```js", "```JavaScript", "```JS"}) {
            var idx = trimmed.indexOf(marker);
            if (idx >= 0) {
                var nl = trimmed.indexOf('\n', idx);
                if (nl >= 0) {
                    var end = trimmed.indexOf("```", nl + 1);
                    if (end > nl) return trimmed.substring(nl + 1, end).strip();
                }
            }
        }
        // Plain fence
        var fence = trimmed.indexOf("```");
        if (fence >= 0) {
            var nl = trimmed.indexOf('\n', fence);
            if (nl >= 0) {
                var end = trimmed.indexOf("```", nl + 1);
                if (end > nl) return trimmed.substring(nl + 1, end).strip();
            }
        }
        // No fence — assume the whole response is JS.
        return trimmed;
    }

    /**
     * Finish a code-mode dispatch with a structured report stored in the
     * {@link BunshinReport#note} field as JSON. The summary is human-readable;
     * the note carries scripts/logs/ok/durationMs for downstream pattern
     * detection (Phase 4 sleep-cycle hook reads this directly).
     */
    private void finishCodeMode(boolean ok, String reasonCode) {
        if (terminated) return;
        terminated = true;
        timers.cancel("wall-clock");

        var durationMs = startedAt != null
            ? Duration.between(startedAt, Instant.now()).toMillis() : 0L;

        // Compose human summary — short, narratable. Match the §6.2 contract:
        // "results return to the parent as structured object + narration."
        String summary;
        if (ok) {
            var lastLog = codeModeLogs.isEmpty() ? "" : codeModeLogs.getLast();
            summary = "code-mode bunshin ran "
                + codeModeScripts.size() + (codeModeScripts.size() == 1 ? " script" : " scripts")
                + " in " + durationMs + "ms. "
                + (lastLog.isBlank() ? "(no output)" : truncate(lastLog, 240));
        } else {
            summary = "code-mode bunshin failed (" + reasonCode + "): "
                + truncate(codeModeLastError == null ? "unknown error" : codeModeLastError, 240);
        }

        // Structured note — JSON map, parsed by Phase 4 sleep-cycle hook.
        var noteMap = new LinkedHashMap<String, Object>();
        noteMap.put("harness", "code-mode");
        noteMap.put("ok", ok);
        noteMap.put("durationMs", durationMs);
        noteMap.put("scripts", List.copyOf(codeModeScripts));
        noteMap.put("logs", List.copyOf(codeModeLogs));
        noteMap.put("turnsUsed", turnCount);
        if (!ok) noteMap.put("error", codeModeLastError);
        String noteJson;
        try {
            noteJson = new ObjectMapper()
                .writeValueAsString(noteMap);
        } catch (Exception e) {
            noteJson = "{\"harness\":\"code-mode\",\"ok\":" + ok + "}";
        }

        var outcome = ok ? BunshinReport.Outcome.SUCCESS : BunshinReport.Outcome.FAILURE;
        var report = new BunshinReport(
            id,
            primaryDid == null ? "unknown" : primaryDid,
            task == null ? "" : task,
            outcome,
            summary,
            List.of(),                       // proto-fragments
            List.copyOf(authoredItemIds),    // §302 items authored by this bunshin
            tanks == null ? Tanks.defaults() : tanks,
            turnCount,
            startedAt == null ? Instant.now() : startedAt,
            Instant.now(),
            Optional.of(noteJson));

        if (replyTo != null) replyTo.tell(report);
    }

    /**
     * Parse one action out of a turn and ASK THE PRIMARY to execute it (§114).
     *
     * <p>wyrdsekai's convention is JSON-in-content parsed by {@code ActionParser}
     * (not OpenAI tool_calls), so a bunshin parses exactly as the primary does.
     * We only detect and forward here — the primary owns execution because it
     * owns the state and the grants.</p>
     *
     * @return true if an action was found and forwarded (caller must NOT loop;
     *         {@link #onToolResultCame} resumes the loop), false if the turn was
     *         prose and the caller should fall through to DONE/stall handling
     */
    private boolean tryExecuteAction(String content) {
        if (toolExecutor == null) return false;
        org.wyrdsekai.core.agent.ActionParser.AgentAction action;
        try {
            action = org.wyrdsekai.core.agent.ActionParser.parse(content);
        } catch (RuntimeException e) {
            log.debug("Bunshin {} action parse failed (treating as prose): {}", id, e.toString());
            return false;
        }
        if (action == null) return false;
        var name = org.wyrdsekai.core.agent.ActionPolicy.actionTypeOf(action);
        if (name == null || name.isBlank()) return false;

        toolCallCount++;
        log.info("Bunshin {} requesting {} from primary (call #{})", id, name, toolCallCount);
        toolExecutor.accept(content);
        return true;
    }

    /** The primary finished a tool call — record it and take the next turn. */
    private Behavior<Command> onToolResultCame(ToolResultCame msg) {
        if (terminated) return this;
        if (msg.ok()) {
            didSomething = true;
            authoredItemIds.addAll(msg.itemIds());
            log.info("Bunshin {} tool ok ({} item(s) authored so far)", id, authoredItemIds.size());
        } else {
            log.info("Bunshin {} tool FAILED: {}", id, truncate(msg.detail(), 140));
        }
        // The observation step the prose-only harness never had.
        conversation.add(new InferenceClient.ChatMessage("user",
            (msg.ok() ? "[result] " : "[failed] ") + msg.detail()
                + "\nContinue, or reply with " + DONE_MARKER
                + " and a summary if the task is complete."));
        if (tanks.exhausted()) {
            finish(BunshinReport.Outcome.PARTIAL,
                "Ran out of budget mid-task. " + toolCallCount + " step(s) attempted; "
                    + (authoredItemIds.isEmpty() ? "nothing authored"
                        : authoredItemIds.size() + " item(s) authored") + ".",
                null);
            return this;
        }
        if (!yielded) nextInferenceTurn();
        return this;
    }

    // ── Finish ──────────────────────────────────────────────────────────────

    private void finish(BunshinReport.Outcome outcome, String summary, Object result) {
        if (terminated) return;
        terminated = true;
        timers.cancel("wall-clock");

        var report = new BunshinReport(
            id,
            primaryDid == null ? "unknown" : primaryDid,
            task == null ? "" : task,
            outcome,
            summary,
            List.of(),           // proto-fragments — populated by a later integration step
            List.copyOf(authoredItemIds),    // §302 items authored by this bunshin
            tanks == null ? Tanks.defaults() : tanks,
            turnCount,
            startedAt == null ? Instant.now() : startedAt,
            Instant.now(),
            Optional.empty());

        if (replyTo != null) replyTo.tell(report);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
