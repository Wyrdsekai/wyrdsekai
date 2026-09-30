package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP service keys are read from The Safe first. Until 2026-09-28 the production backend read only
 * {@code WYRDSEKAI_MCP_KEY_*} from the environment, while this class said keys were kept in The Safe.
 */
class McpKeyStoreChainTest {

    @Test
    void the_safe_slot_wins_over_the_environment() {
        var backend = McpKeyStore.chained(
            slot -> "openai-key".equals(slot) ? Optional.of("from-safe") : Optional.empty(),
            Map.of("WYRDSEKAI_MCP_KEY_OPENAI_KEY", "from-env")::get,
            name -> null);
        assertThat(backend.getKey("openai-key")).isEqualTo("from-safe");
    }

    @Test
    void the_environment_still_answers_when_the_safe_has_no_slot() {
        var backend = McpKeyStore.chained(
            slot -> Optional.empty(),
            Map.of("WYRDSEKAI_MCP_KEY_OPENAI_KEY", "from-env")::get,
            name -> null);
        assertThat(backend.getKey("openai-key")).isEqualTo("from-env");
        assertThat(backend.getKey("unknown")).isNull();
    }
}
