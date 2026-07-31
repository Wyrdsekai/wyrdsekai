package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C #1008 — verify the per-companion ship-default enrollment
 * hook is wired into CompanionActor's soul-birth path. Source-text-based per
 * the project pattern (see {@link CompanionActorInnerMonologueWiringTest}):
 * cheap, deterministic, catches "did the wire get removed in a refactor"
 * without needing to spin up a Pekko actor system + RecipeService stack.
 *
 * <p>Behavioral correctness of provisionForCompanion is covered by
 * {@code ShipDefaultEnrollmentProvisionerTest}; this catches structural drift
 * at the *call site* — the only place CompanionActor invokes the hook.
 */
class CompanionActorRecipeEnrollmentWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    @Test
    void provisionForCompanion_is_invoked_at_soul_birth() throws Exception {
        var src = sourceText();
        // The call may be wrapped across two lines as
        //   ShipDefaultEnrollmentProvisioner\n    .provisionForCompanion(...)
        // so check both substrings independently — both must be present and
        // close together (within 200 chars).
        var ix1 = src.indexOf("ShipDefaultEnrollmentProvisioner", src.indexOf("class CompanionActor"));
        var ix2 = src.indexOf(".provisionForCompanion(");
        assertThat(ix1)
            .as("CompanionActor must reference ShipDefaultEnrollmentProvisioner (#1008)")
            .isGreaterThan(0);
        assertThat(ix2)
            .as("CompanionActor must call .provisionForCompanion(newDid) (#1008)")
            .isGreaterThan(0);
        assertThat(Math.abs(ix2 - ix1))
            .as("the type reference and the method call must be adjacent — "
                + "they form the same call site")
            .isLessThan(200);
    }

    @Test
    void provisionForCompanion_call_lives_after_soulStore_store() throws Exception {
        // Order matters: the provisioner reads the canonical DID, which only
        // exists after soulStore.store(birthManifest) lands the row. If a
        // refactor moves the call above the store, idempotency still holds
        // (the registry will no-op when DID is null/blank — see the unit
        // test provisionForCompanion_blank_did_returns_zero) but the wire
        // is wrong on intent. Catch it.
        var src = sourceText();
        int storeIdx = src.indexOf("soulStore.store(birthManifest)");
        int provisionIdx = src.indexOf(".provisionForCompanion(");
        assertThat(storeIdx)
            .as("expected soulStore.store(birthManifest) call site to be present")
            .isGreaterThan(0);
        assertThat(provisionIdx)
            .as("expected provisionForCompanion call site to be present")
            .isGreaterThan(0);
        assertThat(provisionIdx)
            .as("provisionForCompanion must come AFTER soulStore.store(birthManifest) "
                + "in CompanionActor source — the DID is only canonical after store")
            .isGreaterThan(storeIdx);
    }

    @Test
    void provisionForCompanion_is_exception_guarded() throws Exception {
        // Per #1008: recipe-side failures (missing registry, store IO error)
        // must never crash soul-birth. The call is wrapped in try/catch
        // around either Exception or Throwable. Either form is acceptable.
        var src = sourceText();
        int callIdx = src.indexOf(".provisionForCompanion(");
        assertThat(callIdx).isGreaterThan(0);

        // Look ~600 chars in either direction for the enclosing try/catch.
        int windowStart = Math.max(0, callIdx - 600);
        int windowEnd = Math.min(src.length(), callIdx + 600);
        String window = src.substring(windowStart, windowEnd);
        assertThat(window)
            .as("provisionForCompanion call must be wrapped in try/catch — "
                + "recipe-side failures must NEVER crash soul-birth")
            .containsAnyOf("try {", "try{");
        assertThat(window)
            .as("provisionForCompanion call must be wrapped in catch — see above")
            .containsAnyOf("catch (Exception", "catch (Throwable",
                           "catch(Exception", "catch(Throwable");
    }
}
