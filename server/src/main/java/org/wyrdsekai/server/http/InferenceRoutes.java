package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * HTTP routes for testing inference: POST /api/inference/complete.
 * Only available when InferenceRouter is running.
 */
public final class InferenceRoutes {

    private final ActorSystem<?> system;
    private final ActorRef<InferenceRouter.Command> router;

    public InferenceRoutes(ActorSystem<?> system, ActorRef<InferenceRouter.Command> router) {
        this.system = system;
        this.router = router;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/inference/complete", this::handleComplete);
    }

    record CompleteRequest(
        @JsonProperty("system_prompt") String systemPrompt,
        String prompt,
        String model,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature
    ) {}

    record CompleteResponse(
        String content,
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens
    ) {}

    record ErrorResponse(String error) {}

    private void handleComplete(Context ctx) throws Exception {
        if (router == null) {
            ctx.status(503).json(new ErrorResponse("Inference not available"));
            return;
        }

        var req = Json.mapper().readValue(ctx.body(), CompleteRequest.class);
        if (req.prompt() == null || req.prompt().isBlank()) {
            ctx.status(400).json(new ErrorResponse("prompt is required"));
            return;
        }

        var requestId = UUID.randomUUID().toString();
        var maxTokens = req.maxTokens() != null ? req.maxTokens() : 512;
        var temperature = req.temperature() != null ? req.temperature() : 0.7;

        CompletionStage<InferenceRouter.InferResponse> future = AskPattern.ask(
            router,
            ref -> new InferenceRouter.InferRequest(
                requestId, req.model(), req.systemPrompt(), req.prompt(),
                maxTokens, temperature, ref),
            Duration.ofSeconds(60),
            system.scheduler()
        );

        var resp = future.toCompletableFuture().get();
        switch (resp) {
            case InferenceRouter.InferOk ok ->
                ctx.json(new CompleteResponse(ok.content(), ok.promptTokens(), ok.completionTokens()));
            case InferenceRouter.InferError err ->
                ctx.status(502).json(new ErrorResponse(err.error()));
        }
    }
}
