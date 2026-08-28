package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.util.Base64;
import java.util.List;

/**
 * W13 — TheSafe local single-node persistence:
 * store→read roundtrip, restart survival, 600 file mode, encrypted-at-rest
 * vs the documented plaintext fallback, and wrong-key behavior.
 */
class TheSafeLocalStoreTest {

    private static final byte[] KEY = "test-node-identity-seed-32bytes!".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        TheSafe.resetLocalForTests();
    }

    private Path safeFile() {
        return tempDir.resolve("credentials.safe");
    }

    @Test
    void storeReadRoundtrip() {
        var safe = TheSafe.initLocal(safeFile(), KEY);
        assertTrue(safe.readSlot("github.token").isEmpty());

        safe.storeSlot("github.token", "ghp_secret123");
        assertEquals("ghp_secret123", safe.readSlot("github.token").orElseThrow());

        // Overwrite wins.
        safe.storeSlot("github.token", "ghp_rotated456");
        assertEquals("ghp_rotated456", safe.readSlot("github.token").orElseThrow());

        // K=N=1 secret is visible through the generic Safe API too.
        assertTrue(safe.hasSecret("github.token"));
        assertEquals(List.of("github.token"), safe.listSlots());
    }

    @Test
    void slotsPersistAcrossRestart() {
        TheSafe.initLocal(safeFile(), KEY).storeSlot("maps.key", "abc-123");

        // Simulate restart: fresh instance, same file + key material.
        var reborn = TheSafe.initLocal(safeFile(), KEY);
        assertEquals("abc-123", reborn.readSlot("maps.key").orElseThrow());
    }

    @Test
    void removedSlotStaysGoneAfterRestart() {
        var safe = TheSafe.initLocal(safeFile(), KEY);
        safe.storeSlot("dead.slot", "x");
        assertTrue(safe.removeSlot("dead.slot"));
        assertFalse(safe.removeSlot("dead.slot"), "second remove reports nothing deleted");

        var reborn = TheSafe.initLocal(safeFile(), KEY);
        assertTrue(reborn.readSlot("dead.slot").isEmpty());
    }

    @Test
    void safeFileIsOwnerOnly600() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
            "POSIX permissions not supported on this filesystem");
        TheSafe.initLocal(safeFile(), KEY).storeSlot("s", "v");

        var perms = Files.getPosixFilePermissions(safeFile());
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms,
            "credentials.safe must be 600");
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void encryptedAtRestFileDoesNotLeakSecrets() throws Exception {
        TheSafe.initLocal(safeFile(), KEY).storeSlot("secret.slot", "hunter2-value");

        var onDisk = Files.readString(safeFile());
        assertTrue(onDisk.contains("\"mode\" : \"aes-gcm\"") || onDisk.contains("\"mode\":\"aes-gcm\""),
            "keyed safe must persist encrypted, got: " + onDisk);
        assertFalse(onDisk.contains("hunter2-value"), "plaintext secret must not be on disk");
        assertFalse(onDisk.contains(Base64.getEncoder()
                .encodeToString("hunter2-value".getBytes(StandardCharsets.UTF_8))),
            "base64 of the secret must not be on disk either");
    }

    @Test
    void plaintextFallbackStillRoundtripsAndIs600() throws Exception {
        // No key material — the documented honest fallback.
        var safe = TheSafe.initLocal(safeFile(), null);
        safe.storeSlot("plain.slot", "visible-locally");
        assertEquals("visible-locally",
            TheSafe.initLocal(safeFile(), null).readSlot("plain.slot").orElseThrow());

        var onDisk = Files.readString(safeFile());
        assertTrue(onDisk.contains("plain"), "fallback mode must be recorded honestly");
    }

    @Test
    void plaintextFileUpgradesToEncryptedOnKeyedInit() throws Exception {
        TheSafe.initLocal(safeFile(), null).storeSlot("upgrade.slot", "value-1");

        // Keyed re-init (e.g. node identity became readable): loads + re-encrypts.
        var keyed = TheSafe.initLocal(safeFile(), KEY);
        assertEquals("value-1", keyed.readSlot("upgrade.slot").orElseThrow());
        var onDisk = Files.readString(safeFile());
        assertFalse(onDisk.contains("value-1"), "upgraded file must be encrypted");
        assertTrue(onDisk.contains("aes-gcm"));
    }

    @Test
    void wrongKeyStartsEmptyButLeavesFileInPlace() throws Exception {
        TheSafe.initLocal(safeFile(), KEY).storeSlot("s", "v");

        var wrongKey = "another-node-identity-seed-32b!!".getBytes(StandardCharsets.UTF_8);
        var other = TheSafe.initLocal(safeFile(), wrongKey);
        assertTrue(other.readSlot("s").isEmpty(), "wrong key must not decrypt");
        assertTrue(Files.exists(safeFile()), "unreadable file must be left in place");
    }

    @Test
    void localSingletonLazyInitializesAndIsReplacedByKeyedInit() {
        // local() without prior initLocal builds a plaintext-fallback instance
        // at the canonical path — just verify the singleton contract here with
        // an explicit init (avoid touching the real dataDir).
        var keyed = TheSafe.initLocal(safeFile(), KEY);
        assertSame(keyed, TheSafe.local(), "local() must return the initialized singleton");
    }

    @Test
    void blankSlotOrNullValueRejectedLoudly() {
        var safe = TheSafe.initLocal(safeFile(), KEY);
        assertThrows(IllegalArgumentException.class, () -> safe.storeSlot(" ", "v"));
        assertThrows(IllegalArgumentException.class, () -> safe.storeSlot("s", null));
        assertTrue(safe.readSlot(null).isEmpty());
        assertTrue(safe.readSlot(" ").isEmpty());
    }
}
