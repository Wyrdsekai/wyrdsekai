package org.wyrdsekai.core.inference;

import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.hermod.TaskEnvelope;
import org.wyrdsekai.hermod.TaskExecutor;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

/**
 * Binds hermod's executor seam to wyrdsekai's inference stack. Lives
 * OUTSIDE the hermod package on purpose: hermod stays extraction-pure;
 * this adapter is the household's own wiring.
 *
 * Envelope params: model, system (optional), prompt; tokenBudget caps
 * max_tokens.
 */
public final class HermodInferenceExecutor implements TaskExecutor {

    public static final String TASK_TYPE = "inference.chat";
    /** Full-request ride: params["chatRequestJson"] in, ChatResponse JSON out — tools intact. */
    public static final String TASK_TYPE_FULL = "inference.chat.full";

    private final InferenceClient client;
    private final long timeoutSeconds;
    private final boolean thinking; // seatMode: think=true, nothink=false

    public HermodInferenceExecutor(InferenceClient client, long timeoutSeconds) {
        this(client, timeoutSeconds, true);
    }

    public HermodInferenceExecutor(InferenceClient client, long timeoutSeconds, boolean thinking) {
        this.client = client;
        this.timeoutSeconds = timeoutSeconds;
        this.thinking = thinking;
    }

    @Override
    public boolean handles(String taskType) {
        return TASK_TYPE.equals(taskType) || TASK_TYPE_FULL.equals(taskType);
    }

    @Override
    public TaskResult execute(TaskEnvelope e) {
        if (TASK_TYPE_FULL.equals(e.taskType())) {
            return executeFull(e);
        }
        try {
            var messages = new ArrayList<InferenceClient.ChatMessage>();
            var system = e.params().getOrDefault("system", "");
            if (!system.isBlank()) {
                messages.add(new InferenceClient.ChatMessage("system", system));
            }
            messages.add(new InferenceClient.ChatMessage("user", e.params().get("prompt")));
            var request = new InferenceClient.ChatRequest(e.params().get("model"), messages,
                    (int) Math.min(e.tokenBudget(), Integer.MAX_VALUE), 0.7)
                .withThinking(thinking);
            var resp = client.chatCompletion(request).get(timeoutSeconds, TimeUnit.SECONDS);
            var out = resp.choices() == null || resp.choices().isEmpty()
                ? "" : resp.choices().get(0).message().content();
            return TaskResult.ok(e.envelopeId(), out);
        } catch (Exception ex) {
            return TaskResult.fail(e.envelopeId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private TaskResult executeFull(TaskEnvelope e) {
        try {
            var mapper = Json.mapper();
            var request = mapper.readValue(
                e.params().get("chatRequestJson"), InferenceClient.ChatRequest.class);
            if (!thinking) {
                request = request.withThinking(false);
            }
            var resp = client.chatCompletion(request).get(timeoutSeconds, TimeUnit.SECONDS);
            return TaskResult.ok(e.envelopeId(), mapper.writeValueAsString(resp));
        } catch (Exception ex) {
            return TaskResult.fail(e.envelopeId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
