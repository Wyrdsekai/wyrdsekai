package org.wyrdsekai.between.recipe;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Wire protocol for cross-zone recipe borrowing over the NATS relay
 * ( resource-requisites, option b — "borrow a trusted peer zone").
 *
 * <p>When a heavy recipe (e.g. {@code run-emit-rft}, ~17h on 2×48GB) is blocked
 * locally by the resource preflight, the node can ask a <em>trusted peer zone</em>
 * with the hardware to run it instead. This mirrors the cross-zone inference path
 * ({@link org.wyrdsekai.between.inference.NatsInferenceProtocol}): the relay
 * already scopes household traffic with auth, so we ride it rather than becoming
 * a generic HTTP proxy.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Borrower subscribes to {@code federation.recipe.result.{requestId}}
 *       BEFORE publishing (avoid race).</li>
 *   <li>Borrower publishes {@link Request} to
 *       {@code federation.recipe.{targetZone}.run}.</li>
 *   <li>The lender's {@code NatsRecipeServer} trust-gates the source zone, runs
 *       the recipe locally via its own {@code RecipeService} (whose own resource
 *       preflight is the final authority), and publishes a {@link Response}.</li>
 *   <li>Borrower maps the response back into a {@code RecipeService.StartedRun}.</li>
 * </ol>
 * </p>
 *
 * <p>The lender's local preflight stays authoritative: the borrower's dispatcher
 * only does cheap <em>eligibility screening</em> from gossiped node resources
 * before choosing a peer; the peer re-checks for real (and can still
 * {@code RESOURCE_DENIED} — e.g. if the data files aren't present there).</p>
 */
public final class NatsRecipeProtocol {

    public record Request(
        @JsonProperty("requestId") String requestId,
        @JsonProperty("sourceZone") String sourceZone,
        @JsonProperty("agentDid") String agentDid,        // metering + attribution
        @JsonProperty("recipeName") String recipeName,
        @JsonProperty("params") Map<String, Object> params,
        @JsonProperty("requisitesNote") String requisitesNote  // human summary of what we need
    ) {}

    public record Response(
        @JsonProperty("requestId") String requestId,
        @JsonProperty("lenderZone") String lenderZone,
        @JsonProperty("status") String status,            // RecipeRunner.Status name, or "DENIED"
        @JsonProperty("message") String message,
        @JsonProperty("runId") String runId,
        @JsonProperty("error") String error               // non-null on transport/trust failure
    ) {
        public boolean ok() { return error == null; }
    }

    /** Subject the lender of {@code targetZone} subscribes to for incoming borrow requests. */
    public static String runSubject(String targetZone) {
        return "federation.recipe." + targetZone + ".run";
    }

    /** Subject a single request's result flows back on. Borrower subscribes; lender publishes once. */
    public static String resultSubject(String requestId) {
        return "federation.recipe.result." + requestId;
    }

    private NatsRecipeProtocol() {}
}
