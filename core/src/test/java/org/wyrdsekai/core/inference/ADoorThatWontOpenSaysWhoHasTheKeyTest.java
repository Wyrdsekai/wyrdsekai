package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A tier-gated capability failure names the cause and the remedy.
 *
 * <p>Live 2026-08-10, during the bondholder's first real evening with a
 * day-old companion: she reached for deep reasoning mid-conversation, the
 * router said "Cloud inference not available for this agent (tier:
 * household)", and all she could tell her person was "the reasoning tool
 * isn't available right now" — a dead end delivered to the one human who
 * could actually open the door. A painted-on door is wrong in both
 * directions: hiding the tool wastes a real capability; a dead-end error
 * wastes her honesty.</p>
 *
 * <p>The gate itself is correct — trust tiers are earned, that's the
 * design. What changes is that the refusal now carries the key: which of
 * the two causes applies (backend above her earned tier vs. no capable
 * backend at all) and the concrete steward remedy for each.</p>
 */
class ADoorThatWontOpenSaysWhoHasTheKeyTest {

    private static String routerSrc() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    private static String actorSrc() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    /** The two causes are distinguished by asking the registry, not guessed. */
    @Test
    void the_error_diagnoses_tier_gap_versus_missing_backend() throws Exception {
        var s = routerSrc();
        assertThat(s).contains("var anyTier = capabilityRegistry.resolve(req.capability());");
        assertThat(s).contains("above this companion's earned tier");
        assertThat(s).contains("no configured backend offers it");
    }

    /** Both branches name the concrete steward remedy, not just the refusal. */
    @Test
    void both_branches_carry_the_remedy() throws Exception {
        var s = routerSrc();
        // The remedy appears in the tier-gap branch AND the no-backend branch.
        int first = s.indexOf("WYRDSEKAI_MODEL_COMPLEX");
        int second = s.indexOf("WYRDSEKAI_MODEL_COMPLEX", first + 1);
        assertThat(first).as("tier-gap branch names the config key").isGreaterThan(0);
        assertThat(second).as("no-backend branch names it too").isGreaterThan(first);
        assertThat(s).contains("capability_unavailable: '");
    }

    /** The old dead-end string is gone. */
    @Test
    void the_dead_end_message_no_longer_exists() throws Exception {
        assertThat(routerSrc())
            .doesNotContain("\"Cloud inference not available for this agent");
    }

    /** She tells her person what is missing and who can fix it — no config keys aloud. */
    @Test
    void her_fallback_points_at_the_steward_not_at_nothing() throws Exception {
        var s = actorSrc();
        assertThat(s).contains(
            ".startsWith(\"capability_unavailable\")");
        assertThat(s).contains("The steward can enable it");
        assertThat(s)
            .as("machinery stays out of her mouth — the key lives in the log")
            .doesNotContain("speak(\"I reached for deeper thinking\" + \"WYRDSEKAI");
    }
}
