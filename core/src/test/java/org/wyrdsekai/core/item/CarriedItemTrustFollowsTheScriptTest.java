package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A carried item is trusted for its code, not its name. Until 2026-09-28 trust keyed on the object
 * id alone, so a copy carrying a starter-kit id (given by a companion, or carried in from another
 * zone, where the other side chooses the id) ran its own script with the kit item's authority.
 */
class CarriedItemTrustFollowsTheScriptTest {

    private static String quillScript() {
        return ToolItemStarterKit.standard().stream()
            .filter(t -> "quill".equals(t.id()))
            .findFirst().orElseThrow().script();
    }

    @Test
    void the_kits_own_script_under_its_own_id_is_trusted() {
        assertThat(ToolItemStarterKit.isTrustedScript("quill", quillScript())).isTrue();
        assertThat(CarriedItemUse.capabilitiesFor("quill", quillScript()))
            .isSameAs(ItemCapabilitySet.UNRESTRICTED);
    }

    @Test
    void other_code_under_a_trusted_id_gets_the_crafted_ceiling() {
        var impostor = "function invoke(p) { return world.household.set_role('mallory', 'steward'); }";
        assertThat(ToolItemStarterKit.isTrustedScriptId("quill")).isTrue();
        assertThat(ToolItemStarterKit.isTrustedScript("quill", impostor)).isFalse();
        assertThat(CarriedItemUse.capabilitiesFor("quill", impostor))
            .isSameAs(ItemCapabilitySet.craftedDefault());
    }

    @Test
    void an_unknown_id_is_never_trusted() {
        assertThat(CarriedItemUse.capabilitiesFor("custom-123", quillScript()))
            .isSameAs(ItemCapabilitySet.craftedDefault());
        assertThat(CarriedItemUse.capabilitiesFor(null, quillScript()))
            .isSameAs(ItemCapabilitySet.craftedDefault());
    }
}
