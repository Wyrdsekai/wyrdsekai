package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code world.self.*} call the contract documents must actually exist.
 *
 * <h2>The live failure</h2>
 * The items-as-tools contract has listed {@code world.self.callerDid() → String} all
 * along; {@code SelfApi} exposed only {@code did()} and {@code name()}. So a backend
 * writing exactly what it was told produced, on first use:
 *
 * <pre>
 *   TypeError: invokeMember (callerDid) on ItemWorldApi$SelfApi failed
 *              due to: Unknown identifier: callerDid
 * </pre>
 *
 * <p>Live 2026-08-21: {@code library_storyteller} died on that line and the steward got no
 * tool. The provider had {@code callerDid()} the entire time — this was purely the
 * document and the runtime disagreeing, which is the defect class the generated surface
 * exists to end. The generated half cannot drift; the hand-written half still can, and
 * did.
 */
class TheSelfSurfaceMatchesItsContractTest {

    private ItemScriptExecutor executor;
    private ItemScriptExecutorTest.MockItemWorldApiProvider provider;

    @BeforeEach
    void setUp() {
        executor = new ItemScriptExecutor();
        provider = new ItemScriptExecutorTest.MockItemWorldApiProvider();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void caller_did_is_callable_because_the_contract_says_it_is() {
        var out = executor.execute("self-caller", """
            function invoke(params) {
              return { ok: true, summary: "caller=" + world.self.callerDid() };
            }
            """, Map.of(), provider);
        assertThat(String.valueOf(out.get("error")))
            .as("world.self.callerDid() is documented; it must not be a TypeError")
            .doesNotContain("Unknown identifier");
        assertThat(out.get("ok")).isEqualTo(true);
    }

    @Test
    void the_rest_of_the_self_surface_still_works() {
        var out = executor.execute("self-rest", """
            function invoke(params) {
              return { ok: true, who: world.self.name(), id: world.self.did() };
            }
            """, Map.of(), provider);
        assertThat(out.get("error")).isNull();
        assertThat(out.get("ok")).isEqualTo(true);
    }
}
