package org.wyrdsekai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;

/**
 * the authoring-time orchestrator that compiles a frozen verification
 * harness for a draft skill and persists it WITH the draft.
 *
 * <p>This is the "use the strong model to BUILD the verifier" half, run <b>off the hot path</b>:
 * a recipe step or the Forge sleep-pass calls {@link #author} for pending drafts. The runtime
 * gate ({@link SkillGate}) then runs the frozen harness as pure code — no model in the loop.</p>
 *
 * <p>The pipeline is three injected stages, each cloud-optional:</p>
 * <ol>
 *   <li>{@link AnchorMiner} mines independently-verifiable anchors from the open world
 *       (local 9B + the Library, or a cloud model);</li>
 *   <li>{@link HarnessGenerator} compiles those anchor facts into deterministic test cases;</li>
 *   <li>the harness is stored on the draft ({@link SkillDraft#withHarness}) and persisted, so it
 *       travels with the skill through materialization and Trading-Post copy.</li>
 * </ol>
 *
 * <p>Two quality gates protect against the small-model authoring ceiling before a harness is
 * trusted (both pure code, no model, run right after generation):</p>
 * <ol>
 *   <li><b>Self-consistency</b> — the ORIGINAL skill must pass its own harness. If it doesn't, the
 *       model miscompiled an anchor (e.g. a wrong expected value) and the harness would wrongly block
 *       the correct skill at the gate. Drop it.</li>
 *   <li><b>Teeth</b> — {@link HarnessMutationGate} confirms the harness catches plausible wrong
 *       versions of the skill. A well-formed but non-discriminating ("toothless") harness reads as
 *       verified while protecting nothing; drop it so the skill stays honestly unverified.</li>
 * </ol>
 *
 * <p>Degrades safely: if mining finds no anchors, generation fails, or either quality gate rejects
 * the harness, the draft is left unverified (harness stays null) rather than gaining false confidence.
 * The verification gate then permits it on steward approval alone — exactly the pre-verifier
 * behaviour — and the unverified draft is the signal to escalate authoring to a stronger model.</p>
 */
public final class SkillVerificationAuthoring {

    private static final Logger log = LoggerFactory.getLogger(SkillVerificationAuthoring.class);

    private final AnchorMiner miner;
    private final HarnessGenerator generator;
    private final SkillDraftStore store;
    /** Optional quality-gate machinery; null = attach harnesses without self-consistency/teeth checks. */
    private final SkillVerifier verifier;
    private final HarnessMutationGate mutationGate;
    private final ItemWorldApiProvider provider;
    private final ItemCapabilitySet caps;

    /** Back-compat: author + attach harnesses with no quality gating (no verifier available). */
    public SkillVerificationAuthoring(AnchorMiner miner, HarnessGenerator generator,
                                      SkillDraftStore store) {
        this(miner, generator, store, null, null, null);
    }

    /**
     * Full path: mine → generate → self-consistency check → mutation (teeth) check → attach.
     *
     * @param verifier the deterministic runtime verifier (also drives the mutation gate)
     * @param provider world API provider (a capturing stub at authoring time)
     * @param caps     capability set the skill runs under (use a restricted set)
     */
    public SkillVerificationAuthoring(AnchorMiner miner, HarnessGenerator generator,
                                      SkillDraftStore store, SkillVerifier verifier,
                                      ItemWorldApiProvider provider, ItemCapabilitySet caps) {
        this.miner = miner;
        this.generator = generator;
        this.store = store;
        this.verifier = verifier;
        this.mutationGate = verifier != null ? new HarnessMutationGate(verifier) : null;
        this.provider = provider;
        this.caps = caps;
    }

    /**
     * Author (or refresh) the verification harness for one draft and persist it.
     *
     * @return the draft carrying its harness (or the unchanged draft if none could be authored)
     */
    public SkillDraft author(SkillDraft draft) {
        if (draft == null) return null;

        List<VerificationAnchor> anchors = miner.mine(draft.name(), draft.description(), draft.code());
        if (anchors.isEmpty()) {
            log.info("Skill verification: no anchors mined for '{}' — left unverified", draft.name());
            return draft;
        }

        List<String> facts = anchors.stream().map(VerificationAnchor::fact).toList();
        AnchorHarness harness = generator.generate(draft.name(), draft.description(), draft.code(), facts);
        if (harness == null || harness.cases() == null || harness.cases().isEmpty()) {
            log.info("Skill verification: harness generation produced nothing for '{}' — left unverified",
                draft.name());
            return draft;
        }

        if (verifier != null && !passesQualityGates(draft, harness)) {
            return draft; // left unverified; reason already logged → escalate authoring
        }

        SkillDraft authored = draft.withHarness(harness);
        store.upsert(authored);
        log.info("Skill verification: authored harness for '{}' — {} anchors → {} cases (sources: {})",
            draft.name(), anchors.size(), harness.cases().size(),
            anchors.stream().map(a -> a.trustTier() + ":" + sourceLabel(a)).distinct().toList());
        return authored;
    }

    /**
     * Author harnesses for every PENDING draft of an agent that doesn't already carry one.
     * The natural Forge sleep-pass / recipe entry point.
     *
     * @return the number of drafts that gained a harness this pass
     */
    public int authorPendingFor(String agentDid) {
        int authored = 0;
        for (SkillDraft draft : store.byAgentAndStatus(agentDid, SkillDraft.Status.PENDING)) {
            if (draft.verificationHarness() != null) continue; // already verified-authored
            SkillDraft result = author(draft);
            if (result != null && result.verificationHarness() != null) authored++;
        }
        if (authored > 0) {
            log.info("Skill verification: authored {} harness(es) for agent {}", authored, agentDid);
        }
        return authored;
    }

    /**
     * The two pure-code quality gates (self-consistency, then teeth). Returns true only if the
     * harness both accepts its own correct skill AND discriminates wrong versions of it.
     */
    private boolean passesQualityGates(SkillDraft draft, AnchorHarness harness) {
        // 1. Self-consistency: the correct skill must pass its own harness, or an anchor was
        // miscompiled (wrong expected value) and the harness would block the correct skill at the gate.
        SkillVerifier.Verdict self = verifier.verify(draft.draftId(), draft.code(), harness, provider, caps);
        if (!self.passed()) {
            String reason = self.failures().isEmpty() ? "(no detail)" : self.failures().get(0).reason();
            log.warn("Skill verification: harness REJECTS its own skill '{}' ({}/{} cases; {}) — likely a "
                + "miscompiled anchor; left unverified, escalate authoring", draft.name(),
                self.casesPassed(), self.casesRun(), reason);
            return false;
        }

        // 2. Teeth: the harness must catch plausible wrong versions, else it is toothless.
        var teeth = mutationGate.assess(draft.draftId(), draft.code(), harness, provider, caps);
        if (!teeth.hasTeeth()) {
            log.warn("Skill verification: harness for '{}' is TOOTHLESS — 0 of {} mutants caught; "
                + "left unverified, escalate authoring to a stronger model", draft.name(), teeth.mutantsRun());
            return false;
        }
        if (!teeth.assessable()) {
            log.info("Skill verification: harness for '{}' attached but teeth not assessable "
                + "(no mutable arithmetic) — fail-open", draft.name());
        }
        return true;
    }

    private static String sourceLabel(VerificationAnchor a) {
        if (a.source() == null) return "?";
        if (a.source().title() != null) return a.source().title();
        if (a.source().url() != null) return a.source().url();
        return a.source().ref() != null ? a.source().ref() : "?";
    }
}
