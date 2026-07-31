package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.companion.PersonalProjectStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.time.Instant;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 4 — full materialization E2E.
 *
 * <p>{@code craft.script_draft} entries → {@link ScriptDraftConsolidator}
 * proposes a {@link SkillDraft} → bondholder approves via
 * {@link WorkshopPinboard} → skill lands in {@link FamilyLocker} as a
 * permanent soul-item, equippable as a skill.
 */
class ScriptToSkillMaterializationTest {

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private static final String AGENT_DID = "did:wyrd:zA:wyrd";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void approved_script_proposal_lands_in_family_locker(@TempDir Path tmp) throws Exception {
        // ── 1. Seed personal-project store with 4 similar scripts ──
        var projects = new PersonalProjectStore(AGENT_DID, tmp.resolve("agent"));
        var project = projects.create(
            "Code-mode drafts",
            "Improvisational scripts",
            List.of("craft.script_draft", "code-mode"));

        for (int i = 0; i < 4; i++) {
            // Same library_card.search shape — should cluster
            var script = "const primary = library_card.search('mythology');\n"
                + "const result = primary.slice(0, 3);\n"
                + "console.log('summary: ' + result.length);";
            var entry = new LinkedHashMap<String, Object>();
            entry.put("at", Instant.now().toString());
            entry.put("tier", "improvisation");
            entry.put("script", script);
            entry.put("summary", "summarised " + (3 + i) + " sources");
            entry.put("ok", true);
            projects.addEntry(project.id(), MAPPER.writeValueAsString(entry));
        }

        // ── 2. Set up draft store + locker + executor (same as SkillMatE2E) ──
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        var draftStore = new SkillDraftStore(jdbc);
        SkillDraftStore.setInstance(draftStore);

        var bud = SoulBud.original(AGENT_DID, "z6Mk...",
            "family-script-e2e", "locker://script-e2e", "home-server", "qwen2.5:7b");
        var locker = FamilyLocker.create("family-script-e2e", "locker://script-e2e", bud);
        var executor = new WorkbenchSkillExecutor(locker, AGENT_DID);

        // ── 3. Run consolidation pass — proposal lands on pinboard ──
        var proposed = ScriptDraftConsolidator.consolidate(
            AGENT_DID, projects, draftStore, "9b-drive@rev-1");
        assertThat(proposed).hasSize(1);
        var draft = proposed.get(0);
        assertThat(draft.status()).isEqualTo(SkillDraft.Status.PENDING);

        var pinboard = new WorkshopPinboard(draftStore);
        assertThat(pinboard.pending(AGENT_DID)).hasSize(1);

        // ── 4. Bondholder approves via the existing pinboard flow ──
        var materializer = new WorkshopPinboard.DefaultMaterializer(
            locker, executor, AGENT_DID);
        var decision = pinboard.approve(AGENT_DID, 1, "yes, keep this", materializer);
        assertThat(decision.ok())
            .as("the script-derived draft must materialize like any other skill draft")
            .isTrue();

        // ── 5. Final state: skill lives in FamilyLocker ──
        var stored = draftStore.get(draft.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.MATERIALIZED);

        var skills = locker.byCategory("skill", AGENT_DID);
        assertThat(skills)
            .as("skill should land in the family locker as soul-item")
            .extracting("label")
            .contains(draft.name());

        // The skill is equippable as a skill item via the executor.
        assertThat(executor.supports("workbench." + draft.name()))
            .as("script-derived skill must be registered with the executor")
            .isTrue();

        // No more pending drafts.
        assertThat(pinboard.pending(AGENT_DID)).isEmpty();
    }
}
