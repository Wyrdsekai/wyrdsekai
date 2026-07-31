package org.wyrdsekai.core.recipe;

import java.time.Duration;

/**
 * Dispatches a BACKEND / GOOSE_RECIPE / LONG_JOB step to a coding backend (Pi default) or the
 * bunshin scheduler. Wired in P5; until then the runner is constructed without
 * one and such steps short-circuit to {@code NEEDS_BACKEND} rather than failing.
 *
 * <p>The dispatcher may write outputs back into the {@link RecipeContext} (e.g. a metric a later
 * GATE reads). It must NOT make deploy decisions — gates and deploy stay in the runner (§4).
 *
 * <p>#1012 — the runner passes an effective per-step timeout via
 * {@link #dispatchWith(RecipeStep, RecipeContext, Duration)}; impls that don't honour
 * overrides fall back to the legacy {@link #dispatch} path. {@link DispatchOutcome#transientFailure}
 * tells the runner whether to consume a retry attempt (TimeoutException / network) versus
 * fail-fast on a logical mismatch (success_contract miss, structured backend error).
 */
public interface BackendDispatcher {

    /** @return true if the step succeeded per its success contract. */
    boolean dispatch(RecipeStep step, RecipeContext ctx);

    /**
     * Per-call timeout override with a richer outcome record. Default impl delegates to the
     * legacy {@link #dispatch} path so existing in-test lambdas keep compiling — they just
     * can't report transient failure (legacy impls always non-transient).
     */
    default DispatchOutcome dispatchWith(RecipeStep step, RecipeContext ctx, Duration timeout) {
        return new DispatchOutcome(dispatch(step, ctx), false);
    }

    /**
     * Result of a single dispatch attempt. {@code transientFailure} tells the runner the
     * failure was infrastructural (timeout, network) and worth retrying; a logical failure
     * (success_contract miss, structured backend error) leaves it false so the recipe halts.
     */
    record DispatchOutcome(boolean ok, boolean transientFailure) {
        public static DispatchOutcome success() { return new DispatchOutcome(true, false); }
        public static DispatchOutcome logicalFail() { return new DispatchOutcome(false, false); }
        public static DispatchOutcome transientFail() { return new DispatchOutcome(false, true); }
    }
}
