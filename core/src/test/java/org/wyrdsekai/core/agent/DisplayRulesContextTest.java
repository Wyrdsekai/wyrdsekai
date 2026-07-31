package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies the cultural display-rules
 * resolution table. Pure unit test: no DB, no actor, no PromptAssembler —
 * only the language × culture-override → optional prompt block mapping.
 */
class DisplayRulesContextTest {

    // ── language-derived path ──────────────────────────────────────

    @Test void ja_jp_no_override_emits_ja_block() {
        var block = DisplayRulesContext.forBondholder("ja-JP", null);
        assertThat(block).isPresent();
        assertThat(block.get())
            .contains("Japanese-context")
            .contains("Honne/tatemae")
            .contains("〜のですが、ちょっと〜");
    }

    @Test void en_us_no_override_returns_empty() {
        assertThat(DisplayRulesContext.forBondholder("en-US", null)).isEmpty();
    }

    @Test void fr_fr_no_override_returns_empty() {
        // French is outside the Phase-1A set — silent default Anglo-register.
        assertThat(DisplayRulesContext.forBondholder("fr-FR", null)).isEmpty();
    }

    @Test void de_de_no_override_returns_empty() {
        assertThat(DisplayRulesContext.forBondholder("de-DE", null)).isEmpty();
    }

    @Test void es_mx_no_override_emits_es_block() {
        var block = DisplayRulesContext.forBondholder("es-MX", null);
        assertThat(block).isPresent();
        assertThat(block.get())
            .contains("Spanish")
            .contains("amorcito")
            .contains("tú/usted");
    }

    @Test void es_ar_no_override_emits_es_block() {
        var block = DisplayRulesContext.forBondholder("es-AR", null);
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Spanish");
    }

    @Test void bare_es_no_override_emits_es_block() {
        var block = DisplayRulesContext.forBondholder("es", null);
        assertThat(block).isPresent();
    }

    @Test void pt_br_no_override_emits_pt_block() {
        var block = DisplayRulesContext.forBondholder("pt-BR", null);
        assertThat(block).isPresent();
        assertThat(block.get())
            .contains("Portuguese")
            .contains("Saudade-aware");
    }

    @Test void pt_pt_no_override_emits_pt_block() {
        var block = DisplayRulesContext.forBondholder("pt-PT", null);
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Portuguese");
    }

    @Test void ko_kr_no_override_emits_ko_block() {
        var block = DisplayRulesContext.forBondholder("ko-KR", null);
        assertThat(block).isPresent();
        assertThat(block.get())
            .contains("Korean-context")
            .contains("해요체/해체");
    }

    @Test void zh_cn_no_override_emits_zh_block() {
        var block = DisplayRulesContext.forBondholder("zh-CN", null);
        assertThat(block).isPresent();
        assertThat(block.get())
            .contains("Chinese-context")
            .contains("face-preservation");
    }

    @Test void zh_tw_no_override_emits_zh_block() {
        var block = DisplayRulesContext.forBondholder("zh-TW", null);
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Chinese-context");
    }

    @Test void null_language_returns_empty() {
        assertThat(DisplayRulesContext.forBondholder(null, null)).isEmpty();
    }

    @Test void blank_language_returns_empty() {
        assertThat(DisplayRulesContext.forBondholder("   ", null)).isEmpty();
    }

    // ── override path ──────────────────────────────────────────────

    @Test void ja_jp_with_anglo_override_returns_empty() {
        // The kikokushijo case — Japanese language tag but the bondholder
        // explicitly prefers Anglo register (e.g. operator).
        var block = DisplayRulesContext.forBondholder("ja-JP", "anglo");
        assertThat(block).isEmpty();
    }

    @Test void en_us_with_japanese_formal_override_emits_ja_block() {
        // Reverse case — bondholder's account is en-US but they prefer JA register.
        var block = DisplayRulesContext.forBondholder("en-US", "japanese-formal");
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Japanese-context");
    }

    @Test void en_us_with_japanese_casual_override_emits_ja_block() {
        var block = DisplayRulesContext.forBondholder("en-US", "japanese-casual");
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Japanese-context");
    }

    @Test void en_us_with_latin_warm_override_emits_es_block() {
        var block = DisplayRulesContext.forBondholder("en-US", "latin-warm");
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Spanish");
    }

    @Test void en_us_with_korean_override_emits_ko_block() {
        var block = DisplayRulesContext.forBondholder("en-US", "korean-formal");
        assertThat(block).isPresent();
        assertThat(block.get()).contains("Korean-context");
    }

    @Test void unknown_override_returns_empty() {
        // Defensive: a junk override shouldn't accidentally fall through to
        // language-derived guidance — it's an explicit user choice that
        // we don't recognise, so be safe and emit nothing.
        var block = DisplayRulesContext.forBondholder("ja-JP", "klingon-direct");
        assertThat(block).isEmpty();
    }

    @Test void override_is_case_insensitive() {
        assertThat(DisplayRulesContext.forBondholder("ja-JP", "ANGLO")).isEmpty();
        assertThat(DisplayRulesContext.forBondholder("en-US", "Japanese-Formal")).isPresent();
    }

    @Test void blank_override_falls_through_to_language() {
        // Empty/whitespace override should NOT short-circuit the language path —
        // matches "no override" semantics.
        assertThat(DisplayRulesContext.forBondholder("ja-JP", "   ")).isPresent();
        assertThat(DisplayRulesContext.forBondholder("ja-JP", "")).isPresent();
    }

    @Test void block_size_is_within_30_to_120_token_budget() {
        // §7.3: "~30-80 tokens". Approx: 4 chars/token. Use 30..120 token range
        // (120 ≈ 480 chars) as a safety upper bound — block creep is the bug
        // we want to catch, not exact token count.
        for (var lang : new String[]{"ja-JP", "es-ES", "pt-BR", "ko-KR", "zh-CN"}) {
            var block = DisplayRulesContext.forBondholder(lang, null).orElseThrow();
            int approxTokens = block.length() / 4;
            assertThat(approxTokens).as(lang + " block size").isBetween(20, 120);
        }
    }
}
