package org.wyrdsekai.core.skill;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.Provenance;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * binds a (pure) {@link SkillVerificationAuthoring} pipeline to the LIVE
 * runtime: the prod drive model via {@link InferenceRouter} (the {@code completion} seam) and the
 * Library via {@link WyrdLuceneStore} (the {@code retrieve} seam). The authoring classes stay
 * model-/transport-agnostic; this adapter is the only place that knows about Pekko + the router.
 *
 * <p><b>Threading:</b> the completion function BLOCKS on the inference ask, so the returned
 * authoring must be driven OFF the actor thread (e.g. {@code CompletableFuture.runAsync} from the
 * sleep-pass, or a recipe/scheduler thread) — never on a dispatcher. The model call uses the
 * {@code reasoning} capability at the {@code household} tier (local 9B + household peers, no cloud),
 * matching the SkillProposer's own call.</p>
 */
public final class LiveSkillAuthoring {

    private static final Logger log = LoggerFactory.getLogger(LiveSkillAuthoring.class);

    private LiveSkillAuthoring() {}

    /**
     * Build a fully-wired authoring pipeline for one agent. A fresh {@link ItemScriptExecutor} is
     * created per call (its mutation-gate source cache is then GC'd with the pipeline), so this is
     * cheap to construct per sleep / per trigger.
     */
    public static SkillVerificationAuthoring forAgent(
            ActorRef<InferenceRouter.Command> inferenceRouter, Scheduler scheduler,
            WyrdLuceneStore lucene, SkillDraftStore store, String agentId) {
        Function<String, String> completion = buildCompletion(inferenceRouter, scheduler, agentId);
        Function<String, List<SourcedSnippet>> retrieve = buildRetrieve(lucene);
        return new SkillVerificationAuthoring(
            new ModelAnchorMiner(retrieve, completion),
            new ModelHarnessGenerator(completion),
            store,
            new SkillVerifier(new ItemScriptExecutor()),
            StubItemWorldApiProvider.INSTANCE,
            ItemCapabilitySet.of(List.of()));
    }

    /** Bounded retries when the local model returns a blank completion (see {@link #buildCompletion}). */
    private static final int COMPLETION_ATTEMPTS = 3;
    /** Token budget per completion — wide enough that a multi-anchor harness JSON doesn't truncate. */
    private static final int COMPLETION_MAX_TOKENS = 1536;

    /**
     * prompt &rarr; drive-model text (cap:reasoning, household tier), or null on any failure.
     *
     * <p><b>Retry-on-blank:</b> the local 9B intermittently emits an empty completion on the longer
     * authoring prompts (measured ~25% on anchor-mining, and the failure mode that produced 0
     * harnesses for a non-trivial skill in the ceiling probe). The model is capable — run N+1 of the
     * SAME prompt returns a correct, discriminating answer — so the ceiling is reliability, not
     * comprehension. We retry a blank up to {@link #COMPLETION_ATTEMPTS} times before giving up; the
     * downstream seam classes still degrade safely (unverified) if every attempt is blank.</p>
     */
    private static Function<String, String> buildCompletion(
            ActorRef<InferenceRouter.Command> router, Scheduler scheduler, String agentId) {
        return prompt -> {
            if (router == null || scheduler == null) return null;
            for (int attempt = 1; attempt <= COMPLETION_ATTEMPTS; attempt++) {
                String out = completeOnce(router, scheduler, agentId, prompt);
                if (out != null && !out.isBlank()) return out;
                if (attempt < COMPLETION_ATTEMPTS) {
                    log.info("Skill-authoring completion blank (attempt {}/{}) — retrying",
                        attempt, COMPLETION_ATTEMPTS);
                }
            }
            return null;
        };
    }

    /** One inference ask; returns the content (possibly blank) or null on transport error. */
    private static String completeOnce(ActorRef<InferenceRouter.Command> router, Scheduler scheduler,
                                       String agentId, String prompt) {
        try {
            String requestId = UUID.randomUUID().toString();
            InferenceRouter.InferResponse resp =
                AskPattern.<InferenceRouter.Command, InferenceRouter.InferResponse>ask(
                    router,
                    replyTo -> new InferenceRouter.ToolInferRequest(
                        requestId, agentId, "reasoning", null, null, prompt,
                        COMPLETION_MAX_TOKENS, "household", replyTo),
                    Duration.ofSeconds(90), scheduler)
                .toCompletableFuture().join();
            if (resp instanceof InferenceRouter.InferOk ok) return ok.content();
            if (resp instanceof InferenceRouter.InferError err) {
                log.warn("Skill-authoring inference error: {}", err.error());
            }
            return null;
        } catch (Exception e) {
            log.warn("Skill-authoring inference failed: {}", e.getMessage());
            return null;
        }
    }

    /** query &rarr; Library chunks as sourced snippets (the leakage-barrier evidence). */
    private static Function<String, List<SourcedSnippet>> buildRetrieve(WyrdLuceneStore lucene) {
        return query -> {
            if (lucene == null || query == null || query.isBlank()) return List.of();
            try {
                var results = lucene.searchKnowledgeText(query, 5);
                var out = new ArrayList<SourcedSnippet>();
                for (var r : results) {
                    var meta = r.metadata();
                    String title = meta != null && meta.get("title") != null
                        ? String.valueOf(meta.get("title")) : null;
                    Provenance.TrustTier tier = Provenance.TrustTier.UNKNOWN;
                    if (meta != null && meta.get("trustTier") != null) {
                        try { tier = Provenance.TrustTier.valueOf(String.valueOf(meta.get("trustTier"))); }
                        catch (IllegalArgumentException ignore) { /* keep UNKNOWN */ }
                    }
                    // ref = the chunk's source (pack/source name) — enough for the leakage barrier
                    // (a kept anchor must cite SOMETHING real; ModelAnchorMiner drops uncited ones).
                    var source = new Provenance.Source("library", r.source(), null, title, List.of(), null);
                    out.add(new SourcedSnippet(r.content(), source, tier));
                }
                return out;
            } catch (Exception e) {
                log.warn("Skill-authoring retrieval failed for '{}': {}", query, e.getMessage());
                return List.of();
            }
        };
    }
}
