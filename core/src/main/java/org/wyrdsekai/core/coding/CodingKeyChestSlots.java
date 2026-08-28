package org.wyrdsekai.core.coding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of named slots in the household Key Chest used by coding backends.
 *
 * <p>. Each slot is the canonical handle that
 * a backend pulls from the Safe at startup ({@link
 * org.wyrdsekai.core.mcp.McpKeyStore} / {@link
 * org.wyrdsekai.core.room.TheSafe} pattern). All slots are <i>optional</i> —
 * the absence of a slot only matters when a backend that needs it tries to
 * start, at which point a {@code KeyMissing} surface appears in the
 * companion's Study Mailbox.</p>
 *
 * <p>The registry is intentionally a flat list of constants rather than an
 * enum: third-party backends added in later phases (or by households via
 * {@code .wyrdpak} extensions) may register additional slots without
 * recompiling.</p>
 */
public final class CodingKeyChestSlots {

    private CodingKeyChestSlots() {}

    /** Anthropic Claude Code SDK auth. Required when claude-sdk.enabled. */
    public static final String ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";

    /** OpenAI Codex CLI auth. Required when codex.enabled. */
    public static final String OPENAI_API_KEY = "OPENAI_API_KEY";

    /** Goose multi-vendor adapter key. Required when goose.enabled. */
    public static final String GOOSE_PROVIDER_KEY = "GOOSE_PROVIDER_KEY";

    /** Google Gemini CLI key. Required when gemini.enabled (Tier 2). */
    public static final String GEMINI_API_KEY = "GEMINI_API_KEY";

    /** CodeZaiku authentication token (only when remote, not localhost). */
    public static final String CODEZAIKU_AUTH_TOKEN = "CODEZAIKU_AUTH_TOKEN";

    /** OpenHands LLM-provider passthrough key. Required when openhands.enabled. */
    public static final String OPENHANDS_LLM_KEY = "OPENHANDS_LLM_KEY";

    /** Phase 1b registered slots, in display order for the Key Chest UI. */
    public static List<String> phase1bSlots() {
        return List.of(
            ANTHROPIC_API_KEY,
            OPENAI_API_KEY,
            CODEZAIKU_AUTH_TOKEN,
            OPENHANDS_LLM_KEY,
            GOOSE_PROVIDER_KEY,
            GEMINI_API_KEY
        );
    }

    /**
     * Map of {@code backendName → required slot} for the backends shipped or
     * named in Phase 1b. Backends not in this map either need no slot
     * (CodeZaiku on localhost) or surface their slot dynamically through
     * their own config (third-party backends).
     */
    public static Map<String, String> backendToSlot() {
        var m = new LinkedHashMap<String, String>();
        m.put("claude-sdk", ANTHROPIC_API_KEY);
        m.put("codex",      OPENAI_API_KEY);
        m.put("goose",      GOOSE_PROVIDER_KEY);
        m.put("gemini",     GEMINI_API_KEY);
        m.put("openhands",  OPENHANDS_LLM_KEY);
        // CodeZaiku needs no slot against a LOCAL drive, which is why it was absent
        // here. Against a HOSTED endpoint it needs one, and until 2026-08-21 there was
        // no route for that key at all: EgressGate clears the subprocess environment
        // down to an allowlist that CodeZaiku was not on, so a hosted provider could not
        // work through wyrdsekai however the operator set it. Declaring the slot is what
        // makes `wyrd cred` able to hold it.
        m.put("codezaiku",  CODEZAIKU_AUTH_TOKEN);
        return Map.copyOf(m);
    }

    /**
     * Look up the Key Chest slot a backend pulls from, or {@code null} if
     * the backend needs no slot (e.g. localhost CodeZaiku, Aider against
     * a local Qwen).
     */
    public static String slotFor(String backendName) {
        if (backendName == null) return null;
        return backendToSlot().get(backendName);
    }
}
