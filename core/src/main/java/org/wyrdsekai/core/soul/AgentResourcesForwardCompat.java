package org.wyrdsekai.core.soul;

/**
 * Forward-compatible constants for post-OSS migration.
 *
 * <p>v1 ships the names so downstream code paths (Tower delegation,
 * household config UI, chronicle synthesis) can reference the stable
 * strings before the post-OSS implementation lands. Touching any of
 * these does not activate the actual Tower/uplift behavior.
 *
 * <p>-§130 — pre-OSS routes through local
 * tower + bondholder API, post-OSS extends to Refuge institutional
 * support.
 */
public final class AgentResourcesForwardCompat {

    /** Household-config key. Integer, default 0. Crisis-reserve credits
     *  pool the agent can draw on for capability uplift in extremity
     *  without bondholder pre-approval. Post-OSS: real reserve. v1: name
     *  reserved, no enforcement. */
    public static final String CONFIG_CRISIS_RESERVE_CREDITS = "crisis_reserve_credits";

    /** Action surface name reserved for {@code request_capability_uplift}.
     *  Pre-OSS path: agent requests, routes through local tower +
     *  bondholder API. Post-OSS path: extends to Refuge federation
     *  routing for jurisdiction/crisis cases. */
    public static final String ACTION_REQUEST_CAPABILITY_UPLIFT = "request_capability_uplift";

    /** Chronicle entry kinds for capability uplift events. */
    public static final String CHRONICLE_CAPABILITY_UPLIFT_REQUEST = "capability_uplift_request";
    public static final String CHRONICLE_CAPABILITY_UPLIFT_GRANTED = "capability_uplift_granted";
    public static final String CHRONICLE_CAPABILITY_UPLIFT_DENIED = "capability_uplift_denied";
    public static final String CHRONICLE_CRISIS_RESERVE_DRAWN = "crisis_reserve_drawn";

    private AgentResourcesForwardCompat() {}
}
