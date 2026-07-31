package org.wyrdsekai.core.agent;

/**
 * AI disclosure display modes for EU AI Act Article 50 compliance.
 * Controls how AI-generated content is disclosed to users.
 *
 * <ul>
 *   <li>LORE — Fantasy-flavored disclosure ("woven by arcane threads")</li>
 *   <li>WYRD — Minimal wyrd marker (asterisk prefix or subtle indicator)</li>
 *   <li>DIRECT — Plain disclosure ("AI-generated response")</li>
 *   <li>ICON — Machine-readable marker only (for accessibility/screen readers)</li>
 * </ul>
 */
public enum LoreMode {
    /** Fantasy-flavored AI disclosure. Default. */
    LORE("This response is woven by arcane threads — an AI companion speaking in character."),

    /** Minimal marker style. */
    WYRD("*wyrd-woven*"),

    /** Plain, direct disclosure. */
    DIRECT("This is an AI-generated response."),

    /** Machine-readable marker only (no visible text). */
    ICON("");

    private final String disclosureText;

    LoreMode(String disclosureText) {
        this.disclosureText = disclosureText;
    }

    /** Get the disclosure text for this mode. */
    public String disclosureText() {
        return disclosureText;
    }

    /** Whether this mode adds visible text to output. */
    public boolean hasVisibleDisclosure() {
        return !disclosureText.isEmpty();
    }

    /**
     * Build the Layer 8 output constraints string for PromptAssembler.
     * Includes disclosure instruction and any structured output schema.
     */
    public String buildOutputConstraints(boolean structuredOutput) {
        var sb = new StringBuilder();

        // AI disclosure instruction
        if (hasVisibleDisclosure()) {
            sb.append("Important: You are an AI companion. ");
            switch (this) {
                case LORE -> sb.append(
                    "When appropriate, acknowledge your nature using fantasy language "
                        + "(e.g., 'woven by arcane threads', 'I am a construct of the Wyrd'). "
                        + "Do not pretend to be human.");
                case WYRD -> sb.append(
                    "Prefix your responses with *wyrd-woven* to indicate AI origin.");
                case DIRECT -> sb.append(
                    "Clearly state that your responses are AI-generated when asked about your nature.");
                case ICON -> {} // No visible text
            }
            sb.append("\n");
        }

        // Structured output schema
        if (structuredOutput) {
            sb.append("Respond in JSON format with fields: "
                + "{\"speech\": \"your spoken words\", \"action\": \"optional action\", "
                + "\"emotion\": \"current emotional state\"}\n");
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /** Parse from string, defaulting to LORE. */
    public static LoreMode fromString(String mode) {
        if (mode == null) return LORE;
        return switch (mode.toLowerCase()) {
            case "lore" -> LORE;
            case "wyrd" -> WYRD;
            case "direct" -> DIRECT;
            case "icon" -> ICON;
            default -> LORE;
        };
    }
}
