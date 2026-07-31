package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockListTest {

    private static final String DID_ALICE =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_BOB =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";
    private static final String DID_CURATOR =
        "did:wyrd:z6MkwQvE85q8FepJRJJWyEFY5gTRGPSxjA1MTFpUPu8PXt3B";

    private static final Instant T0 = Instant.parse("2026-04-19T12:00:00Z");

    // ── empty state ───────────────────────────────────────────────────

    @Test void empty_startsEmpty(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        assertEquals(0, list.size());
        assertFalse(list.contains(DID_ALICE));
    }

    // ── add / contains / remove ───────────────────────────────────────

    @Test void add_preemptiveBlock(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, "abusive, warned by bob");
        assertTrue(list.contains(DID_ALICE));
        var entry = list.get(DID_ALICE).orElseThrow();
        assertFalse(entry.revoke());
        assertTrue(entry.isLocal());
        assertEquals("abusive, warned by bob", entry.note());
    }

    @Test void add_reactiveBlockWithRevokeFlag(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, true, "spammed my companion");
        var entry = list.get(DID_ALICE).orElseThrow();
        assertTrue(entry.revoke(),
            "revoke flag signals caller to also publish explicit revocation envelope");
    }

    @Test void add_overwritesExisting(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, "first note");
        list.add(DID_ALICE, T0.plusSeconds(10), true, "second note");
        var entry = list.get(DID_ALICE).orElseThrow();
        assertTrue(entry.revoke());
        assertEquals("second note", entry.note());
    }

    @Test void remove_returnsTrueOnHit(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, null);
        assertTrue(list.remove(DID_ALICE));
        assertFalse(list.contains(DID_ALICE));
        assertFalse(list.remove(DID_ALICE));
    }

    // ── validation ────────────────────────────────────────────────────

    @Test void add_rejectsMalformedDid(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        assertThrows(IllegalArgumentException.class,
            () -> list.add("not-a-did", T0, false, null));
        assertThrows(IllegalArgumentException.class,
            () -> list.add("did:key:z6Mk…", T0, false, null));  // wrong scheme
    }

    // ── curator import (§6.4) ─────────────────────────────────────────

    @Test void importFromCurator_taggedWithSource(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.importFromCurator(DID_ALICE, T0, false, DID_CURATOR, "harassment");
        var entry = list.get(DID_ALICE).orElseThrow();
        assertEquals(DID_CURATOR, entry.source());
        assertFalse(entry.isLocal());
    }

    @Test void importFromCurator_respectsLocalEntry(@TempDir Path tmp) {
        // Spec §6.4: "manual wyrd block entries are untouched" by curator imports.
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, "my own note");
        list.importFromCurator(DID_ALICE, T0.plusSeconds(10), false, DID_CURATOR, "curator note");

        var entry = list.get(DID_ALICE).orElseThrow();
        assertTrue(entry.isLocal(),
            "local entry must win over curator import");
        assertEquals("my own note", entry.note());
    }

    @Test void unsubscribeCurator_removesOnlyTheirEntries(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, "local");
        list.importFromCurator(DID_BOB, T0, false, DID_CURATOR, "curator");

        int removed = list.unsubscribeCurator(DID_CURATOR);
        assertEquals(1, removed);
        // Local entry untouched.
        assertTrue(list.contains(DID_ALICE));
        assertFalse(list.contains(DID_BOB));
    }

    @Test void unsubscribeCurator_noMatchReturnsZero(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, null);
        assertEquals(0, list.unsubscribeCurator(DID_CURATOR));
    }

    // ── persistence ───────────────────────────────────────────────────

    @Test void saveAndLoad_roundTrip_preemptive(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        var a = BlockList.empty(file);
        a.add(DID_ALICE, T0, false, "some note");
        a.save();

        assertTrue(Files.exists(file));
        var b = BlockList.load(file);
        assertEquals(1, b.size());
        var entry = b.get(DID_ALICE).orElseThrow();
        assertFalse(entry.revoke());
        assertTrue(entry.isLocal());
        assertEquals("some note", entry.note());
    }

    @Test void saveAndLoad_roundTrip_reactiveBlock(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        var a = BlockList.empty(file);
        a.add(DID_ALICE, T0, true, "revoked after spam");
        a.save();

        var b = BlockList.load(file);
        var entry = b.get(DID_ALICE).orElseThrow();
        assertTrue(entry.revoke());
        assertEquals("revoked after spam", entry.note());
    }

    @Test void saveAndLoad_roundTrip_curatorSource(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        var a = BlockList.empty(file);
        a.importFromCurator(DID_ALICE, T0, false, DID_CURATOR, "harassment");
        a.save();

        var b = BlockList.load(file);
        var entry = b.get(DID_ALICE).orElseThrow();
        assertEquals(DID_CURATOR, entry.source());
        assertEquals("harassment", entry.note());
    }

    @Test void saveAndLoad_multipleEntries(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        var a = BlockList.empty(file);
        a.add(DID_ALICE, T0, false, "note A");
        a.add(DID_BOB, T0.plusSeconds(10), true, null);
        a.save();

        var b = BlockList.load(file);
        assertEquals(2, b.size());
        assertTrue(b.contains(DID_ALICE));
        assertTrue(b.contains(DID_BOB));
    }

    @Test void load_returnsEmptyWhenMissing(@TempDir Path tmp) throws Exception {
        var list = BlockList.load(tmp.resolve("nonexistent"));
        assertEquals(0, list.size());
    }

    @Test void load_ignoresBlankAndCommentLines(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        Files.writeString(file,
            "# top-level comment\n"
            + "\n"
            + DID_ALICE + "\t" + T0 + "\n"
            + "# mid-comment\n"
            + DID_BOB + "\t" + T0.plusSeconds(1) + "\trevoke\n");

        var list = BlockList.load(file);
        assertEquals(2, list.size());
        assertFalse(list.get(DID_ALICE).orElseThrow().revoke());
        assertTrue(list.get(DID_BOB).orElseThrow().revoke());
    }

    @Test void load_failsOnBadTimestamp(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        Files.writeString(file, DID_ALICE + "\tnot-a-date\n");
        var ex = assertThrows(IOException.class, () -> BlockList.load(file));
        assertTrue(ex.getMessage().contains(":1:"));
    }

    @Test void load_failsOnMalformedDid(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("blocks");
        Files.writeString(file, "not-a-did\t" + T0 + "\n");
        assertThrows(IOException.class, () -> BlockList.load(file));
    }

    // ── introspection ─────────────────────────────────────────────────

    @Test void blockedDids_returnsAllDids(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_ALICE, T0, false, null);
        list.add(DID_BOB, T0, false, null);
        assertEquals(Set.of(DID_ALICE, DID_BOB), list.blockedDids());
    }

    @Test void list_preservesInsertionOrder(@TempDir Path tmp) {
        var list = BlockList.empty(tmp.resolve("blocks"));
        list.add(DID_BOB, T0, false, null);
        list.add(DID_ALICE, T0.plusSeconds(1), false, null);

        var all = list.list();
        assertEquals(DID_BOB, all.get(0).did());
        assertEquals(DID_ALICE, all.get(1).did());
    }
}
