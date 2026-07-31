package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.soul.BehavioralFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — training-corpus generator output format and
 * CfC drive-delta extraction.
 */
class TrainingCorpusGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── sample construction ────────────────────────────────────────────────

    @Test
    void sample_has_three_messages_in_correct_order() {
        var sample = TrainingCorpusGenerator.buildSample(
            "I made a `researcher` form that works.",
            TrainingCorpusGenerator.DriveSnapshot.neutral());
        var msgs = sample.get("messages");
        assertEquals(3, msgs.size());
        assertEquals("system", msgs.get(0).get("role").asText());
        assertEquals("user", msgs.get(1).get("role").asText());
        assertEquals("assistant", msgs.get(2).get("role").asText());
        assertEquals("familiar-forge", sample.get("source").asText());
    }

    @Test
    void system_prompt_contains_all_known_drives() {
        var prompt = TrainingCorpusGenerator.buildSystemPrompt(
            TrainingCorpusGenerator.DriveSnapshot.neutral());
        for (var drive : TrainingCorpusGenerator.KNOWN_DRIVES) {
            assertTrue(prompt.contains(drive + "="),
                "system prompt must tag drive: " + drive);
        }
        assertTrue(prompt.contains("energy=0.7"));
    }

    @Test
    void user_turn_picks_plausible_prompt_by_keyword() {
        assertEquals("What have you been working on?",
            TrainingCorpusGenerator.synthesizeUserTurn("sent a bunshin to focus"));
        assertEquals("Tell me about the forms you've shaped.",
            TrainingCorpusGenerator.synthesizeUserTurn("I made a researcher form"));
        assertEquals("Who's been helping you lately?",
            TrainingCorpusGenerator.synthesizeUserTurn("My named familiar gardener"));
    }

    // ── LoRA jsonl writer ──────────────────────────────────────────────────

    @Test
    void writes_one_jsonl_line_per_corpus_entry(@TempDir Path dir) throws IOException {
        var result = new FamiliarForgeIngester.Result(
            List.of(), BehavioralFingerprint.empty(),
            List.of(
                "I made a `researcher` form. She works 90% of the time.",
                "I sent a bunshin to focus on documentation."));
        var out = dir.resolve("corpus.jsonl");
        var written = TrainingCorpusGenerator.writeLoraCorpus(
            result, TrainingCorpusGenerator.DriveSnapshot.neutral(), out);

        assertEquals(2, written.lineCount());
        var lines = Files.readAllLines(out);
        assertEquals(2, lines.size());
        for (var line : lines) {
            var parsed = MAPPER.readTree(line);
            assertTrue(parsed.has("messages"));
            assertEquals(3, parsed.get("messages").size());
            assertEquals("familiar-forge", parsed.get("source").asText());
        }
    }

    @Test
    void empty_result_writes_nothing(@TempDir Path dir) throws IOException {
        var empty = new FamiliarForgeIngester.Result(
            List.of(), BehavioralFingerprint.empty(), List.of());
        var out = dir.resolve("empty.jsonl");
        var written = TrainingCorpusGenerator.writeLoraCorpus(
            empty, TrainingCorpusGenerator.DriveSnapshot.neutral(), out);
        assertEquals(0, written.lineCount());
        assertFalse(Files.exists(out));
    }

    @Test
    void writer_appends_rather_than_overwrites(@TempDir Path dir) throws IOException {
        var out = dir.resolve("growing.jsonl");
        var r1 = new FamiliarForgeIngester.Result(
            List.of(), BehavioralFingerprint.empty(), List.of("first"));
        var r2 = new FamiliarForgeIngester.Result(
            List.of(), BehavioralFingerprint.empty(), List.of("second", "third"));
        TrainingCorpusGenerator.writeLoraCorpus(r1,
            TrainingCorpusGenerator.DriveSnapshot.neutral(), out);
        TrainingCorpusGenerator.writeLoraCorpus(r2,
            TrainingCorpusGenerator.DriveSnapshot.neutral(), out);
        assertEquals(3, Files.readAllLines(out).size());
    }

    // ── CfC drive-delta extraction ─────────────────────────────────────────

    @Test
    void no_activity_produces_no_deltas() {
        var deltas = TrainingCorpusGenerator.cfcDriveDeltas(BehavioralFingerprint.empty());
        assertTrue(deltas.isEmpty());
    }

    @Test
    void bunshin_activity_bumps_seeking_and_creativity() {
        var fp = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(),
            Map.of("dispatch_bunshin", 3.0f),
            Map.of(), Map.of(), 0f, 0f, List.of(), Map.of());
        var deltas = TrainingCorpusGenerator.cfcDriveDeltas(fp);
        assertTrue(deltas.get("seeking") > 0);
        assertTrue(deltas.get("creativity") > 0);
    }

    @Test
    void named_companion_affinity_bumps_affiliation_and_care() {
        var fp = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of("named-companions", 0.6f),
            Map.of(), 0f, 0f, List.of(), Map.of());
        var deltas = TrainingCorpusGenerator.cfcDriveDeltas(fp);
        assertTrue(deltas.get("affiliation") > 0);
        assertTrue(deltas.get("care") > 0);
    }

    @Test
    void bunshin_delta_is_capped_at_10_dispatches() {
        var heavy = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(),
            Map.of("dispatch_bunshin", 100.0f),
            Map.of(), Map.of(), 0f, 0f, List.of(), Map.of());
        var deltas = TrainingCorpusGenerator.cfcDriveDeltas(heavy);
        // Cap is min(count, 10) * 0.05 = 0.5
        assertEquals(0.5f, deltas.get("seeking"), 1e-4);
    }

    // ── CfC manifest writer ────────────────────────────────────────────────

    @Test
    void cfc_manifest_appends_drive_deltas_as_jsonl(@TempDir Path dir) throws IOException {
        var fp = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(),
            Map.of("dispatch_bunshin", 2.0f, "shape_form", 1.0f),
            Map.of(), Map.of(), 0f, 0f, List.of(), Map.of());
        var result = new FamiliarForgeIngester.Result(
            List.of(), fp, List.of("entry"));
        var out = dir.resolve("cfc.jsonl");

        TrainingCorpusGenerator.writeCfcManifest(result, out,
            "did:wyrd:zA:wyrd", Instant.parse("2026-04-21T12:00:00Z"));

        var line = Files.readAllLines(out).get(0);
        var parsed = MAPPER.readTree(line);
        assertEquals("did:wyrd:zA:wyrd", parsed.get("agentDid").asText());
        var driveDeltas = parsed.get("driveDeltas");
        assertTrue(driveDeltas.get("seeking").asDouble() > 0);
        assertTrue(driveDeltas.get("creativity").asDouble() > 0);
        assertEquals(1, parsed.get("corpusLineCount").asInt());
    }

    // ── end-to-end with real ingester output ───────────────────────────────

    @Test
    void end_to_end_from_ingester_to_jsonl(@TempDir Path dir) throws IOException {
        var form = ThoughtForm.author("did:wyrd:zA:wyrd", "researcher",
            "Research and cite sources.", Set.of(), "Three sources.");
        for (int i = 0; i < 10; i++) form = form.incrementSummon();
        for (int i = 0; i < 9; i++) form = form.recordSuccess();
        form = form.recordFailure();

        var batch = new FamiliarForgeIngester.Batch(
            "did:wyrd:zA:wyrd", List.of(form), List.of(), List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        var out = dir.resolve("e2e.jsonl");

        TrainingCorpusGenerator.writeLoraCorpus(result,
            TrainingCorpusGenerator.DriveSnapshot.neutral(), out);
        var lines = Files.readAllLines(out);
        assertFalse(lines.isEmpty());
        var parsed = MAPPER.readTree(lines.get(0));
        assertEquals(3, parsed.get("messages").size());
        // Assistant turn should mention the form
        var assistantText = parsed.get("messages").get(2).get("content").asText();
        assertTrue(assistantText.contains("researcher"));
    }
}
