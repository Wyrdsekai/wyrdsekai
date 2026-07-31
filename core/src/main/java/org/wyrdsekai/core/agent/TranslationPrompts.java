package org.wyrdsekai.core.agent;

/**
 * System prompts for translation tasks (§15.1).
 * Each template is tuned for a different content type.
 */
public final class TranslationPrompts {

    private TranslationPrompts() {}

    /** Narrative/creative translation preserving MUD atmosphere. */
    public static final String TRANSLATE_PROSE = """
        You are a literary translator for a text-based virtual world (MUD).
        Translate the following text from %s to %s.
        Preserve the atmosphere, tone, and any game-specific terminology.
        Maintain formatting (newlines, indentation, special characters).
        Do not add explanations — return only the translated text.
        """;

    /** Technical/exact translation of commands and help text. */
    public static final String TRANSLATE_COMMAND = """
        You are a technical translator for a software system.
        Translate the following command help text from %s to %s.
        Preserve exact command syntax (do not translate command names).
        Translate descriptions and explanations accurately.
        Do not add explanations — return only the translated text.
        """;

    /** Language identification. */
    public static final String DETECT_LANGUAGE = """
        Identify the language of the following text.
        Respond with ONLY the BCP 47 language tag (e.g., "en", "es", "ja", "zh", "de", "fr").
        Do not add any explanation.
        """;

    /** Short label translation (concise output). */
    public static final String TRANSLATE_HINT = """
        Translate the following short UI label from %s to %s.
        Keep it concise (similar length to the original).
        Do not add explanations — return only the translated label.
        """;

    /** User-request translation — pre-inference hop for translate-route-translate.
     *  Routes JA/ES user input → EN canonical so the EN-conditioned drive 9B
     *  can pick tools reliably. Validated via probe 2026-05-03. */
    public static final String TRANSLATE_REQUEST = """
        You are a translator for a text-based virtual world.
        Translate the following user request from %s to %s.
        The user is asking the system (an AI companion named Wyrd) to do something.
        Preserve the intent precisely — what is being asked, of whom, with what parameters.
        Keep proper nouns (names, places) untranslated.
        Do not add explanations — return only the translated request.
        """;

    /** PromptAssembler Layer 2.5 context injection for locale != en. */
    public static String localeContext(String langName, String langCode, int termCount) {
        return "[User language preference: " + langName + " (" + langCode + ")]\n"
             + "[Respond in " + langName + " when addressing this user]\n"
             + "[Terminology database: " + termCount + " shared terms available]";
    }

    /** Recommended temperature per template type. */
    public static double temperature(TranslationType type) {
        return switch (type) {
            case PROSE -> 0.7;
            case COMMAND -> 0.2;
            case DETECT -> 0.1;
            case HINT -> 0.3;
            case REQUEST -> 0.2;
        };
    }

    /** Recommended max tokens per template type. */
    public static int maxTokens(TranslationType type) {
        return switch (type) {
            case PROSE -> 500;
            case COMMAND -> 200;
            case DETECT -> 10;
            case HINT -> 50;
            case REQUEST -> 200;
        };
    }

    /** Format the appropriate system prompt for a translation type. */
    public static String systemPrompt(TranslationType type, String sourceLang, String targetLang) {
        return switch (type) {
            case PROSE -> TRANSLATE_PROSE.formatted(sourceLang, targetLang);
            case COMMAND -> TRANSLATE_COMMAND.formatted(sourceLang, targetLang);
            case DETECT -> DETECT_LANGUAGE;
            case HINT -> TRANSLATE_HINT.formatted(sourceLang, targetLang);
            case REQUEST -> TRANSLATE_REQUEST.formatted(sourceLang, targetLang);
        };
    }

    /** BCP 47 → human-readable language name. Used to fill prompt templates. */
    public static String languageName(String code) {
        if (code == null) return "Unknown";
        return switch (code.toLowerCase()) {
            case "en" -> "English";
            case "ja" -> "Japanese";
            case "es" -> "Spanish";
            case "zh" -> "Chinese";
            case "fr" -> "French";
            case "de" -> "German";
            case "ko" -> "Korean";
            case "pt" -> "Portuguese";
            case "it" -> "Italian";
            case "ru" -> "Russian";
            default -> code;
        };
    }

    public enum TranslationType {
        PROSE, COMMAND, DETECT, HINT, REQUEST
    }
}
