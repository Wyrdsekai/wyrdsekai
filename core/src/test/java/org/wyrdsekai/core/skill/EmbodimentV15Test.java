package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.SkillUsageTracker;
import org.wyrdsekai.core.familiar.DynamicFormValidator;
import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;
import org.wyrdsekai.scripting.api.ItemManifestValidator;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.5 — lockdown tests for the three places agents
 * create world content: shape-time validator (DynamicFormValidator),
 * SkillProposer drafts, pinboard rendering, and the i18n surface.
 * CodingTaskItemBridge is exercised via its own existing test in
 * {@code CodingTaskItemBridgeTest} — the v1.5 pre-check shares the
 * scripting-side gate.
 */
class EmbodimentV15Test {

    @AfterEach
    void cleanup() {
        SkillDraftStore.resetForTests();
    }

    private static SkillUsageTracker.CapabilityGap gap(String desc, int n) {
        return new SkillUsageTracker.CapabilityGap(desc, Instant.now(), n);
    }

    // ── D2: DynamicFormValidator REJECT on missing/invalid embodiment ───────

    @Test
    void shapeFormValidatorRejectsManifestWithoutEmbodiment() {
        var script = """
            exports.manifest = {
              name: "agent_authored_no_embodiment",
              version: "1.0.0",
              description: "Missing the §18 embodiment block.",
              capabilities: []
            };
            function invoke(params) { return { ok: true }; }
            exports.invoke = invoke;
            """;
        assertThatThrownBy(() -> DynamicFormValidator.requireEmbodiment(
                script, "agent_authored_no_embodiment"))
            .isInstanceOf(ItemManifestValidator.ManifestEmbodimentMissingException.class)
            .hasMessageContaining("agent_authored_no_embodiment")
            // The message must be actionable on its own — it names what is
            // missing and what to declare, without deferring to a document.
            .hasMessageContaining("must be a declared choice");
    }

    @Test
    void shapeFormValidatorRejectsStructurallyInvalidEmbodiment() {
        // silent: true but no reason → invalid per §18
        var script = """
            exports.manifest = {
              name: "agent_authored_bad_silent",
              version: "1.0.0",
              capabilities: [],
              embodiment: { silent: true }
            };
            function invoke(params) { return { ok: true }; }
            exports.invoke = invoke;
            """;
        assertThatThrownBy(() -> DynamicFormValidator.requireEmbodiment(
                script, "agent_authored_bad_silent"))
            .isInstanceOf(ItemManifestValidator.ManifestEmbodimentMissingException.class)
            .hasMessageContaining("structurally invalid");
    }

    @Test
    void shapeFormValidatorAcceptsValidSilentEmbodiment() {
        var script = """
            exports.manifest = {
              name: "agent_authored_silent_ok",
              version: "1.0.0",
              capabilities: [],
              embodiment: { silent: true, reason: "private notebook tool" }
            };
            function invoke(params) { return { ok: true }; }
            exports.invoke = invoke;
            """;
        var spec = DynamicFormValidator.requireEmbodiment(script, "agent_authored_silent_ok");
        assertThat(spec).isNotNull();
        assertThat(spec.silent()).isTrue();
        assertThat(spec.reason()).isEqualTo("private notebook tool");
        assertThat(spec.isValid()).isTrue();
    }

    @Test
    void shapeFormValidatorAcceptsValidEmittingEmbodiment() {
        var script = """
            exports.manifest = {
              name: "agent_authored_emits_ok",
              version: "1.0.0",
              capabilities: [],
              embodiment: {
                silent: false,
                emits: ["body_language"],
                descriptor_template: "{actor} flips through the deck"
              }
            };
            function invoke(params) { return { ok: true }; }
            exports.invoke = invoke;
            """;
        var spec = DynamicFormValidator.requireEmbodiment(script, "agent_authored_emits_ok");
        assertThat(spec).isNotNull();
        assertThat(spec.silent()).isFalse();
        assertThat(spec.emits()).containsExactly("body_language");
        assertThat(spec.descriptorTemplate()).isEqualTo("{actor} flips through the deck");
        assertThat(spec.isValid()).isTrue();
    }

    @Test
    void denialFromCarriesStructuredKey() {
        var missingEx = new ItemManifestValidator.ManifestEmbodimentMissingException(
            "item 'foo' is missing the required `embodiment` block...");
        var d1 = DynamicFormValidator.denialFrom(missingEx, "foo");
        assertThat(d1.messageKey()).isEqualTo(DynamicFormValidator.EmbodimentDenial.KEY_MISSING);
        assertThat(d1.itemName()).isEqualTo("foo");
        assertThat(d1.detail()).contains("embodiment");

        var invalidEx = new ItemManifestValidator.ManifestEmbodimentMissingException(
            "item 'bar' declares an embodiment block but it's structurally invalid: ...");
        var d2 = DynamicFormValidator.denialFrom(invalidEx, "bar");
        assertThat(d2.messageKey()).isEqualTo(DynamicFormValidator.EmbodimentDenial.KEY_INVALID);
    }

    // ── D3: SkillProposer emits v1-shim or parses explicit embodiment ────────

    @Test
    void skillProposerEmitsV1ShimWhenLlmOmitsEmbodiment() {
        var g = gap("recurring image-resize gap", 4);
        var llmOutput = """
            {
              "name": "resize_image",
              "description": "Resize an image to given pixel bounds.",
              "rationale": "Repeated steward asks.",
              "code": "function execute(params) { return { resized: true }; }",
              "runtime": "graaljs",
              "closes_gaps": ["recurring image-resize gap"],
              "replaces": null
            }
            """;
        var draft = SkillProposer.parse(llmOutput, "did:wyrd:test", g, "test-model");
        assertThat(draft).isNotNull();
        assertThat(draft.embodiment()).isNotNull();
        assertThat(draft.carriesEmbodimentShim()).isTrue();
        assertThat(draft.embodiment().silent()).isTrue();
        assertThat(draft.embodiment().reason()).isEqualTo(SkillProposer.V1_DRAFT_REASON);
    }

    @Test
    void skillProposerHonorsExplicitSilentEmbodiment() {
        var g = gap("private cache gap", 3);
        var llmOutput = """
            {
              "name": "private_cache",
              "description": "A private memoization cache.",
              "rationale": "Recurring computation.",
              "code": "function execute(params) { return { ok: true }; }",
              "runtime": "graaljs",
              "closes_gaps": ["private cache gap"],
              "replaces": null,
              "embodiment": { "silent": true, "reason": "internal cache, no body trace" }
            }
            """;
        var draft = SkillProposer.parse(llmOutput, "did:wyrd:test", g, "test-model");
        assertThat(draft).isNotNull();
        assertThat(draft.carriesEmbodimentShim()).isFalse();
        assertThat(draft.embodiment().silent()).isTrue();
        assertThat(draft.embodiment().reason()).isEqualTo("internal cache, no body trace");
    }

    @Test
    void skillProposerHonorsExplicitEmittingEmbodiment() {
        var g = gap("research-trace gap", 5);
        var llmOutput = """
            {
              "name": "trace_research",
              "description": "Log a research breadcrumb.",
              "rationale": "Recurring need.",
              "code": "function execute(params) { return { logged: true }; }",
              "runtime": "graaljs",
              "closes_gaps": ["research-trace gap"],
              "replaces": null,
              "embodiment": {
                "silent": false,
                "emits": ["body_language", "ambient_shift"],
                "descriptor_template": "{actor} marks a careful breadcrumb"
              }
            }
            """;
        var draft = SkillProposer.parse(llmOutput, "did:wyrd:test", g, "test-model");
        assertThat(draft).isNotNull();
        assertThat(draft.carriesEmbodimentShim()).isFalse();
        assertThat(draft.embodiment().silent()).isFalse();
        assertThat(draft.embodiment().emits()).contains("body_language", "ambient_shift");
        assertThat(draft.embodiment().descriptorTemplate())
            .isEqualTo("{actor} marks a careful breadcrumb");
    }

    @Test
    void skillProposerSilentWithoutReasonFallsBackToShim() {
        var g = gap("malformed silent gap", 3);
        var llmOutput = """
            {
              "name": "malformed_silent",
              "description": "x",
              "rationale": "x",
              "code": "function execute(params) { return {}; }",
              "runtime": "graaljs",
              "closes_gaps": ["malformed silent gap"],
              "replaces": null,
              "embodiment": { "silent": true }
            }
            """;
        var draft = SkillProposer.parse(llmOutput, "did:wyrd:test", g, "test-model");
        assertThat(draft).isNotNull();
        assertThat(draft.carriesEmbodimentShim()).isTrue();
    }

    @Test
    void skillProposerSystemPromptMentionsEmbodimentRequirement() {
        var prompt = SkillProposer.buildSystemPrompt();
        assertThat(prompt).contains("Embodiment is REQUIRED");
        assertThat(prompt).contains("\"silent\": true");
        assertThat(prompt).contains("\"emits\"");
        assertThat(prompt).contains("descriptor_template");
    }

    // ── D4: Workbench pinboard surfaces embodiment first-class ──────────────

    @Test
    void pinboardRenderSummaryFlagsV1Shim() {
        var draft = SkillDraft.pending(
            "draft-1", "did:wyrd:test",
            "shim_skill", "A skill with default shim.",
            "rationale",
            "function execute(params) { return {}; }",
            "graaljs",
            List.of("gap"), null,
            "test-model");
        var summary = WorkshopPinboard.renderEmbodimentSummary(draft);
        assertThat(summary).contains("v1-draft shim");
        assertThat(summary).contains("replace before materialize");
    }

    @Test
    void pinboardRenderSummaryFormatsSilentDraft() {
        var draft = SkillDraft.pending(
            "draft-2", "did:wyrd:test",
            "silent_skill", "Silent.", "rationale",
            "function execute(params) { return {}; }", "graaljs",
            List.of("gap"), null,
            "test-model",
            ItemEmbodimentSpec.silent("internal accumulator"));
        var summary = WorkshopPinboard.renderEmbodimentSummary(draft);
        assertThat(summary).contains("silent");
        assertThat(summary).contains("internal accumulator");
        assertThat(summary).doesNotContain("v1-draft shim");
    }

    @Test
    void pinboardRenderSummaryFormatsEmittingDraft() {
        var draft = SkillDraft.pending(
            "draft-3", "did:wyrd:test",
            "emits_skill", "Emits.", "rationale",
            "function execute(params) { return {}; }", "graaljs",
            List.of("gap"), null,
            "test-model",
            ItemEmbodimentSpec.emits(
                List.of("body_language"),
                "{actor} reaches for the deck"));
        var summary = WorkshopPinboard.renderEmbodimentSummary(draft);
        assertThat(summary).contains("emits");
        assertThat(summary).contains("body_language");
        assertThat(summary).contains("{actor} reaches for the deck");
    }

    // ── D6: i18n keys present in all three langs ────────────────────────────

    @Test
    void i18nDenialKeysExistInAllThreeLangs() {
        var requiredKeys = List.of(
            "embodiment.reject_missing",
            "embodiment.reject_invalid",
            "embodiment.reject_workbench",
            "embodiment.reject_coding_bridge",
            "workbench.embodiment.silent",
            "workbench.embodiment.shim",
            "workbench.embodiment.emits",
            "workbench.embodiment.emits_no_template",
            "workbench.embodiment.none",
            "workbench.embodiment.label"
        );
        for (var lang : List.of("en", "es", "ja")) {
            var catalog = ScriptMessageCatalog.forLang(lang);
            for (var key : requiredKeys) {
                assertThat(catalog.hasKey(key))
                    .as("locale %s missing §18 v1.5 key: %s", lang, key)
                    .isTrue();
                var value = catalog.get(key);
                assertThat(value)
                    .as("locale %s key %s must resolve to non-blank value", lang, key)
                    .isNotBlank();
                assertThat(value)
                    .as("locale %s key %s must not echo the key itself (lookup failed)", lang, key)
                    .isNotEqualTo(key);
            }
        }
    }

    @Test
    void i18nMissingKeyRendersWithItemName() {
        var catalog = ScriptMessageCatalog.forLang("en");
        var rendered = catalog.get("embodiment.reject_missing", "my_item");
        assertThat(rendered).contains("my_item");
        // Assert the part an author can act on. The rendered message carries no
        // document reference — it has to stand on its own wording.
        assertThat(rendered).contains("embodiment");
    }

    @Test
    void skillDraftDefaultEmbodimentShimMatchesV1Reason() {
        var shim = SkillDraft.defaultEmbodimentShim();
        assertThat(shim).isNotNull();
        assertThat(shim.silent()).isTrue();
        assertThat(shim.reason()).isEqualTo(SkillProposer.V1_DRAFT_REASON);
        assertThat(shim.isValid()).isTrue();
    }

    @Test
    void skillDraftBackwardCompat14ArgConstructorPopulatesShim() {
        // Old call sites that don't pass embodiment get the v1 shim, not null.
        var draft = new SkillDraft(
            "draft-x", "did:wyrd:test", SkillDraft.Status.PENDING,
            "old_skill", "Legacy.", "rationale",
            "function execute() {}", "graaljs",
            List.of(), null,
            Instant.now(), "model",
            null, null);
        assertThat(draft.embodiment()).isNotNull();
        assertThat(draft.carriesEmbodimentShim()).isTrue();
    }
}
