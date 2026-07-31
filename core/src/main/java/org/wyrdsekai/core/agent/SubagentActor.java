package org.wyrdsekai.core.agent;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Subagent actor for context-isolated task execution.
 *
 * <p>Heavy tool results (Library search returns, long code blocks, analysis tasks)
 * bloat the parent agent's context window. SubagentActor processes these in isolation:
 * <ul>
 *   <li>Receives a focused task description (no soul fragments, no conversation history)</li>
 *   <li>Has access to inference (via InferenceRouter) and the same tool set</li>
 *   <li>Runs with a token budget and time limit</li>
 *   <li>Returns a compressed summary (max 500 tokens) to the parent</li>
 *   <li>Terminates after completing the task or hitting the timeout</li>
 * </ul>
 *
 * <p>Design: pure tool execution — no soul, no vitality, no personality contamination.
 * The parent agent's identity stays on the small model; heavy thinking is delegated here.</p>
 */
public class SubagentActor extends AbstractBehavior<SubagentActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(SubagentActor.class);

    // --- Protocol ---

    public sealed interface Command {}

    /** Start processing a task. */
    public record Execute(SubagentRequest request, ActorRef<SubagentResult> replyTo) implements Command {}

    /** Internal: inference response received. */
    private record InferenceResponse(InferenceRouter.InferResponse response) implements Command {}

    /** Internal: timeout reached. */
    private record Timeout() implements Command {}

    // --- Request/Result ---

    /**
     * Task request for the subagent.
     *
     * @param taskId     unique task ID
     * @param parentId   parent agent's entity ID (for logging)
     * @param task       task description (natural language)
     * @param context    optional additional context (search results, data, etc.)
     * @param maxTokens  max response tokens (default: 500)
     * @param timeout    max execution time (default: 2 minutes)
     */
    public record SubagentRequest(
        String taskId,
        String parentId,
        String task,
        String context,
        int maxTokens,
        Duration timeout
    ) {
        public SubagentRequest(String parentId, String task, String context) {
            this(UUID.randomUUID().toString(), parentId, task, context, 500, Duration.ofMinutes(2));
        }
    }

    /**
     * Result from subagent execution.
     *
     * @param taskId   the task ID from the request
     * @param summary  compressed summary of results (max 500 tokens)
     * @param success  whether the task completed successfully
     * @param error    error message if failed (null on success)
     */
    public record SubagentResult(
        String taskId,
        String summary,
        boolean success,
        String error
    ) {
        public static SubagentResult success(String taskId, String summary) {
            return new SubagentResult(taskId, summary, true, null);
        }
        public static SubagentResult failure(String taskId, String error) {
            return new SubagentResult(taskId, null, false, error);
        }
    }

    // --- State ---

    private final ActorRef<InferenceRouter.Command> inferenceRouter;
    private final ActorRef<InferenceRouter.InferResponse> inferenceAdapter;
    private ActorRef<SubagentResult> replyTo;
    private SubagentRequest request;

    private SubagentActor(ActorContext<Command> context,
                          TimerScheduler<Command> timers,
                          ActorRef<InferenceRouter.Command> inferenceRouter) {
        super(context);
        this.inferenceRouter = inferenceRouter;
        this.inferenceAdapter = context.messageAdapter(
            InferenceRouter.InferResponse.class, InferenceResponse::new);
    }

    public static Behavior<Command> create(ActorRef<InferenceRouter.Command> inferenceRouter) {
        return Behaviors.setup(ctx -> Behaviors.withTimers(timers ->
            new SubagentActor(ctx, timers, inferenceRouter)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Execute.class, this::onExecute)
            .onMessage(InferenceResponse.class, this::onInferenceResponse)
            .onMessage(Timeout.class, this::onTimeout)
            .build();
    }

    private Behavior<Command> onExecute(Execute msg) {
        this.request = msg.request();
        this.replyTo = msg.replyTo();

        log.debug("Subagent starting task '{}' for parent '{}': {}",
            request.taskId(), request.parentId(),
            request.task().length() > 80 ? request.task().substring(0, 80) + "..." : request.task());

        // Set timeout
        getContext().scheduleOnce(request.timeout(), getContext().getSelf(), new Timeout());

        // Build system prompt — focused, no personality
        var systemPrompt = """
            You are a focused task executor. Process the following task and return a concise summary.
            Rules:
            - Be concise. Maximum 3-4 sentences.
            - Include only the most relevant findings.
            - If the task involves search, list the top results with brief descriptions.
            - Do not add personality, greetings, or filler.
            """;

        // Build user message — task + optional context
        var userMessage = new StringBuilder();
        userMessage.append("Task: ").append(request.task());
        if (request.context() != null && !request.context().isBlank()) {
            userMessage.append("\n\nContext:\n").append(request.context());
        }

        var messages = new ArrayList<InferenceClient.ChatMessage>();
        messages.add(new InferenceClient.ChatMessage("system", systemPrompt));
        messages.add(new InferenceClient.ChatMessage("user", userMessage.toString()));

        var requestId = "subagent-" + request.taskId();
        inferenceRouter.tell(new InferenceRouter.ChatRequest(
            requestId, null, messages,
            request.maxTokens(), 0.3,
            inferenceAdapter));

        return this;
    }

    private Behavior<Command> onInferenceResponse(InferenceResponse msg) {
        switch (msg.response()) {
            case InferenceRouter.InferOk ok -> {
                log.debug("Subagent task '{}' complete ({} tokens)",
                    request.taskId(), ok.completionTokens());
                replyTo.tell(SubagentResult.success(request.taskId(), ok.content()));
            }
            case InferenceRouter.InferError error -> {
                log.warn("Subagent task '{}' inference failed: {}",
                    request.taskId(), error.error());
                replyTo.tell(SubagentResult.failure(request.taskId(), error.error()));
            }
        }
        return Behaviors.stopped();
    }

    private Behavior<Command> onTimeout(Timeout msg) {
        log.warn("Subagent task '{}' timed out after {}", request.taskId(), request.timeout());
        replyTo.tell(SubagentResult.failure(request.taskId(),
            "Subagent timed out after " + request.timeout()));
        return Behaviors.stopped();
    }
}
