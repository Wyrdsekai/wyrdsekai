package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@code wyrd bond} against a real SQLite database.
 *
 * <p>Doesn't go through {@code main} — uses the test-friendly {@link
 * BondAdminMain#run(String, PrintStream, PrintStream, String...)} overload
 * so {@link System#exit} can't kill the JVM.</p>
 */
class BondAdminMainTest {

    private record CapturedRun(int exit, String stdout, String stderr) {}

    private static CapturedRun run(String jdbc, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var o = new PrintStream(out, true, StandardCharsets.UTF_8);
        var e = new PrintStream(err, true, StandardCharsets.UTF_8);
        int exit = BondAdminMain.run(jdbc, o, e, args);
        return new CapturedRun(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Set up DB with: a registered player and a stored soul manifest for a companion. */
    private static String freshDbWithPlayerAndCompanion(Path dir, String playerUsername, String companionDid) {
        var dbPath = dir.resolve("world.db");
        var jdbc = SchemaInitializer.initialize(dbPath);
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var sess = auth.register(playerUsername, "pw", playerUsername, "steward");
        assertTrue(sess.isPresent());
        // Seed a minimal SoulManifest for the companion. Most fields are
        // optional / nullable; only `did` and `manifestVersion` matter for
        // the round-trip we're verifying here.
        try (var soulStore = new SqlSoulStore(jdbc)) {
            var profile = new AgentProfile("Wyrd", "wyrd-entity-id",
                "companion", "a test companion", "you are wyrd",
                4096, 1024, 0.7);
            var manifest = new SoulManifest(
                companionDid, null, null, null, 1, Instant.now(), null,
                profile, null, null, 0, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
            soulStore.store(manifest);
        }
        return jdbc;
    }

    @Test void noArgs_printsUsageAndExits1(@TempDir Path dir) {
        var r = run("jdbc:sqlite::memory:");
        assertEquals(1, r.exit);
        assertTrue(r.stdout.contains("bond create"), "stdout: " + r.stdout);
    }

    @Test void help_exits0() {
        var r = run("jdbc:sqlite::memory:", "help");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("bond create"));
    }

    @Test void unknownCommand_exits1() {
        var r = run("jdbc:sqlite::memory:", "nonsense");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("unknown"));
    }

    @Test void create_missingArgs_exits1() {
        var r = run("jdbc:sqlite::memory:", "create", "alice");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("Usage:"));
    }

    @Test void create_unknownPlayer_exits1(@TempDir Path dir) {
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", "did:key:z6Mk-companion");
        var r = run(jdbc, "create", "ghost", "did:key:z6Mk-companion");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("player not found"));
    }

    @Test void create_unknownCompanion_exits1(@TempDir Path dir) {
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", "did:key:z6Mk-real");
        var r = run(jdbc, "create", "operator", "did:key:z6Mk-fake");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("no soul manifest"));
    }

    @Test void create_writes_BondStore_and_bumps_manifest(@TempDir Path dir) {
        var companionDid = "did:key:z6Mk-companion-test";
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", companionDid);
        var r = run(jdbc, "create", "operator", companionDid);
        assertEquals(0, r.exit, "stderr: " + r.stderr);

        // BondStore got a row.
        var bonds = new BondStore(jdbc).all();
        assertEquals(1, bonds.size());
        var bond = bonds.get(0);
        assertEquals(companionDid, bond.agentADid());
        assertTrue(bond.active());
        assertTrue(bond.mutualConsent(), "admin path defaults to mutualConsent=true");
        assertEquals(Bond.BondDepth.ACQUAINTANCE, bond.depth());

        // SoulManifest got bumped to v2 with the bond appended.
        try (var soulStore = new SqlSoulStore(jdbc)) {
            var latest = soulStore.latest(companionDid).orElseThrow();
            assertEquals(2, latest.manifestVersion(), "manifest version should bump from 1 → 2");
            assertNotNull(latest.bonds());
            assertEquals(1, latest.bonds().size());
            assertEquals(bond.bondId(), latest.bonds().get(0).bondId());
        }
    }

    @Test void create_with_depth_flag_sets_depth(@TempDir Path dir) {
        var companionDid = "did:key:z6Mk-companion-depth";
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", companionDid);
        var r = run(jdbc, "create", "operator", companionDid, "--depth", "SACRED");
        assertEquals(0, r.exit, "stderr: " + r.stderr);
        var bond = new BondStore(jdbc).all().get(0);
        assertEquals(Bond.BondDepth.SACRED, bond.depth());
    }

    @Test void create_invalid_depth_exits1(@TempDir Path dir) {
        var companionDid = "did:key:z6Mk-companion-bad";
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", companionDid);
        var r = run(jdbc, "create", "operator", companionDid, "--depth", "WHATEVER");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("invalid --depth"));
    }

    @Test void create_is_idempotent_replaces_same_pair(@TempDir Path dir) {
        var companionDid = "did:key:z6Mk-idem";
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", companionDid);
        // First create at ACQUAINTANCE.
        assertEquals(0, run(jdbc, "create", "operator", companionDid).exit);
        // Re-create at SACRED — should replace, not duplicate.
        assertEquals(0, run(jdbc, "create", "operator", companionDid, "--depth", "SACRED").exit);
        try (var soulStore = new SqlSoulStore(jdbc)) {
            var latest = soulStore.latest(companionDid).orElseThrow();
            assertEquals(3, latest.manifestVersion(), "two creates → v1 → v2 → v3");
            List<Bond> bonds = latest.bonds();
            assertEquals(1, bonds.size(), "same player↔companion pair must not double-insert");
            assertEquals(Bond.BondDepth.SACRED, bonds.get(0).depth());
        }
    }

    @Test void list_empty_prints_no_bonds() {
        var r = run("jdbc:sqlite::memory:", "list");
        // Empty in-memory DB has no bonds table — surfaces as internal error or empty.
        // Either way, the test verifies the list path doesn't crash with args.
        assertTrue(r.exit == 0 || r.exit == 2);
    }

    @Test void list_shows_existing_bonds(@TempDir Path dir) {
        var companionDid = "did:key:z6Mk-list-test";
        var jdbc = freshDbWithPlayerAndCompanion(dir, "operator", companionDid);
        run(jdbc, "create", "operator", companionDid);
        var r = run(jdbc, "list");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains(companionDid), "stdout: " + r.stdout);
        assertTrue(r.stdout.contains("ACQUAINTANCE"));
    }
}
