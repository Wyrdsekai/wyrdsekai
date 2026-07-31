package org.wyrdsekai.core.item;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.net.NetworkAllowEntry;
import org.wyrdsekai.core.net.NetworkGate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * / — the permission-fixed
 * network items surface only once the steward opens the corresponding door.
 * The {@link org.wyrdsekai.core.net.NetworkGate} stays the enforcement
 * boundary; this gating is what keeps unreachable reach off the tool menu.
 */
final class NetworkItemKitSurfaceTest {

    private static NetworkGate gateWith(NetworkAllowEntry... entries) {
        return new NetworkGate(List.of(entries), Map.of());
    }

    private static NetworkAllowEntry entry(String host, String... kinds) {
        return new NetworkAllowEntry(host, Set.of(kinds), "household:" + host, List.of(), null);
    }

    private static List<String> ids(List<ToolItem> items) {
        return items.stream().map(ToolItem::id).toList();
    }

    @Test
    void nothing_granted_nothing_surfaced() {
        assertTrue(NetworkItemKit.enabledItems(NetworkGate.empty(), false).isEmpty(),
            "a fresh install must not offer network items the gate would only deny");
    }

    @Test
    void ssh_allowlist_entry_lights_far_hand_and_wire() {
        var items = ids(NetworkItemKit.enabledItems(gateWith(entry("second-node", "ssh")), false));
        assertEquals(List.of("far_hand", "wire"), items);
    }

    @Test
    void scp_allowlist_entry_lights_postrider() {
        var items = ids(NetworkItemKit.enabledItems(gateWith(entry("second-node", "scp")), false));
        assertEquals(List.of("postrider", "wire"), items);
    }

    @Test
    void household_transport_lights_courier_satchel() {
        var items = ids(NetworkItemKit.enabledItems(NetworkGate.empty(), true));
        assertEquals(List.of("courier_satchel", "wire"), items);
    }

    @Test
    void full_grant_surfaces_all_four() {
        var items = ids(NetworkItemKit.enabledItems(
            gateWith(entry("second-node", "ssh", "scp")), true));
        assertEquals(List.of("courier_satchel", "far_hand", "postrider", "wire"), items);
    }

    @Test
    void enabled_items_carry_an_embodiment_declaration() {
        // silence is a choice, absence is a bug.
        for (var item : NetworkItemKit.enabledItems(gateWith(entry("second-node", "ssh", "scp")), true)) {
            assertNotNull(item.embodiment(), item.id() + " has no embodiment declaration");
        }
    }

    @Test
    void network_items_are_trusted_scripts() {
        // They are JVM-baked programs over world.net.* — the carried-item
        // execution paths must run them UNRESTRICTED (the NetworkGate is the
        // boundary), not under the crafted ceiling that denies net.*.
        for (var id : NetworkItemKit.ITEM_IDS) {
            assertTrue(ToolItemStarterKit.isTrustedScriptId(id),
                id + " must be in the trusted-script TCB");
        }
    }
}
