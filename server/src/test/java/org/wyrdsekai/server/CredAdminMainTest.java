package org.wyrdsekai.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.room.TheSafe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import java.util.TreeSet;

/**
 * {@code wyrd cred set|get|list|unset} — the CLI over the credential Safe.
 * Values ride stdin (the no-echo path — tests pipe it), never argv; reads
 * never reveal the stored value.
 */
class CredAdminMainTest {

    private record CapturedRun(int exit, String stdout, String stderr) {}

    @AfterEach
    void resetSafe() {
        TheSafe.resetLocalForTests();
    }

    private static CapturedRun run(Path dataDir, String stdin, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        int exit = CredAdminMain.run(dataDir, in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), args);
        return new CapturedRun(exit, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    @Test void set_get_list_unset_roundtrip(@TempDir Path dir) {
        var set = run(dir, "hunter2-token\n", "set", "github.token");
        assertEquals(0, set.exit(), set.stderr());
        assertTrue(set.stdout().contains("'github.token' stored"), set.stdout());
        assertTrue(set.stdout().contains("wyrd restart"), set.stdout());

        var get = run(dir, "", "get", "github.token");
        assertEquals(0, get.exit(), get.stderr());
        assertTrue(get.stdout().contains("SET"));

        var list = run(dir, "", "list");
        assertEquals(0, list.exit());
        assertTrue(list.stdout().contains("github.token"));

        var unset = run(dir, "", "unset", "github.token");
        assertEquals(0, unset.exit(), unset.stderr());

        var gone = run(dir, "", "get", "github.token");
        assertEquals(1, gone.exit());
        assertTrue(gone.stdout().contains("(not set)"));
    }

    @Test void value_arrives_via_stdin_and_is_never_echoed(@TempDir Path dir) {
        var secret = "s3cr3t-value-abc";
        var set = run(dir, secret + "\n", "set", "maps.key");
        assertEquals(0, set.exit(), set.stderr());
        assertFalse(set.stdout().contains(secret), "set must not echo the value");
        assertFalse(set.stderr().contains(secret), "set must not echo the value");

        // get / list never reveal it either.
        var get = run(dir, "", "get", "maps.key");
        assertFalse(get.stdout().contains(secret));
        var list = run(dir, "", "list");
        assertFalse(list.stdout().contains(secret));
    }

    @Test void stored_value_is_readable_through_the_safe(@TempDir Path dir) {
        run(dir, "roundtrip-value\n", "set", "svc.token");
        // Fresh init from disk (what the server does at boot) sees the slot.
        TheSafe.resetLocalForTests();
        var get = run(dir, "", "get", "svc.token");
        assertEquals(0, get.exit(), get.stderr());
        assertTrue(get.stdout().contains("SET"));
    }

    @Test void safe_file_is_created_owner_only(@TempDir Path dir) throws Exception {
        run(dir, "x-value\n", "set", "a.slot");
        var safeFile = dir.resolve("credentials.safe");
        assertTrue(Files.isRegularFile(safeFile));
        try {
            var perms = Files.getPosixFilePermissions(safeFile);
            assertEquals("[OWNER_READ, OWNER_WRITE]",
                new TreeSet<>(perms).toString());
        } catch (UnsupportedOperationException nonPosix) {
            // Windows: TheSafe falls back to owner-only File flags; nothing to assert.
        }
        // Encrypted at rest: the raw file must not contain the plaintext value.
        var raw = Files.readString(safeFile, StandardCharsets.UTF_8);
        assertFalse(raw.contains("x-value"), "value must not be on disk in the clear");
    }

    // ── #29: the safe must end up owned by the zone's service user ──────────

    @Test void dataDirOwner_prefers_world_db_owner(@TempDir Path dir) throws Exception {
        // world.db present → its owner is the service user (the server wrote it).
        Files.writeString(dir.resolve("world.db"), "");
        var owner = CredAdminMain.dataDirOwner(dir);
        assertNotNull(owner);
        assertEquals(Files.getOwner(dir.resolve("world.db")).getName(), owner.getName());
    }

    @Test void dataDirOwner_falls_back_to_dir_owner(@TempDir Path dir) throws Exception {
        // No world.db yet (fresh install) → the data dir's owner stands in.
        var owner = CredAdminMain.dataDirOwner(dir);
        assertNotNull(owner);
        assertEquals(Files.getOwner(dir).getName(), owner.getName());
    }

    @Test void dataDirOwner_null_when_nothing_statable() {
        assertNull(CredAdminMain.dataDirOwner(Path.of("/nonexistent-wyrd-data-dir-xyz")));
    }

    @Test void same_user_roundtrip_keeps_ownership(@TempDir Path dir) throws Exception {
        // Non-root path: files created by the caller stay owned by the caller
        // (the chown-back branch is root-only and must not disturb this).
        var set = run(dir, "v\n", "set", "own.slot");
        assertEquals(0, set.exit(), set.stderr());
        var me = System.getProperty("user.name");
        assertEquals(me, Files.getOwner(dir.resolve("credentials.safe")).getName());
    }

    @Test void empty_stdin_value_is_rejected(@TempDir Path dir) {
        var r = run(dir, "\n", "set", "empty.slot");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("no value"));
        assertEquals(1, run(dir, "", "get", "empty.slot").exit());
    }

    @Test void unset_missing_slot_reports_not_set(@TempDir Path dir) {
        var r = run(dir, "", "unset", "ghost.slot");
        assertEquals(1, r.exit());
        assertTrue(r.stdout().contains("(not set)"));
    }

    @Test void no_args_prints_usage(@TempDir Path dir) {
        var r = run(dir, "");
        assertEquals(1, r.exit());
        assertTrue(r.stdout().contains("wyrd cred"));
    }

    @Test void missing_slot_arg_is_user_error(@TempDir Path dir) {
        assertEquals(1, run(dir, "v\n", "set").exit());
        assertEquals(1, run(dir, "", "get").exit());
        assertEquals(1, run(dir, "", "unset").exit());
    }

    @Test void unknown_subcommand_fails(@TempDir Path dir) {
        var r = run(dir, "", "steal");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("unknown cred command"));
    }
}
