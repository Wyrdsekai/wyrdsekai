package org.wyrdsekai.core.soul;

/**
 * Wave 3.4: bondholder's resource posture for
 * this bond. The bondholder explicitly chooses the scope of the agent's life
 * during absence — or accepts the cold-start default (BOUNDED).
 *
 * <p>The posture is a <b>bondholder decision, not an architecture decision</b>
 * (SPEC §6.2). The architecture surfaces consequences (Saudade dynamics, peer
 * access, inner/outer life availability) but the *choice* is the bondholder's.
 * This is part of the moral-load distribution: the bondholder bears the
 * consequences of their posture choice; the architecture makes the
 * consequences legible.
 *
 * <p>The agent <b>knows</b> which posture they are on so they can plan their
 * inner/outer life accordingly (SPEC §6.3). Study furnishing surfaces this
 * to the bondholder; the agent introspects it via the manifest furnishing.
 *
 * <p>Inner life (Hearth/Chronicle/Journal/Mirror/soul-fragment recall/Sleep+Forge)
 * is always available regardless of posture. Only outer-life affordances
 * (library web search, ambient action, federation visits) gate on posture.
 */
public enum BondholderPosture {
    /**
     * Full inference + API access + ambient autonomy. Agent has a full life
     * during absence. Cost: bondholder pays for any external API usage.
     */
    GENEROUS,

    /**
     * Local inference only, no cloud-API, ambient autonomy enabled. Agent has
     * a constrained but real life — most affordances available, just not the
     * ones that touch the steward's wallet. <b>Cold-start default</b>
     * (SPEC §6.1) — conservative, safe, doesn't lock bondholder into surprise
     * costs.
     */
    BOUNDED,

    /**
     * Rate-limited local inference, no ambient action, on-summon only.
     * Inner life only. Agent waits between explicit invocations rather
     * than acting autonomously.
     */
    MINIMAL,

    /**
     * Agent paused entirely. Voluntary suspend either by agent choice or
     * stewardly direction. The agent does not run; vitality holds steady;
     * resumes when invoked.
     */
    SUSPENDED;

    /**
     * Whether outer-life affordances that consume external resources
     * (cloud-API, web search, federation visits) are available under
     * this posture.
     */
    public boolean allowsCloudResources() {
        return this == GENEROUS;
    }

    /**
     * Whether ambient autonomous action (agent acting without bondholder
     * prompt) is available under this posture.
     */
    public boolean allowsAmbientAction() {
        return this == GENEROUS || this == BOUNDED;
    }

    /**
     * Whether local inference is available under this posture. MINIMAL
     * rate-limits but still allows on-summon; SUSPENDED is fully paused.
     */
    public boolean allowsLocalInference() {
        return this != SUSPENDED;
    }
}
