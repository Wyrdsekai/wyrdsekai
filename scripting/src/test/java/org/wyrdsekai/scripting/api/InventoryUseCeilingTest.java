package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** An item that uses another item cannot lend it more authority than it has itself. */
class InventoryUseCeilingTest {

    @Test
    void the_used_item_gets_the_users_capabilities_as_its_ceiling() {
        var seen = new AtomicReference<ItemCapabilitySet>();
        var provider = new ArtifactApiTest.StubProvider() {
            @Override
            public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params,
                                                    int depth, ItemCapabilitySet ceiling) {
                seen.set(ceiling);
                return Map.of("ok", true);
            }
        };
        var caps = ItemCapabilitySet.of(List.of("llm.summarize"));
        new ItemWorldApi(provider, caps).inventory.use("quill", Map.of());
        assertThat(seen.get()).isSameAs(caps);
    }
}
