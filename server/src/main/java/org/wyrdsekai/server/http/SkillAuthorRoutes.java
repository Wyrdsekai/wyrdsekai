package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.skill.LiveSkillAuthoring;
import org.wyrdsekai.core.skill.SkillDraftStore;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * the on-demand authoring trigger.
 *
 * <p>The Forge sleep-pass authors verification harnesses for pending skill drafts automatically,
 * once per sleep. This is the complementary <b>on-demand</b> path: a steward (curl / Study
 * furnishing), the recipe scheduler (shell &rarr; HTTP), or a test can force authoring now without
 * waiting for the next sleep. Both paths call the same idempotent
 * {@link LiveSkillAuthoring}/{@code authorPendingFor} — drafts that already carry a harness are
 * skipped, so the two never double-work.</p>
 *
 * <p>Authoring runs in-process because it needs the live drive model + Library; it is dispatched
 * OFF the request thread (the model call blocks) and the endpoint returns {@code 202}-style
 * immediately. Self-contained: a node without the inference router or Library answers 503.</p>
 *
 * <ul>
 *   <li>{@code POST /api/skill/author?agent={did}} — author harnesses for the agent's pending drafts</li>
 * </ul>
 */
public final class SkillAuthorRoutes {

    private static final Logger log = LoggerFactory.getLogger(SkillAuthorRoutes.class);

    private final ActorRef<InferenceRouter.Command> inferenceRouter;
    private final Scheduler scheduler;
    private final WyrdLuceneStore lucene;
    private final SkillDraftStore store;

    public SkillAuthorRoutes(ActorRef<InferenceRouter.Command> inferenceRouter, Scheduler scheduler,
                             WyrdLuceneStore lucene, SkillDraftStore store) {
        this.inferenceRouter = inferenceRouter;
        this.scheduler = scheduler;
        this.lucene = lucene;
        this.store = store;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/skill/author", this::author);
    }

    private void author(Context ctx) {
        var agent = ctx.queryParam("agent");
        if (agent == null || agent.isBlank()) {
            ctx.status(400).json(Map.of("error", "agent parameter required"));
            return;
        }
        if (inferenceRouter == null || lucene == null || store == null) {
            ctx.status(503).json(Map.of("error",
                "skill-harness authoring unavailable on this node (no inference router / Library)"));
            return;
        }
        int pending = store.countPending(agent);
        if (pending == 0) {
            ctx.json(Map.of("ok", true, "agent", agent, "pending", 0,
                "status", "no pending drafts to author"));
            return;
        }
        // Dispatch off the request thread — the model call blocks; authorPendingFor is idempotent.
        CompletableFuture.runAsync(() -> {
            try {
                var authoring = LiveSkillAuthoring.forAgent(inferenceRouter, scheduler, lucene, store, agent);
                int authored = authoring.authorPendingFor(agent);
                log.info("On-demand skill authoring for {}: {} harness(es) authored ({} pending)",
                    agent, authored, pending);
            } catch (Exception e) {
                log.warn("On-demand skill authoring failed for {}: {}", agent, e.getMessage());
            }
        });
        ctx.json(Map.of("ok", true, "agent", agent, "pending", pending,
            "status", "authoring dispatched"));
    }
}
