package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1142 — SqlRecipeParamOverrides round-trip against a fresh
 * SQLite file (self-bootstrapping schema). Covers upsert / clear and the
 * household-vs-per-agent overlay the RecipeService merge depends on.
 */
class SqlRecipeParamOverridesTest {

    @TempDir Path tmp;
    private SqlRecipeParamOverrides store;

    @BeforeEach void setUp() {
        store = new SqlRecipeParamOverrides(
            "jdbc:sqlite:" + tmp.resolve("ovr.db").toAbsolutePath());
    }

    @Test void upsert_then_read_round_trips() {
        store.upsert("research-pack-freshness", null, "max_dead_chunks", "750", "tester");
        assertThat(store.effectiveFor("research-pack-freshness", null))
            .containsEntry("max_dead_chunks", "750");
    }

    @Test void upsert_is_idempotent_last_write_wins() {
        store.upsert("r", null, "p", "1", "a");
        store.upsert("r", null, "p", "2", "b");
        assertThat(store.effectiveFor("r", null)).containsEntry("p", "2");
    }

    @Test void clear_reverts_to_manifest_default() {
        store.upsert("r", null, "p", "9", "a");
        store.clear("r", null, "p");
        assertThat(store.effectiveFor("r", null)).doesNotContainKey("p");
    }

    @Test void per_agent_override_wins_over_household_wide() {
        store.upsert("r", null, "p", "10", "household");        // household-wide
        store.upsert("r", "did:test:c", "p", "20", "agent");    // per-agent
        // household-only key still surfaces for everyone…
        store.upsert("r", null, "q", "5", "household");

        var eff = store.effectiveFor("r", "did:test:c");
        assertThat(eff).containsEntry("p", "20");   // per-agent overlay won
        assertThat(eff).containsEntry("q", "5");     // household-only inherited

        // A different agent with no per-agent row sees the household value.
        assertThat(store.effectiveFor("r", "did:test:other"))
            .containsEntry("p", "10");
    }

    @Test void null_agent_reads_only_household_wide_rows() {
        store.upsert("r", null, "p", "10", "household");
        store.upsert("r", "did:test:c", "p", "20", "agent");
        // A null caller is the household scope — never sees a per-agent row.
        assertThat(store.effectiveFor("r", null)).containsEntry("p", "10");
    }
}
