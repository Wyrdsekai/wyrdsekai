package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalZoneRegistryTest {

    @Test void empty_startsEmpty(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        assertEquals(0, reg.size());
        assertTrue(reg.defaultLabel().isEmpty());
    }

    @Test void add_registersLabel(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        assertTrue(reg.contains("kitchen"));
        assertEquals(1, reg.size());
    }

    @Test void add_rejectsReserved(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        for (var keyword : ZoneLabels.RESERVED) {
            assertThrows(IllegalArgumentException.class,
                () -> reg.add(keyword),
                "must reject reserved keyword: " + keyword);
        }
    }

    @Test void add_rejectsMalformed(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        assertThrows(IllegalArgumentException.class, () -> reg.add("Kitchen"));
        assertThrows(IllegalArgumentException.class, () -> reg.add("kitchen.main"));
        assertThrows(IllegalArgumentException.class, () -> reg.add(""));
    }

    @Test void add_rejectsDuplicate(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        assertThrows(IllegalArgumentException.class, () -> reg.add("kitchen"));
    }

    @Test void remove_returnsTrueOnHit(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        assertTrue(reg.remove("kitchen"));
        assertFalse(reg.remove("kitchen"));
        assertFalse(reg.contains("kitchen"));
    }

    @Test void defaultLabel_returnsFirstRegistered(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        reg.add("garage");
        reg.add("study");
        assertEquals("kitchen", reg.defaultLabel().orElseThrow());
    }

    @Test void defaultLabel_survivesOtherRemovals(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        reg.add("garage");
        reg.remove("garage");
        assertEquals("kitchen", reg.defaultLabel().orElseThrow());
    }

    @Test void defaultLabel_updatesIfFirstRemoved(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        reg.add("garage");
        reg.remove("kitchen");
        assertEquals("garage", reg.defaultLabel().orElseThrow());
    }

    @Test void list_isOrderPreserving(@TempDir Path tmp) {
        var reg = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        reg.add("kitchen");
        reg.add("garage");
        reg.add("study");
        assertEquals(List.of("kitchen", "garage", "study"), reg.list());
    }

    @Test void saveAndLoad_roundTrip(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("my-zones");
        var reg = LocalZoneRegistry.empty(file);
        reg.add("kitchen");
        reg.add("garage");
        reg.save();

        assertTrue(Files.exists(file));
        var reloaded = LocalZoneRegistry.load(file);
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.contains("kitchen"));
        assertTrue(reloaded.contains("garage"));
        assertEquals("kitchen", reloaded.defaultLabel().orElseThrow());
    }

    @Test void load_returnsEmptyWhenFileMissing(@TempDir Path tmp) throws Exception {
        var reg = LocalZoneRegistry.load(tmp.resolve("does-not-exist"));
        assertEquals(0, reg.size());
    }

    @Test void load_ignoresBlankAndCommentLines(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("my-zones");
        Files.writeString(file, """
            # my zones
            kitchen

            # second group
            garage
            """);
        var reg = LocalZoneRegistry.load(file);
        assertEquals(2, reg.size());
        assertTrue(reg.contains("kitchen"));
        assertTrue(reg.contains("garage"));
    }

    @Test void load_failsOnHandEditedReserved(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("my-zones");
        Files.writeString(file, "home\n");
        var ex = assertThrows(IOException.class,
            () -> LocalZoneRegistry.load(file));
        // Defense against operator hand-edit — spec §2.4 is data-layer
        // invariant, not just add-time.
        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test void load_reportsLineNumber(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("my-zones");
        Files.writeString(file, "kitchen\ngarage\nBADNAME\n");
        var ex = assertThrows(IOException.class,
            () -> LocalZoneRegistry.load(file));
        assertTrue(ex.getMessage().contains(":3:"));
    }
}
