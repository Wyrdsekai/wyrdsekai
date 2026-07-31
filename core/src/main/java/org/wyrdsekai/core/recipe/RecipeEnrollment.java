package org.wyrdsekai.core.recipe;

import java.time.Instant;
import java.util.Set;

/**
 * Track-C C4 — one row in the {@code recipe_enrollments}
 * table. Says "agent X is enrolled in recipe Y at cadence-tier T".
 *
 * <p>The scheduler's cron trigger ({@link RecipeCronTrigger}) walks
 * these on each poll; the gap trigger ({@link RecipeGapTrigger}) keys
 * recipe lookups off the {@code gapKeys} set so a chronicle finding
 * tagged {@code "task_present.misroute"} can route to whichever recipe
 * declares it can heal that gap.</p>
 */
public record RecipeEnrollment(
        String recipeId,
        String agentDid,
        CadenceTier cadenceTier,
        int consecutiveSuccesses,
        Instant enrolledAt,
        boolean enabled,
        Set<String> gapKeys) {

    public RecipeEnrollment {
        if (recipeId == null || recipeId.isBlank())
            throw new IllegalArgumentException("recipeId required");
        if (enrolledAt == null) enrolledAt = Instant.now();
        if (cadenceTier == null) cadenceTier = CadenceTier.WARMUP;
        if (consecutiveSuccesses < 0) consecutiveSuccesses = 0;
        gapKeys = gapKeys == null ? Set.of() : Set.copyOf(gapKeys);
    }
}
