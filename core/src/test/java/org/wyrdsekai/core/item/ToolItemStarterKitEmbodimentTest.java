package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * audit — every built-in {@link ToolItem} in the starter
 * kit must declare an embodiment block (silent | emits). The registry in
 * {@link ToolItemStarterKit#EMBODIMENT_REGISTRY} is the source of truth;
 * {@link ToolItemStarterKit#attachEmbodiment} fills the spec onto fresh
 * items and WARNs if the registry is missing an id.
 */
class ToolItemStarterKitEmbodimentTest {

    @Test
    void everyStandardKitItemDeclaresEmbodiment() {
        var items = ToolItemStarterKit.standard();
        var missing = items.stream()
            .filter(i -> i.embodiment() == null)
            .map(ToolItem::id)
            .toList();
        assertTrue(missing.isEmpty(),
            "starter-kit items missing embodiment: " + missing);
    }

    @Test
    void everyInherentActionDeclaresEmbodiment() {
        var actions = ToolItemStarterKit.inherentActions();
        var missing = actions.stream()
            .filter(i -> i.embodiment() == null)
            .map(ToolItem::id)
            .toList();
        assertTrue(missing.isEmpty(),
            "inherent actions missing embodiment: " + missing);
    }

    @Test
    void everyAgencyActionDeclaresEmbodiment() {
        // Layer-3 agency acts + the W7 familiar/form family (2026-07-11).
        var actions = ToolItemStarterKit.agencyActions();
        var missing = actions.stream()
            .filter(i -> i.embodiment() == null)
            .map(ToolItem::id)
            .toList();
        assertTrue(missing.isEmpty(),
            "agency actions missing embodiment: " + missing);
    }

    @Test
    void everyMinimalKitItemDeclaresEmbodiment() {
        var items = ToolItemStarterKit.minimal();
        for (var i : items) {
            assertNotNull(i.embodiment(),
                "minimal-kit item '" + i.id() + "' missing embodiment");
        }
    }

    @Test
    void everyRegisteredEmbodimentIsValid() {
        // Each registered spec must satisfy the silent|emits contract.
        for (var entry : ToolItemStarterKit.EMBODIMENT_REGISTRY.entrySet()) {
            var spec = entry.getValue();
            assertTrue(spec.isValid(),
                "registry entry '" + entry.getKey()
                + "' is structurally invalid (silent needs reason; non-silent needs emits list)");
        }
    }

    @Test
    void registryCoversAllJvmBakedStarterKitIds() {
        // Together — standard() + inherentActions() — should be fully covered.
        var allIds = new HashSet<String>();
        ToolItemStarterKit.standard().forEach(i -> allIds.add(i.id()));
        ToolItemStarterKit.inherentActions().forEach(i -> allIds.add(i.id()));
        ToolItemStarterKit.agencyActions().forEach(i -> allIds.add(i.id()));
        // Disk-loaded scripted items are not part of the JVM-baked registry;
        // exclude them — they go through ItemEmbodimentSpec on their manifest.
        var diskIds = ToolItemStarterKit.loadedScriptedItems().stream()
            .map(ToolItem::id)
            .collect(Collectors.toSet());
        var bakedIds = allIds.stream().filter(id -> !diskIds.contains(id))
            .collect(Collectors.toSet());
        var registry = ToolItemStarterKit.EMBODIMENT_REGISTRY.keySet();
        var bakedMissing = bakedIds.stream()
            .filter(id -> !registry.contains(id))
            .collect(Collectors.toSet());
        assertTrue(bakedMissing.isEmpty(),
            "JVM-baked ids not in EMBODIMENT_REGISTRY: " + bakedMissing);
    }
}
