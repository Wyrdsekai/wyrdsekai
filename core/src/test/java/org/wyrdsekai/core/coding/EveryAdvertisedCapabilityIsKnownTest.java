package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three lists describe one capability; a capability in two of them does not exist.
 *
 * <h2>What went wrong</h2>
 * A capability has to appear in {@code CRAFTED_ALLOW} (may an item use it), in the
 * generated contract (does the author know it exists), and in
 * {@code ItemManifestValidator.KNOWN_CAPABILITIES} (may an item DECLARE it). On
 * 2026-08-22 the host verbs were added to the first two and not the third, so an item that
 * declared exactly what the contract told it to declare was refused —
 * {@code unknown capability: 'host.file_find'} — the repair loop tried to help and made it
 * worse, and the tool never reached the steward.
 */
class EveryAdvertisedCapabilityIsKnownTest {

    @Test
    @DisplayName("everything the crafted ceiling permits can also be declared")
    void theCeilingAndTheCatalogueAgree() {
        for (var cap : ItemCapabilitySet.CRAFTED_ALLOW) {
            if (cap.endsWith(".*")) continue;   // wildcards are expanded elsewhere
            assertThat(ItemManifestValidator.KNOWN_CAPABILITIES)
                .as("an item may USE %s but declaring it fails validation", cap)
                .containsKey(cap);
        }
    }
}
