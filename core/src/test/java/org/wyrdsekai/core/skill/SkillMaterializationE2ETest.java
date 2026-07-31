package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.SkillUsageTracker;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.time.Instant;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * full lifecycle:
 * gap recorded → threshold hit → proposer drafts → steward approves →
 * soul-item lands in FamilyLocker as a registered skill.
 *
 * <p>The proposer LLM call is mocked via a string fixture; everything
 * else is real (SkillDraftStore, WorkshopPinboard, SkillItemCodec,
 * WorkbenchSkillExecutor, FamilyLocker).</p>
 */
class SkillMaterializationE2ETest {

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private static final String AUTHOR_DID = "did:wyrd:zA:wyrd";

    private static String mockProposerOutput() {
        return """
            {
              "name": "compress_archive",
              "description": "Compress a directory tree into .tar.gz with progress reporting.",
              "rationale": "Steward asked 5 times in two weeks; agent had no skill to call.",
              "code": "function execute(params) { return { ok: true, path: params.path }; }",
              "runtime": "graaljs",
              "closes_gaps": ["couldn't compress folder"],
              "replaces": null
            }
            """;
    }

    @Test
    void full_loop_gap_to_propose_to_approve_to_materialize(@TempDir Path tmp) {
        // ── 1. Setup: tracker + draft store + locker + executor ──
        var jdbc = SchemaInitializer.initialize(tmp.resolve("e2e.db"));
        var store = new SkillDraftStore(jdbc);
        SkillDraftStore.setInstance(store);

        var tracker = new SkillUsageTracker();
        var bud = SoulBud.original(AUTHOR_DID, "z6Mk...",
            "family-e2e", "locker://e2e", "home-server", "qwen2.5:7b");
        var locker = FamilyLocker.create("family-e2e", "locker://e2e", bud);
        var executor = new WorkbenchSkillExecutor(locker, AUTHOR_DID);

        // ── 2. Gap detection: 3 failures pile up, threshold hit ──
        var gapDescription = "couldn't compress folder";
        for (int i = 0; i < 3; i++) tracker.recordGap(gapDescription);

        assertThat(tracker.shouldTriggerAssessment())
            .as("threshold of 3 should trigger")
            .isTrue();
        var triggered = tracker.triggeredGaps();
        assertThat(triggered).hasSize(1);
        var gap = triggered.get(0);
        assertThat(gap.occurrences()).isEqualTo(3);

        // ── 3. SkillProposer: draft a skill via the mocked LLM output ──
        var draft = SkillProposer.proposeAndStore(
            mockProposerOutput(), AUTHOR_DID, gap, "9b-skills@rev-12", store);
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo(SkillDraft.Status.PENDING);
        assertThat(draft.name()).isEqualTo("compress_archive");

        // Drafts are visible on the Workshop pinboard and via REST.
        var pinboard = new WorkshopPinboard(store);
        var pending = pinboard.pending(AUTHOR_DID);
        assertThat(pending).hasSize(1);
        var look = pinboard.renderLook(AUTHOR_DID, "Wyrd");
        assertThat(look).contains("compress_archive");
        assertThat(look).contains("1 pending draft");

        // ── 4. Steward approves → DefaultMaterializer seats the skill ──
        var materializer = new WorkshopPinboard.DefaultMaterializer(
            locker, executor, AUTHOR_DID);
        var decision = pinboard.approve(AUTHOR_DID, 1, "ship it", materializer);
        assertThat(decision.ok()).isTrue();

        // ── 5. Final state ──
        var stored = store.get(draft.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.MATERIALIZED);

        // Skill is in FamilyLocker as a soul-item with category "skill".
        var skills = locker.byCategory("skill", AUTHOR_DID);
        assertThat(skills).extracting("label").contains("compress_archive");

        // Skill is registered with the executor and can be looked up.
        assertThat(executor.supports("workbench.compress_archive")).isTrue();

        // No more pending drafts.
        assertThat(pinboard.pending(AUTHOR_DID)).isEmpty();
    }

    @Test
    void rejection_persists_reason_and_keeps_skill_out_of_locker(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("e2e-rej.db"));
        var store = new SkillDraftStore(jdbc);
        SkillDraftStore.setInstance(store);

        var bud = SoulBud.original(AUTHOR_DID, "z6Mk...",
            "family-e2e", "locker://e2e", "home-server", "qwen2.5:7b");
        var locker = FamilyLocker.create("family-e2e", "locker://e2e", bud);
        var executor = new WorkbenchSkillExecutor(locker, AUTHOR_DID);

        var gap = new SkillUsageTracker.CapabilityGap(
            "do something risky", Instant.now(), 3);
        var draft = SkillProposer.proposeAndStore(
            mockProposerOutput(), AUTHOR_DID, gap, "test", store);
        assertThat(draft).isNotNull();

        var pinboard = new WorkshopPinboard(store);
        var decision = pinboard.reject(AUTHOR_DID, 1, "uses unsafe network calls");
        assertThat(decision.ok()).isTrue();

        var stored = store.get(draft.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.REJECTED);
        assertThat(stored.decisionNote()).contains("unsafe");

        // Skill must NOT be in the locker.
        assertThat(locker.byCategory("skill", AUTHOR_DID)).isEmpty();
        assertThat(executor.supports("workbench.compress_archive")).isFalse();
    }

    @Test
    void edit_path_supersedes_prior_and_materializes_new_code(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("e2e-edit.db"));
        var store = new SkillDraftStore(jdbc);
        SkillDraftStore.setInstance(store);

        var bud = SoulBud.original(AUTHOR_DID, "z6Mk...",
            "family-e2e", "locker://e2e", "home-server", "qwen2.5:7b");
        var locker = FamilyLocker.create("family-e2e", "locker://e2e", bud);
        var executor = new WorkbenchSkillExecutor(locker, AUTHOR_DID);

        var gap = new SkillUsageTracker.CapabilityGap(
            "compress folder", Instant.now(), 3);
        var draft = SkillProposer.proposeAndStore(
            mockProposerOutput(), AUTHOR_DID, gap, "test", store);
        assertThat(draft).isNotNull();

        var pinboard = new WorkshopPinboard(store);
        var materializer = new WorkshopPinboard.DefaultMaterializer(
            locker, executor, AUTHOR_DID);

        var decision = pinboard.editAndApprove(AUTHOR_DID, 1,
            "function execute(params) { return { ok: true, edited: true }; }",
            "tightened error handling",
            materializer);
        assertThat(decision.ok()).isTrue();

        // Prior is SUPERSEDED.
        var stored = store.get(draft.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.SUPERSEDED);

        // The materialized skill in the locker holds the edited code.
        var skills = locker.byCategory("skill", AUTHOR_DID);
        assertThat(skills).hasSize(1);
        var def = SkillItemCodec.decode(skills.get(0));
        assertThat(def.code()).contains("edited: true");
    }
}
