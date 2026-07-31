package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.companion.PersonalProjectStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 4 — dissimilar drafts must NOT cluster.
 *
 * <p>If 4 drafts have nothing in common (different identifiers, different
 * structure), the consolidator must produce zero {@link SkillDraft}
 * proposals. False clustering would surface noise on the workshop pinboard
 * and erode steward trust.
 */
class ForgeScriptDraftDissimilarTest {

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private static final String AGENT_DID = "did:wyrd:zA:wyrd";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void four_dissimilar_drafts_produce_no_proposal(@TempDir Path tmp) throws Exception {
        var store = new PersonalProjectStore(AGENT_DID, tmp.resolve("agent"));
        var project = store.create(
            "Code-mode drafts",
            "Improvisational scripts",
            List.of("craft.script_draft", "code-mode"));

        // Four wildly different scripts: each uses different identifiers,
        // different verbs, different surface — nothing should cluster.
        var scripts = List.of(
            "const weather = oracle_lens.forecast('rain', 24);\n"
                + "console.log('forecast: ' + weather.summary);",

            "const note = quill.write('a memo to myself about the willow tree');\n"
                + "console.log('wrote: ' + note.id);",

            "const status = sending_stone.send('alice', 'hello');\n"
                + "console.log('sent to alice: ' + status.ok);",

            "const inv = world.listInventory();\n"
                + "console.log('inventory: ' + inv.length + ' items');"
        );

        for (var script : scripts) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("at", Instant.now().toString());
            entry.put("tier", "improvisation");
            entry.put("ok", true);
            entry.put("summary", "did a thing");
            entry.put("script", script);
            store.addEntry(project.id(), MAPPER.writeValueAsString(entry));
        }

        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        var draftStore = new SkillDraftStore(jdbc);
        SkillDraftStore.setInstance(draftStore);

        var proposed = ScriptDraftConsolidator.consolidate(
            AGENT_DID, store, draftStore, "test-model");

        assertThat(proposed)
            .as("4 dissimilar scripts should never cluster into a skill")
            .isEmpty();

        var pinboard = new WorkshopPinboard(draftStore);
        assertThat(pinboard.pending(AGENT_DID)).isEmpty();
    }

    @Test
    void mixed_batch_only_clusters_the_recurring_subset(@TempDir Path tmp) throws Exception {
        // 3 similar (will cluster) + 2 unique (won't) → exactly 1 proposal
        var store = new PersonalProjectStore(AGENT_DID, tmp.resolve("agent"));
        var project = store.create("d", "x", List.of("craft.script_draft"));

        // 3 similar — search-and-summarise pattern
        for (int i = 0; i < 3; i++) {
            var script = "const r = library_card.search('topic" + i + "');\n"
                + "console.log(r.length);";
            addEntry(store, project.id(), script, "found " + i);
        }
        // 2 unrelated
        addEntry(store, project.id(),
            "const w = oracle_lens.forecast('storm', 12);\nconsole.log(w);",
            "weather check");
        addEntry(store, project.id(),
            "sending_stone.send('bob', 'are you home?');",
            "asked bob");

        var jdbc = SchemaInitializer.initialize(tmp.resolve("d.db"));
        var draftStore = new SkillDraftStore(jdbc);
        var proposed = ScriptDraftConsolidator.consolidate(
            AGENT_DID, store, draftStore, "test");

        assertThat(proposed)
            .as("only the recurring subset should propose; the unique drafts stay drafts")
            .hasSize(1);
    }

    private static void addEntry(PersonalProjectStore store, String projectId,
                                  String script, String summary) throws Exception {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("at", Instant.now().toString());
        entry.put("script", script);
        entry.put("summary", summary);
        entry.put("ok", true);
        store.addEntry(projectId, MAPPER.writeValueAsString(entry));
    }
}
