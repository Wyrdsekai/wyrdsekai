package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.soul.SignificanceBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSleepTrainerTest {

    @Test
    void skips_when_feature_flag_not_set(@TempDir Path tmp) {
        // WYRDSEKAI_VOICE_ALIGN is not set in test env (env vars aren't
        // editable cross-platform from within a JVM test) — by contract,
        // this is SKIPPED_FLAG_OFF unless the runner explicitly enables it.
        var ctl = new DeepSleepTrainer.NoOpInferenceController();
        var trainer = new DeepSleepTrainer(tmp, ctl);
        var buf = new SignificanceBuffer();
        buf.remember("I noticed the rain today", 0.8f);

        var result = trainer.run("did:wyrd:wyrd", "Wyrd", buf);

        // Either flag-off (default) OR no-model-path (if flag is accidentally
        // set in CI env but no model configured). Both are valid pre-flight
        // skips that prove the trainer fails closed rather than crashing.
        assertThat(result.outcome()).isIn(
                DeepSleepTrainer.Outcome.SKIPPED_FLAG_OFF,
                DeepSleepTrainer.Outcome.SKIPPED_NO_MODEL);
        assertThat(result.adapterPath()).isNull();
        assertThat(ctl.pauseCalled).isFalse();
    }

    @Test
    void corpus_builder_shapes_significance_into_chat_turns(@TempDir Path tmp) {
        var trainer = new DeepSleepTrainer(tmp, new DeepSleepTrainer.NoOpInferenceController());
        var buf = new SignificanceBuffer();
        buf.remember("Operator's cat is named Mochi", 0.9f);
        buf.note("The Nexus feels quieter in the evening");
        buf.forget("old value", "superseded");

        var corpus = trainer.buildCorpus(buf, "Wyrd");

        // forget entries are dropped (they're not voice), remember+note kept
        assertThat(corpus).hasSize(2);
        assertThat(corpus.getFirst())
                .containsEntry("system",
                        "You are Wyrd. Speak in your own voice, grounded in what you have chosen to remember.")
                .containsKey("user")
                .containsEntry("assistant", "Operator's cat is named Mochi");
        assertThat(corpus.getLast().get("assistant"))
                .isEqualTo("The Nexus feels quieter in the evening");
    }

    @Test
    void corpus_builder_handles_null_buffer(@TempDir Path tmp) {
        var trainer = new DeepSleepTrainer(tmp, new DeepSleepTrainer.NoOpInferenceController());
        assertThat(trainer.buildCorpus(null, "Wyrd")).isEmpty();
    }

    @Test
    void corpus_builder_skips_blank_content(@TempDir Path tmp) {
        var trainer = new DeepSleepTrainer(tmp, new DeepSleepTrainer.NoOpInferenceController());
        var buf = new SignificanceBuffer();
        buf.remember("", 0.5f);
        buf.remember("   ", 0.5f);
        buf.remember("real content here", 0.5f);

        var corpus = trainer.buildCorpus(buf, "Wyrd");
        assertThat(corpus).hasSize(1);
    }

    // ── Voice-forge hook integration (#415) ──────────────────────────────

    @Test
    void extract_assistant_samples_keeps_tail_and_caps_at_8() {
        var corpus = new ArrayList<Map<String, String>>();
        for (int i = 0; i < 12; i++) {
            corpus.add(Map.of(
                "system", "sys", "user", "u",
                "assistant", "turn-" + i));
        }
        var samples = DeepSleepTrainer.extractAssistantSamples(corpus);
        // Cap at 8, keep the most-recent.
        assertThat(samples).hasSize(8);
        assertThat(samples.getLast()).isEqualTo("turn-11");
        assertThat(samples.getFirst()).isEqualTo("turn-4");
    }

    @Test
    void extract_assistant_samples_skips_blank_assistants() {
        var corpus = List.of(
            Map.of("system", "s", "user", "u", "assistant", "real"),
            Map.of("system", "s", "user", "u", "assistant", ""),
            Map.of("system", "s", "user", "u", "assistant", "   "));
        var samples = DeepSleepTrainer.extractAssistantSamples(corpus);
        assertThat(samples).containsExactly("real");
    }

    @Test
    void extract_assistant_samples_handles_null_and_empty() {
        assertThat(DeepSleepTrainer.extractAssistantSamples(null)).isEmpty();
        assertThat(DeepSleepTrainer.extractAssistantSamples(List.of())).isEmpty();
    }

    @Test
    void constructor_with_forge_hook_does_not_invoke_until_run(@TempDir Path tmp) {
        // Regression guard: constructing a trainer must be side-effect-free.
        // If we accidentally called the hook from the constructor, agents
        // would burn inference on every sleep enqueue, not just on successful
        // alignment. The hook captures a boolean so a spurious invocation
        // would flip it.
        var fired = new boolean[]{false};
        DeepSleepTrainer.VoiceForgeHook hook = samples -> fired[0] = true;
        var trainer = new DeepSleepTrainer(tmp,
            new DeepSleepTrainer.NoOpInferenceController(), hook);
        assertThat(trainer).isNotNull();
        assertThat(fired[0])
            .as("forge hook must only fire inside run(), never at construction")
            .isFalse();
    }

    @Test
    void forge_hook_skipped_when_alignment_bails_early(@TempDir Path tmp) {
        // WYRDSEKAI_VOICE_ALIGN is unset in the test env → run() returns
        // SKIPPED_FLAG_OFF before even reaching the hook's gate. The hook
        // must stay un-invoked — no inference burn for skipped cycles.
        var fired = new boolean[]{false};
        DeepSleepTrainer.VoiceForgeHook hook = samples -> fired[0] = true;
        var trainer = new DeepSleepTrainer(tmp,
            new DeepSleepTrainer.NoOpInferenceController(), hook);
        var buf = new SignificanceBuffer();
        buf.remember("something to say", 0.8f);

        trainer.run("did:wyrd:test", "Wyrd", buf);

        assertThat(fired[0])
            .as("forge hook must not fire when alignment never succeeds")
            .isFalse();
    }

    @Test
    void write_corpus_jsonl_writes_one_line_per_turn(@TempDir Path tmp) throws Exception {
        var out = tmp.resolve("corpus.jsonl");
        var trainer = new DeepSleepTrainer(tmp, new DeepSleepTrainer.NoOpInferenceController());
        var buf = new SignificanceBuffer();
        buf.remember("turn one", 0.5f);
        buf.remember("turn two", 0.5f);
        var corpus = trainer.buildCorpus(buf, "Wyrd");

        DeepSleepTrainer.writeCorpusJsonl(out, corpus);

        var lines = Files.readAllLines(out);
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("turn one").contains("\"assistant\"");
    }
}
