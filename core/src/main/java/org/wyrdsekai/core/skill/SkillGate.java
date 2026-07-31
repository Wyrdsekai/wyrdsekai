package org.wyrdsekai.core.skill;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.Optional;
import java.util.function.Function;

/**
 * a pluggable gate run at {@code WorkshopPinboard.approve} before a
 * draft is materialized.
 *
 * <p>Decoupled from {@link WorkshopPinboard} so the verifier and its harness source can be
 * wired at the edge: a {@code null} gate means no verification (full back-compat with the
 * pre-verifier approve path).</p>
 */
@FunctionalInterface
public interface SkillGate {

    /** @return {@code Optional.of(reason)} to BLOCK approval, or {@code Optional.empty()} to permit. */
    Optional<String> check(SkillDraft draft);

    /**
     * A gate backed by the deterministic {@link SkillVerifier}.
     *
     * <p>{@code harnessSource} resolves the frozen {@link AnchorHarness} for a draft — the
     * seam that P2/P3 (strong-model harness generation + anchor mining) fills. A draft with
     * <b>no harness is UNVERIFIED and permitted</b> (steward approval still applies); the gate
     * only blocks drafts that carry a harness and fail it. Runs as pure code — no model.</p>
     *
     * @param verifier     the runtime verifier
     * @param harnessSource resolves a draft's frozen harness (or null = unverified)
     * @param provider     world API provider (a capturing stub at authoring time)
     * @param caps         capability set the skill runs under (use a restricted set)
     */
    static SkillGate verifying(SkillVerifier verifier,
                               Function<SkillDraft, AnchorHarness> harnessSource,
                               ItemWorldApiProvider provider, ItemCapabilitySet caps) {
        return draft -> {
            AnchorHarness harness = harnessSource.apply(draft);
            if (harness == null || harness.cases() == null || harness.cases().isEmpty()) {
                return Optional.empty(); // unverified — allowed, but unprotected
            }
            SkillVerifier.Verdict v = verifier.verify(draft.draftId(), draft.code(), harness, provider, caps);
            if (v.passed()) {
                return Optional.empty();
            }
            String first = v.failures().isEmpty() ? "(no detail)" : v.failures().get(0).reason();
            return Optional.of(v.casesPassed() + "/" + v.casesRun()
                + " anchors passed; first failure: " + first);
        };
    }

    /**
     * The productionized gate: the harness travels ON the draft (authored off the hot path by
     * {@link SkillVerificationAuthoring}, persisted in {@code skill_drafts.harness_json}). This
     * is the wiring {@code WorkshopPinboard} uses in production — no harness store lookup, the
     * frozen harness is right there on the draft, exactly as a Trading-Post recipient receives it.
     */
    static SkillGate fromPersistedHarness(SkillVerifier verifier,
                                          ItemWorldApiProvider provider, ItemCapabilitySet caps) {
        return verifying(verifier, SkillDraft::verificationHarness, provider, caps);
    }
}
