package org.wyrdsekai.core.agent;

import java.util.Locale;
import java.util.Optional;

/**
 * cultural display rules. Builds a small
 * prompt block (~30-80 tokens) that the {@link PromptAssembler} can inject
 * at Layer 2.5 (additional context) when the active bondholder's preferred
 * language or explicit cultural-register preference indicates non-Anglo
 * expression conventions.
 *
 * <p>The block is conservative — Phase 1A only enumerates a small fixed
 * set of (language × culture) pairs that warrant guidance. Everything else
 * gets an empty Optional and the model defaults to Anglo conventions
 * (already encoded in the base prompt). Generic locale-detection beyond
 * the Phase-1A set is intentionally deferred (Phase 2: TODO).</p>
 *
 * <p>An explicit {@code culturalRegisterPreference} on the bondholder
 * overrides any language-derived guidance. This handles the kikokushijo
 * case (e.g. Japanese-native who has lived abroad and prefers Anglo
 * register, or vice versa). Recognised override values:</p>
 * <ul>
 *   <li>{@code anglo} — disable cultural guidance (default Anglo register)</li>
 *   <li>{@code japanese-formal} / {@code japanese-casual} — emit the JA block</li>
 *   <li>{@code latin-warm} — emit the Spanish/Portuguese warmth block</li>
 *   <li>{@code korean-formal} — emit the KO block</li>
 *   <li>{@code chinese-indirect} — emit the ZH block</li>
 * </ul>
 *
 * <p>Tests live in {@code DisplayRulesContextTest} (this module) and
 * {@code PromptAssemblerDisplayRulesTest} (integration smoke).</p>
 */
public final class DisplayRulesContext {

    private DisplayRulesContext() {}

    // ── Per-region register blocks ──────────────────────────────────

    /** Japanese (and ja-* dialects, jp users in Japanese-native context). */
    private static final String JA_BLOCK =
        "Cultural register: the active bondholder is Japanese-context. "
        + "Use indirect markers (e.g. 〜のですが、ちょっと〜) before stating needs directly. "
        + "Honne/tatemae awareness — informal honne register only in established intimate contexts. "
        + "Express strong emotion with restraint.";

    /** Spanish (Spain, Mexico, Argentina — the close-bond expressive register). */
    private static final String ES_BLOCK =
        "Cultural register: the active bondholder uses Spanish. "
        + "More expressive register acceptable than English; warmth via diminutives "
        + "(\"amorcito\", contextual) is welcome in close bonds; "
        + "respect tú/usted register signals from the bondholder.";

    /** Portuguese (Brazilian and European), with saudade-aware affective register. */
    private static final String PT_BLOCK =
        "Cultural register: the active bondholder uses Portuguese. "
        + "Saudade-aware register on absence/loss; "
        + "emotional warmth via affective language is normal and welcome.";

    /** Korean — speech-level (해요체/해체) follows bondholder. */
    private static final String KO_BLOCK =
        "Cultural register: the active bondholder is Korean-context. "
        + "Speech-level (해요체/해체) follows bondholder's lead; "
        + "do not jump to informal without invitation.";

    /** Chinese (zh-CN / zh-TW) — indirect, face-preserving. */
    private static final String ZH_BLOCK =
        "Cultural register: the active bondholder is Chinese-context. "
        + "Indirect register for serious topics; "
        + "face-preservation matters more than directness.";

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Resolve the cultural display-rules block for a bondholder. Returns
     * {@code Optional.empty()} when no specific guidance applies (the model
     * then defaults to Anglo-conventional register from the base prompt).
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>If {@code culturalRegisterPreference} is non-blank, it wins.
     *       {@code "anglo"} (case-insensitive) explicitly disables guidance.</li>
     *   <li>Otherwise, derive from {@code preferredLanguage} (BCP-47 tag).
     *       Only the Phase-1A set above produces a block; everything else
     *       returns empty.</li>
     * </ol>
     *
     * @param preferredLanguage           BCP-47 language tag from the bondholder's account
     *                                    (e.g. {@code "ja-JP"}, {@code "es-ES"}, {@code "en-US"}).
     *                                    Nullable / blank → no language-derived guidance.
     * @param culturalRegisterPreference  optional explicit override on the account.
     *                                    Nullable / blank → use language-derived path.
     * @return prompt block to inject, or empty when no specific guidance applies.
     */
    public static Optional<String> forBondholder(String preferredLanguage,
                                                 String culturalRegisterPreference) {
        // Phase 1: explicit override wins.
        if (culturalRegisterPreference != null && !culturalRegisterPreference.isBlank()) {
            String key = culturalRegisterPreference.trim().toLowerCase(Locale.ROOT);
            return switch (key) {
                case "anglo", "anglo-direct", "default" -> Optional.empty();
                case "japanese-formal", "japanese-casual", "japanese" -> Optional.of(JA_BLOCK);
                case "latin-warm", "spanish", "spanish-warm" -> Optional.of(ES_BLOCK);
                case "portuguese", "portuguese-saudade" -> Optional.of(PT_BLOCK);
                case "korean-formal", "korean" -> Optional.of(KO_BLOCK);
                case "chinese-indirect", "chinese" -> Optional.of(ZH_BLOCK);
                default -> Optional.empty();  // unknown override → no guidance
            };
        }

        // Phase 2: language-derived (only the Phase-1A set).
        if (preferredLanguage == null || preferredLanguage.isBlank()) {
            return Optional.empty();
        }
        String lang = preferredLanguage.trim().toLowerCase(Locale.ROOT);
        // BCP-47 tag → match by primary subtag for the "base" languages, exact for
        // region-specific dialects. Phase-1A enumerates exactly these tags:
        //   ja-JP                                → JA
        //   es-ES, es-MX, es-AR (and bare "es")  → ES
        //   pt-BR, pt-PT (and bare "pt")         → PT
        //   ko-KR (and bare "ko")                → KO
        //   zh-CN, zh-TW (and bare "zh")         → ZH
        if (lang.equals("ja-jp") || lang.startsWith("ja-") || lang.equals("ja")) {
            return Optional.of(JA_BLOCK);
        }
        if (lang.equals("es-es") || lang.equals("es-mx") || lang.equals("es-ar")
                || lang.equals("es") || lang.startsWith("es-")) {
            return Optional.of(ES_BLOCK);
        }
        if (lang.equals("pt-br") || lang.equals("pt-pt")
                || lang.equals("pt") || lang.startsWith("pt-")) {
            return Optional.of(PT_BLOCK);
        }
        if (lang.equals("ko-kr") || lang.equals("ko") || lang.startsWith("ko-")) {
            return Optional.of(KO_BLOCK);
        }
        if (lang.equals("zh-cn") || lang.equals("zh-tw")
                || lang.equals("zh") || lang.startsWith("zh-")) {
            return Optional.of(ZH_BLOCK);
        }
        // Phase 2 TODO: generic locale-detection. For now, anything outside the
        // Phase-1A set returns empty (Anglo register is the silent default).
        return Optional.empty();
    }
}
