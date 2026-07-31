package org.wyrdsekai.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class StateDumpMainTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("wyrdsekai.dataDir");
    }

    @Test
    void emitsValidJsonWithAllSections(@TempDir Path tmp) throws Exception {
        seedFixture(tmp);
        System.setProperty("wyrdsekai.dataDir", tmp.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            StateDumpMain.main(new String[]{});
        } finally {
            System.setOut(prevOut);
        }

        JsonNode root = Json.mapper().readTree(out.toString());
        assertNotNull(root.get("meta"));
        assertNotNull(root.get("node"));
        assertNotNull(root.get("config"));
        assertNotNull(root.get("database"));
        assertNotNull(root.get("souls"));
        assertNotNull(root.get("contacts"));
        assertNotNull(root.get("filesystem"));
        assertNotNull(root.get("fragmentation"));

        assertEquals(tmp.toString(), root.path("meta").path("data_dir").asText());
        assertTrue(root.path("node").path("present").asBoolean());
        assertEquals("test-node-id", root.path("node").path("node_id").asText());
        assertTrue(root.path("database").path("present").asBoolean());
        assertEquals(2, root.path("database").path("tables").path("users").path("rows").asInt());
        assertEquals(1, root.path("database").path("tables").path("bilateral_agreements").path("rows").asInt());
    }

    @Test
    void summaryContainsFragmentationAlerts(@TempDir Path tmp) throws Exception {
        seedFixture(tmp);
        System.setProperty("wyrdsekai.dataDir", tmp.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            StateDumpMain.main(new String[]{"--summary"});
        } finally {
            System.setOut(prevOut);
        }
        String s = out.toString();
        assertTrue(s.contains("Fragmentation"), "summary should call out fragmentation");
        assertTrue(s.contains("voice_profiles"), "voice_profiles fragmentation should appear");
        assertTrue(s.contains("companion_did"), "companion_did fragmentation should appear");
        assertTrue(s.contains("F7b"), "summary should reference F7b phase numbers");
    }

    @Test
    void writesToOutFileWhenRequested(@TempDir Path tmp) throws Exception {
        seedFixture(tmp);
        System.setProperty("wyrdsekai.dataDir", tmp.toString());
        Path outFile = tmp.resolve("state.json");

        StateDumpMain.main(new String[]{"--out", outFile.toString()});

        assertTrue(Files.exists(outFile));
        JsonNode root = Json.mapper().readTree(outFile.toFile());
        assertNotNull(root.get("database"));
    }

    @Test
    void masksSecretsInConfigOutput(@TempDir Path tmp) throws Exception {
        seedFixture(tmp);
        Files.writeString(tmp.resolve("wyrdsekai.conf"), """
            node.name = "test-node"
            relay.token = "supersecrettoken12345"
            api.key = "another-secret-9876"
            """);
        System.setProperty("wyrdsekai.dataDir", tmp.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            StateDumpMain.main(new String[]{});
        } finally {
            System.setOut(prevOut);
        }
        String json = out.toString();
        assertFalse(json.contains("supersecrettoken12345"),
            "raw token must not appear in dump output");
        assertFalse(json.contains("another-secret-9876"),
            "raw secret must not appear in dump output");
        assertTrue(json.contains("test-node"), "non-secret values still emit");
    }

    @Test
    void doesNotEmitEncryptedPrivateKey(@TempDir Path tmp) throws Exception {
        seedFixture(tmp);
        System.setProperty("wyrdsekai.dataDir", tmp.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            StateDumpMain.main(new String[]{});
        } finally {
            System.setOut(prevOut);
        }
        String json = out.toString();
        assertFalse(json.contains("encryptedPrivateKey"),
            "encryptedPrivateKey field must not appear");
        assertFalse(json.contains("FAKEENCRYPTEDPRIVATEKEY"),
            "encrypted private key value must not appear");
    }

    // ── Fixture seeder ────────────────────────────────────────────────────

    private static void seedFixture(Path tmp) throws Exception {
        // node-identity.json — public_key is fair game, encryptedPrivateKey is not.
        Files.writeString(tmp.resolve("node-identity.json"), """
            {
              "nodeId": "test-node-id",
              "publicKey": "FAKEPUBLICKEY",
              "encryptedPrivateKey": "FAKEENCRYPTEDPRIVATEKEY",
              "keyFormat": "PKCS8",
              "keyDerivation": {
                "algorithm": "PBKDF2WithHmacSHA256",
                "iterations": 100000,
                "salt": "FAKESALT"
              }
            }
            """);

        // contacts file
        Files.writeString(tmp.resolve("contacts"), """
            # comment
            beta\tdid:wyrd:z6Mkfake\tBeta zone
            """);

        // souls subdir with a CfC weights file (no real manifests on this fixture).
        Path soulsDir = tmp.resolve("souls");
        Files.createDirectories(soulsDir);
        Files.writeString(soulsDir.resolve("companion-test.did"), "did:key:z6Mkfaketest");
        Files.writeString(soulsDir.resolve("companion-test_cfc.json"),
            "{\"w1\":[1,2,3],\"b1\":[0,0]}");

        // world.db with a couple of state tables seeded.
        Path dbPath = tmp.resolve("world.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users (username TEXT, role TEXT, created_at TEXT)");
            s.execute("INSERT INTO users VALUES ('alice', 'steward', '2026-01-01')");
            s.execute("INSERT INTO users VALUES ('bob', 'member', '2026-01-02')");
            s.execute("CREATE TABLE bilateral_agreements ("
                + "partner_zone TEXT, status TEXT, agreed_at TEXT)");
            s.execute("INSERT INTO bilateral_agreements VALUES ('beta', 'active', '2026-02-01')");
            s.execute("CREATE TABLE bonds (bond_id TEXT, holder_user_id TEXT, "
                + "companion_did TEXT, depth INTEGER)");
            s.execute("CREATE TABLE soul_manifests ("
                + "did TEXT, version INTEGER, forged_at TEXT, content_hash TEXT, "
                + "manifest_json TEXT, archived INTEGER, archive_reason TEXT, "
                + "PRIMARY KEY(did, version))");
            s.execute("INSERT INTO soul_manifests VALUES "
                + "('did:key:z6Mkfaketest', 1, '2026-01-01', 'h1', '{}', 0, NULL)");
            s.execute("CREATE TABLE foreign_identities (did TEXT, home_zone TEXT)");
            s.execute("CREATE TABLE residency (did TEXT, zone_id TEXT, role TEXT, "
                + "granted_at INTEGER, grantor TEXT, study_room_id TEXT, "
                + "PRIMARY KEY(did, zone_id))");
            s.execute("CREATE TABLE invites (intended_name TEXT, role TEXT, "
                + "expires_at TEXT, redeemed_at TEXT, created_by TEXT)");
            s.execute("CREATE TABLE grants (capability TEXT, revoked_at TEXT)");
            s.execute("CREATE TABLE zone_manifests (zone_id TEXT, version INTEGER, "
                + "published_at TEXT)");
            s.execute("CREATE TABLE transit_tokens (token TEXT, used INTEGER)");
            s.execute("CREATE TABLE household_config (key TEXT, value TEXT)");
            s.execute("CREATE TABLE paired_devices (user_id TEXT, device_label TEXT, "
                + "paired_at TEXT)");
        }
    }
}
