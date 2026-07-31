package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ItemCapabilitySetTest {

    @Test
    void implicit_caps_always_pass() {
        var caps = ItemCapabilitySet.of(Set.of());
        assertDoesNotThrow(() -> caps.require("self.did"));
        assertDoesNotThrow(() -> caps.require("library.search"));
        assertDoesNotThrow(() -> caps.require("time.now"));
    }

    @Test
    void declared_cap_passes() {
        var caps = ItemCapabilitySet.of(List.of("library.add", "drive.mark"));
        assertDoesNotThrow(() -> caps.require("library.add"));
        assertDoesNotThrow(() -> caps.require("drive.mark"));
    }

    @Test
    void undeclared_tier2_cap_throws() {
        var caps = ItemCapabilitySet.of(List.of("library.search"));
        var ex = assertThrows(CapabilityDeniedError.class,
            () -> caps.require("library.add"));
        assertEquals("library.add", ex.capability());
    }

    @Test
    void wildcard_declaration_matches_any_subcap() {
        var caps = ItemCapabilitySet.of(List.of("github.*"));
        assertDoesNotThrow(() -> caps.require("github.create_issue"));
        assertDoesNotThrow(() -> caps.require("github.merge_pr"));
        // Different namespace doesn't match
        assertThrows(CapabilityDeniedError.class,
            () -> caps.require("gitlab.create_issue"));
    }

    @Test
    void unrestricted_set_bypasses_gating() {
        var caps = ItemCapabilitySet.UNRESTRICTED;
        assertDoesNotThrow(() -> caps.require("anything.at.all"));
    }

    @Test
    void audit_hook_records_grants_and_denials() {
        var grants = new AtomicInteger();
        var denials = new AtomicInteger();
        var caps = ItemCapabilitySet.of(List.of("library.add"),
            (cap, allowed) -> {
                if (Boolean.TRUE.equals(allowed)) grants.incrementAndGet();
                else denials.incrementAndGet();
            });
        caps.require("library.add");        // granted
        caps.require("self.did");           // granted (implicit)
        try { caps.require("drive.mark"); } catch (CapabilityDeniedError ignored) {}
        assertEquals(2, grants.get());
        assertEquals(1, denials.get());
    }

    @Test
    void has_predicate_does_not_throw() {
        var caps = ItemCapabilitySet.of(List.of("library.add"));
        assertTrue(caps.has("library.add"));
        assertTrue(caps.has("self.did"));
        assertFalse(caps.has("drive.mark"));
    }

    @Test
    void from_manifest_pulls_capabilities() {
        var m = new ItemManifest("test", "1.0.0", "T.", "did:wyrd:x",
            List.of("library.add", "journal.write"),
            Map.of(), "low", List.of(),
            List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var caps = ItemCapabilitySet.from(m);
        assertTrue(caps.has("library.add"));
        assertTrue(caps.has("journal.write"));
        assertFalse(caps.has("drive.mark"));
    }
}
