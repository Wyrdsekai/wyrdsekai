package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.SkillUsageTracker;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior contracts for {@link SkillProposer}. Phase 2 of
 * rollout.
 */
class SkillProposerTest {

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private static SkillUsageTracker.CapabilityGap gap(String desc, int n) {
        return new SkillUsageTracker.CapabilityGap(desc, Instant.now(), n);
    }

    private static String mockLlmOutput(String name, String code, String... gaps) {
        var gapJson = new StringBuilder("[");
        for (int i = 0; i < gaps.length; i++) {
            if (i > 0) gapJson.append(",");
            gapJson.append('"').append(gaps[i]).append('"');
        }
        gapJson.append("]");
        return """
            {
              "name": "%s",
              "description": "Compress a directory tree into .tar.gz with progress.",
              "rationale": "Steward asked 5x and the agent had nothing to use.",
              "code": "%s",
              "runtime": "graaljs",
              "closes_gaps": %s,
              "replaces": null
            }
            """.formatted(name, code, gapJson.toString());
    }

    @Test
    void parse_round_trips_well_formed_output() {
        var g = gap("couldn't compress that big folder", 5);
        var out = mockLlmOutput("compress_archive",
            "function execute(params) { return 'ok'; }",
            "couldn't compress that big folder");

        var draft = SkillProposer.parse(out, "did:wyrd:wyrd", g, "9b-skills@rev-12");

        assertThat(draft).isNotNull();
        assertThat(draft.name()).isEqualTo("compress_archive");
        assertThat(draft.runtime()).isEqualTo("graaljs");
        assertThat(draft.status()).isEqualTo(SkillDraft.Status.PENDING);
        assertThat(draft.closesGaps()).contains("couldn't compress that big folder");
        assertThat(draft.proposedByModel()).isEqualTo("9b-skills@rev-12");
    }

    @Test
    void parse_falls_back_to_gap_description_if_closes_gaps_empty() {
        var g = gap("recurring pdf split", 3);
        // closes_gaps array empty in output → proposer fills from gap
        var out = """
            {
              "name": "split_pdf",
              "description": "Split a PDF into pages.",
              "rationale": "Repeated requests.",
              "code": "function execute(params) { return params; }",
              "runtime": "graaljs",
              "closes_gaps": [],
              "replaces": null
            }
            """;
        var draft = SkillProposer.parse(out, "did:wyrd:x", g, "test");
        assertThat(draft).isNotNull();
        assertThat(draft.closesGaps()).containsExactly("recurring pdf split");
    }

    @Test
    void parse_returns_null_for_empty_output() {
        var g = gap("anything", 3);
        assertThat(SkillProposer.parse("", "did:wyrd:x", g, "m")).isNull();
        assertThat(SkillProposer.parse(null, "did:wyrd:x", g, "m")).isNull();
        assertThat(SkillProposer.parse("just prose, no JSON", "did:wyrd:x", g, "m")).isNull();
    }

    @Test
    void parse_extracts_json_from_markdown_fence() {
        var g = gap("schedule recurring backup", 4);
        var out = """
            Sure! Here is the draft:

            ```json
            {
              "name": "schedule_backup",
              "description": "Schedule a recurring tar.gz backup.",
              "rationale": "Recurring request.",
              "code": "function execute(p) { return p; }",
              "runtime": "graaljs",
              "closes_gaps": ["schedule recurring backup"],
              "replaces": null
            }
            ```

            That should do it!
            """;
        var draft = SkillProposer.parse(out, "did:wyrd:x", g, "m");
        assertThat(draft).isNotNull();
        assertThat(draft.name()).isEqualTo("schedule_backup");
    }

    @Test
    void parse_drops_drafts_that_fail_workbench_validation() {
        var g = gap("anything", 3);
        // Bad: no `function execute` in code → WorkbenchValidator rejects it.
        var out = """
            {
              "name": "bad",
              "description": "x",
              "rationale": "x",
              "code": "var x = 1;",
              "runtime": "graaljs",
              "closes_gaps": ["anything"],
              "replaces": null
            }
            """;
        var draft = SkillProposer.parse(out, "did:wyrd:x", g, "m");
        assertThat(draft).isNull();
    }

    @Test
    void parse_drops_drafts_with_missing_name() {
        var g = gap("anything", 3);
        var out = """
            {
              "name": "",
              "description": "x",
              "rationale": "x",
              "code": "function execute(p) { return p; }",
              "runtime": "graaljs",
              "closes_gaps": ["anything"]
            }
            """;
        assertThat(SkillProposer.parse(out, "did:wyrd:x", g, "m")).isNull();
    }

    @Test
    void parse_carries_replaces_field_when_present() {
        var g = gap("better compress", 3);
        var out = """
            {
              "name": "compress_v2",
              "description": "Better compression.",
              "rationale": "Old one fails on huge dirs.",
              "code": "function execute(p) { return p; }",
              "runtime": "graaljs",
              "closes_gaps": ["better compress"],
              "replaces": "compress_archive"
            }
            """;
        var draft = SkillProposer.parse(out, "did:wyrd:x", g, "m");
        assertThat(draft).isNotNull();
        assertThat(draft.replaces()).isEqualTo("compress_archive");
    }

    @Test
    void proposeAndStore_persists_draft_to_store(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        var store = new SkillDraftStore(jdbc);
        var g = gap("compress dir", 3);
        var out = mockLlmOutput("compress_archive",
            "function execute(p) { return p; }", "compress dir");

        var draft = SkillProposer.proposeAndStore(out, "did:wyrd:x", g, "9b-skills", store);
        assertThat(draft).isNotNull();
        var read = store.get(draft.draftId()).orElseThrow();
        assertThat(read.name()).isEqualTo("compress_archive");
        assertThat(read.status()).isEqualTo(SkillDraft.Status.PENDING);
    }

    @Test
    void buildUserPrompt_includes_gap_and_occurrence_count() {
        var g = gap("couldn't summarize long article", 7);
        var prompt = SkillProposer.buildUserPrompt(g, null, null, null);
        assertThat(prompt).contains("couldn't summarize long article");
        assertThat(prompt).contains("7 occurrences");
        assertThat(prompt).contains("Output JSON only");
    }

    @Test
    void buildUserPrompt_includes_recent_failure_context_from_tracker() {
        var tracker = new SkillUsageTracker();
        tracker.record("compress_archive", false, 50, "tar: command not found");
        tracker.record("compress_archive", false, 30, "huge folder timeout");
        var g = gap("couldn't compress", 3);

        var prompt = SkillProposer.buildUserPrompt(g, tracker, null, null);
        assertThat(prompt).contains("Recent failed-skill context");
        assertThat(prompt).contains("tar: command not found");
    }
}
