package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A declared credential slot with nothing on the other end is not a credential path.
 *
 * <h2>What was actually wired</h2>
 * The pieces all existed and none of them met. {@code CodingKeyChestSlots} declared which
 * slot each backend pulls from. {@code DefaultAuthResolver} read that slot through a
 * {@code keyChestLookup} function and returned an {@link AuthMode.ApiKey}. Backends called
 * {@code resolveAuth(NAME)} at task-spawn.
 *
 * <p>And {@code CoreServices} called the ONE-ARG
 * {@code CodingBackendBootstrap.init(config)}, so {@code keyChestLookup} defaulted to
 * {@code slot -> null}. Every paid or hosted backend surfaced {@code AuthMissing} no
 * matter what the household had stored — goose as much as CodeZaiku. The slot was
 * declared, the resolver was built, the backends asked, and nothing was ever on the other
 * end.
 *
 * <p>Found on 2026-08-21 while closing the CodeZaiku hosted-endpoint path: the ambient-env
 * route worked, the stored-credential route could not, and the reason was a missing
 * argument two modules away.
 */
class ABackendCanActuallyGetItsKeyTest {

    /**
     * The production wiring must pass a real lookup.
     *
     * <p>A source scan because the alternative is booting the whole of CoreServices. What
     * matters is exactly the thing that was wrong: that the two-arg overload is the one
     * being called, and that what it is handed reaches the household credential chain
     * rather than a stub.
     */
    @Test
    void core_services_hands_the_bootstrap_a_real_key_chest_lookup() throws Exception {
        var src = Files.readString(find(
            "core/src/main/java/org/wyrdsekai/core/bootstrap/CoreServices.java"));
        var call = src.substring(src.indexOf("CodingBackendBootstrap.init("));
        call = call.substring(0, Math.min(call.length(), 1200));

        assertThat(call)
            .as("the one-arg overload leaves keyChestLookup as slot -> null, and every "
                + "backend then surfaces AuthMissing however the key was stored")
            .contains("CredentialResolver");
        assertThat(call)
            .as("resolve(), not has(): a backend asking for a key it needs is a real "
                + "miss and the steward should hear about it")
            .contains(".resolve(slot)");
    }

    /** Every backend that declares a slot can have that slot filled. */
    @Test
    void every_declared_slot_is_reachable_through_the_credential_chain() {
        var slots = CodingKeyChestSlots.backendToSlot();
        assertThat(slots)
            .as("codezaiku needs a slot the moment its drive is not localhost")
            .containsEntry("codezaiku", CodingKeyChestSlots.CODEZAIKU_AUTH_TOKEN);
        assertThat(slots.values())
            .as("a blank slot name would silently resolve to nothing")
            .allSatisfy(v -> assertThat(v).isNotBlank());
    }

    /**
     * And the routing credential still survives the egress scrub, for the operator who
     * exports it into the service environment instead of storing it.
     */
    @Test
    void the_ambient_route_remains_open_too() {
        assertThat(EgressGate.DEFAULT_ENV_ALLOWLIST)
            .contains("CODEZAIKU_AUTH_TOKEN", "CODEZAIKU_API_KEY")
            .as("goose has had OPENAI_API_KEY here since the gate was written")
            .contains("OPENAI_API_KEY");
    }

    private static Path find(String repoRelative) {
        for (var candidate : List.of(repoRelative, "../" + repoRelative,
                repoRelative.replaceFirst("^core/", ""))) {
            var p = Path.of(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("not found from " + System.getProperty("user.dir")
            + ": " + repoRelative + " — this guard must never silently pass");
    }
}
