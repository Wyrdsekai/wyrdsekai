package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.naming.BlockList;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the naming admin CLI. Exercises the {@code run}
 * entry point (not {@code main} — we don't want {@code System.exit} to kill
 * the test JVM) with a temp data dir so the user's real
 * {@code ~/.wyrdsekai/} is never touched.
 */
class NamingAdminMainTest {

    private static final String DID_ALICE =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_BOB =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private record CapturedRun(int exit, String stdout, String stderr) {}

    private static CapturedRun run(Path dataDir, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var o = new PrintStream(out, true, StandardCharsets.UTF_8);
        var e = new PrintStream(err, true, StandardCharsets.UTF_8);
        int exit = NamingAdminMain.run(dataDir, o, e, args);
        return new CapturedRun(exit,
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    // ── usage / unknown command ───────────────────────────────────────

    @Test void noArgs_printsUsageToStderrAndExits1(@TempDir Path dir) {
        var r = run(dir);
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("zone identity") || r.stderr.contains("whoami"),
            "stderr should contain usage: " + r.stderr);
    }

    @Test void unknownCommand_exits1(@TempDir Path dir) {
        var r = run(dir, "noperinos");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("unknown"));
    }

    @Test void help_exits0WithUsageOnStdout(@TempDir Path dir) {
        var r = run(dir, "help");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("whoami"));
        assertTrue(r.stdout.contains("contacts"));
        assertTrue(r.stdout.contains("zones"));
    }

    // ── whoami ────────────────────────────────────────────────────────

    @Test void whoami_printsDidWyrdForm(@TempDir Path dir) {
        var r = run(dir, "whoami");
        assertEquals(0, r.exit);
        var did = r.stdout.strip();
        assertTrue(did.startsWith("did:wyrd:z6Mk"),
            "expected did:wyrd:z6Mk… prefix, got: " + did);
    }

    @Test void whoami_stableAcrossInvocations(@TempDir Path dir) {
        // Same data dir → same keypair on disk → same DID.
        var first = run(dir, "whoami");
        var second = run(dir, "whoami");
        assertEquals(0, first.exit);
        assertEquals(0, second.exit);
        assertEquals(first.stdout.strip(), second.stdout.strip(),
            "whoami must be stable across invocations given same data dir");
    }

    @Test void whoami_differentDirsGiveDifferentDids(@TempDir Path a, @TempDir Path b) {
        var ra = run(a, "whoami");
        var rb = run(b, "whoami");
        assertNotEquals(ra.stdout.strip(), rb.stdout.strip(),
            "fresh keypair → fresh DID");
    }

    @Test void whoami_createsIdentityFileIfMissing(@TempDir Path dir) {
        assertFalse(Files.exists(dir.resolve("node-identity.json")));
        var r = run(dir, "whoami");
        assertEquals(0, r.exit);
        assertTrue(Files.exists(dir.resolve("node-identity.json")),
            "whoami must materialise node-identity.json (idempotent with server boot)");
    }

    // ── contacts ──────────────────────────────────────────────────────

    @Test void contacts_listWhenEmpty_hintsUser(@TempDir Path dir) {
        var r = run(dir, "contacts", "list");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("no contacts yet"),
            "empty-list output should hint at `add`: " + r.stdout);
    }

    @Test void contacts_addAndList_roundTrip(@TempDir Path dir) {
        var add = run(dir, "contacts", "add", "alice", DID_ALICE, "kitchen");
        assertEquals(0, add.exit);
        assertTrue(add.stdout.contains("added contact: alice"));
        assertTrue(add.stdout.contains("verify fingerprint out-of-band"),
            "TOFU guidance must be printed: " + add.stdout);

        var list = run(dir, "contacts", "list");
        assertEquals(0, list.exit);
        assertTrue(list.stdout.contains("alice"));
        assertTrue(list.stdout.contains(DID_ALICE));
        assertTrue(list.stdout.contains("kitchen"));
    }

    @Test void contacts_add_rejectsReservedAlias(@TempDir Path dir) {
        var r = run(dir, "contacts", "add", "home", DID_ALICE);
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("reserved"),
            "reserved-alias rejection must name the reason: " + r.stderr);
    }

    @Test void contacts_add_rejectsMalformedDid(@TempDir Path dir) {
        var r = run(dir, "contacts", "add", "alice", "not-a-did");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("did") || r.stderr.contains("DID")
            || r.stderr.contains("did:wyrd"));
    }

    @Test void contacts_add_duplicateFails(@TempDir Path dir) {
        assertEquals(0, run(dir, "contacts", "add", "alice", DID_ALICE).exit);
        var second = run(dir, "contacts", "add", "alice", DID_BOB);
        assertEquals(1, second.exit);
        assertTrue(second.stderr.contains("already"));
    }

    @Test void contacts_remove_worksAndPersists(@TempDir Path dir) {
        run(dir, "contacts", "add", "alice", DID_ALICE);
        var rm = run(dir, "contacts", "remove", "alice");
        assertEquals(0, rm.exit);
        assertTrue(rm.stdout.contains("removed"));

        var list = run(dir, "contacts", "list");
        assertFalse(list.stdout.contains("alice"));
    }

    @Test void contacts_remove_missingFails(@TempDir Path dir) {
        var r = run(dir, "contacts", "remove", "nobody");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("no contact named"));
    }

    @Test void contacts_rename_preservesDefaultLabel(@TempDir Path dir) {
        run(dir, "contacts", "add", "alice", DID_ALICE, "kitchen");
        var ren = run(dir, "contacts", "rename", "alice", "alice-2");
        assertEquals(0, ren.exit);

        var list = run(dir, "contacts", "list");
        assertFalse(list.stdout.contains("alice  did"),
            "old alias must be gone from list: " + list.stdout);
        assertTrue(list.stdout.contains("alice-2"));
        assertTrue(list.stdout.contains("kitchen"));
    }

    @Test void contacts_update_rotatesDid(@TempDir Path dir) {
        run(dir, "contacts", "add", "alice", DID_ALICE, "kitchen");
        var upd = run(dir, "contacts", "update", "alice", DID_BOB);
        assertEquals(0, upd.exit);

        var list = run(dir, "contacts", "list");
        assertTrue(list.stdout.contains(DID_BOB));
        assertFalse(list.stdout.contains(DID_ALICE));
        // Default label must carry over after rotation.
        assertTrue(list.stdout.contains("kitchen"));
    }

    @Test void contacts_unknownSubcommand_printsUsage(@TempDir Path dir) {
        var r = run(dir, "contacts", "delete");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("Usage") || r.stderr.contains("Unknown"));
    }

    // ── zones ─────────────────────────────────────────────────────────

    @Test void zones_listWhenEmpty_hintsUser(@TempDir Path dir) {
        var r = run(dir, "zones", "list");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("no zones yet"));
    }

    @Test void zones_createAndList_marksDefault(@TempDir Path dir) {
        assertEquals(0, run(dir, "zones", "create", "kitchen").exit);
        assertEquals(0, run(dir, "zones", "create", "garage").exit);

        var list = run(dir, "zones", "list");
        assertEquals(0, list.exit);
        assertTrue(list.stdout.contains("kitchen"));
        assertTrue(list.stdout.contains("garage"));
        assertTrue(list.stdout.contains("(default)"),
            "first-registered zone must be marked default: " + list.stdout);

        // Marker is on 'kitchen', not 'garage'
        int kitchenIdx = list.stdout.indexOf("kitchen");
        int garageIdx = list.stdout.indexOf("garage");
        int defaultIdx = list.stdout.indexOf("(default)");
        assertTrue(kitchenIdx < defaultIdx && defaultIdx < garageIdx,
            "default marker should be on kitchen line, before garage: " + list.stdout);
    }

    @Test void zones_create_rejectsReserved(@TempDir Path dir) {
        var r = run(dir, "zones", "create", "home");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("reserved"));
    }

    @Test void zones_create_rejectsMalformed(@TempDir Path dir) {
        var r = run(dir, "zones", "create", "Kitchen");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("valid") || r.stderr.contains("lowercase"));
    }

    @Test void zones_remove_works(@TempDir Path dir) {
        run(dir, "zones", "create", "kitchen");
        assertEquals(0, run(dir, "zones", "remove", "kitchen").exit);
        var list = run(dir, "zones", "list");
        assertFalse(list.stdout.contains("kitchen"));
    }

    @Test void zones_persistAcrossInvocations(@TempDir Path dir) {
        run(dir, "zones", "create", "kitchen");
        // Completely fresh invocation — must read file from disk.
        var list = run(dir, "zones", "list");
        assertTrue(list.stdout.contains("kitchen"));
    }

    @Test void zones_addAlias_worksLikeCreate(@TempDir Path dir) {
        // "add" alias accepted for muscle-memory users.
        assertEquals(0, run(dir, "zones", "add", "kitchen").exit);
        assertTrue(run(dir, "zones", "list").stdout.contains("kitchen"));
    }

    @Test void zones_rmAlias_worksLikeRemove(@TempDir Path dir) {
        run(dir, "zones", "create", "kitchen");
        assertEquals(0, run(dir, "zones", "rm", "kitchen").exit);
    }

    // ── blocks ────────────────────────────────────────────────────────

    @Test void block_add_persists(@TempDir Path dir) {
        var r = run(dir, "block", DID_ALICE);
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("blocked"));
        // Verify persisted.
        var list = run(dir, "blocks");
        assertEquals(0, list.exit);
        assertTrue(list.stdout.contains(DID_ALICE));
    }

    @Test void block_withRevokeFlag_reportsReactive(@TempDir Path dir) {
        var r = run(dir, "block", DID_ALICE, "--revoke");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("reactive"),
            "--revoke block must mention it's reactive: " + r.stdout);
    }

    @Test void block_withNote_persistsNote(@TempDir Path dir) {
        run(dir, "block", DID_ALICE, "--note", "abusive — warned by charlie");
        var list = run(dir, "blocks");
        assertTrue(list.stdout.contains("abusive"));
    }

    @Test void block_missingDid_fails(@TempDir Path dir) {
        var r = run(dir, "block");
        assertEquals(1, r.exit);
    }

    @Test void block_malformedDid_fails(@TempDir Path dir) {
        var r = run(dir, "block", "not-a-did");
        assertEquals(1, r.exit);
    }

    @Test void unblock_removesEntry(@TempDir Path dir) {
        run(dir, "block", DID_ALICE);
        var rm = run(dir, "unblock", DID_ALICE);
        assertEquals(0, rm.exit);
        var list = run(dir, "blocks");
        assertFalse(list.stdout.contains(DID_ALICE));
    }

    @Test void unblock_unknownFails(@TempDir Path dir) {
        var r = run(dir, "unblock", DID_ALICE);
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("no block"));
    }

    @Test void blocks_empty_hintsToAdd(@TempDir Path dir) {
        var r = run(dir, "blocks");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("no blocks"));
    }

    // ── safety ────────────────────────────────────────────────────────

    @Test void safety_subscribe_and_list(@TempDir Path dir) {
        var sub = run(dir, "safety", "subscribe", DID_BOB);
        assertEquals(0, sub.exit);
        var list = run(dir, "safety", "list");
        assertTrue(list.stdout.contains(DID_BOB));
    }

    @Test void safety_subscribeRejectsNonDid(@TempDir Path dir) {
        var r = run(dir, "safety", "subscribe", "not-a-did");
        assertEquals(1, r.exit);
    }

    @Test void safety_subscribeIdempotent(@TempDir Path dir) throws Exception {
        run(dir, "safety", "subscribe", DID_BOB);
        run(dir, "safety", "subscribe", DID_BOB);
        // Only one entry in the file — no duplicates.
        var file = dir.resolve("safety-subscriptions");
        var count = Files.readAllLines(file).stream()
            .filter(l -> l.strip().equals(DID_BOB)).count();
        assertEquals(1, count);
    }

    @Test void safety_unsubscribeRemovesImportedBlocks(@TempDir Path dir) throws Exception {
        // Seed a curator-sourced block entry via BlockList directly (CLI
        // import isn't wired yet — spec §5 DHT).
        var blocks = BlockList.load(dir.resolve("blocks"));
        blocks.importFromCurator(DID_ALICE, Instant.now(), false, DID_BOB, "imported");
        blocks.save();
        // Also add a local block — must survive the unsubscribe.
        run(dir, "block", DID_BOB);

        run(dir, "safety", "subscribe", DID_BOB);
        var unsub = run(dir, "safety", "unsubscribe", DID_BOB);
        assertEquals(0, unsub.exit);
        assertTrue(unsub.stdout.contains("removed") || unsub.stdout.contains("imported"));

        // Local block on DID_BOB still there; curator's DID_ALICE block gone.
        var list = run(dir, "blocks");
        assertTrue(list.stdout.contains(DID_BOB), "local block must survive curator unsubscribe");
        assertFalse(list.stdout.contains(DID_ALICE), "curator-imported block must be dropped");
    }

    @Test void safety_list_emptyByDefault(@TempDir Path dir) {
        var r = run(dir, "safety", "list");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("no curator"));
    }

    // ── discover ──────────────────────────────────────────────────────

    /**
     * Helper: run {@code discover} against an unused port — expect exit 2 with
     * a connection-failure message. This tests the CLI's error path without
     * standing up a real server in the test JVM.
     */
    private static CapturedRun runDiscoverUnreachable(Path dir, String... extra) {
        var args = new ArrayList<String>();
        args.add("discover");
        for (var e : extra) args.add(e);

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var o = new PrintStream(out, true, StandardCharsets.UTF_8);
        var e = new PrintStream(err, true, StandardCharsets.UTF_8);

        // Redirect discover to a guaranteed-unreachable URL via system
        // property (NamingAdminMain checks the property before env). Port
        // 1 is reserved and no ordinary service binds it.
        var prior = System.getProperty("wyrdsekai.api.url");
        System.setProperty("wyrdsekai.api.url", "http://127.0.0.1:1");
        try {
            int exit = NamingAdminMain.run(dir, o, e, args.toArray(new String[0]));
            return new CapturedRun(exit,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
        } finally {
            if (prior == null) System.clearProperty("wyrdsekai.api.url");
            else System.setProperty("wyrdsekai.api.url", prior);
        }
    }

    @Test void discover_help_exits0(@TempDir Path dir) {
        var r = run(dir, "discover", "--help");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.toLowerCase().contains("usage"));
    }

    @Test void discover_unreachableServer_exits2WithClearMessage(@TempDir Path dir) {
        var r = runDiscoverUnreachable(dir);
        assertEquals(2, r.exit,
            "expected connection-failure exit code; stderr=" + r.stderr);
        assertTrue(r.stderr.contains("cannot reach server"),
            "stderr must explain the failure: " + r.stderr);
    }

    @Test void discover_invalidLimit_exits1(@TempDir Path dir) {
        var r = run(dir, "discover", "--limit", "not-a-number");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.toLowerCase().contains("integer"));
    }

    @Test void discover_listedInTopLevelUsage(@TempDir Path dir) {
        var r = run(dir, "help");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("discover"),
            "top-level usage should mention discover: " + r.stdout);
    }
}
