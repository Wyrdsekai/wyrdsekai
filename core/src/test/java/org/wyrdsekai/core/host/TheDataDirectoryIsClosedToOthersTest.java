package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Audit W4 (2026-09-28): with per-being principals off ("hands shared") the data directory
 * kept the package's 755 and the umask's 644 files, so any local user could read the record,
 * the souls and the backups. It is closed to others now, whatever the hands.
 */
class TheDataDirectoryIsClosedToOthersTest {

    @AfterEach
    void tearDown() {
        Principals.resetForTests();
    }

    private static String mode(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    private static void set(Path p, String mode) throws Exception {
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
    }

    @Test
    @DisplayName("hands shared: everything but the models is closed to others, and nothing can be listed")
    void sharedHandsCloseTheDirectory(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data.resolve("souls"));
        Files.createDirectories(data.resolve("backups"));
        Files.createDirectories(data.resolve("models"));
        Files.createDirectories(data.resolve("coding-workspaces"));
        Files.writeString(data.resolve("world.db"), "the record");
        set(data, "rwxr-xr-x");
        set(data.resolve("world.db"), "rw-r--r--");
        for (var d : new String[] {"souls", "backups", "models", "coding-workspaces"}) set(data.resolve(d), "rwxr-xr-x");

        Principals.init(data);   // a test JVM is not a root service with the wrapper: hands shared
        assertFalse(Principals.enabled());

        assertEquals("rwx--x--x", mode(data), "search only: the llama unit reaches models/, nobody lists");
        assertEquals("rw-r-----", mode(data.resolve("world.db")));
        assertEquals("rwxr-x---", mode(data.resolve("souls")));
        assertEquals("rwxr-x---", mode(data.resolve("backups")));
        assertEquals("rwxr-x---", mode(data.resolve("coding-workspaces")), "no being needs it with the hands shared");
        assertEquals("rwxr-xr-x", mode(data.resolve("models")), "the unprivileged llama unit still reads the models");
    }

    @Test
    @DisplayName("hands per being again: the three places a being's tool needs are opened back to them")
    void principalsReopenTheirPlaces(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data.resolve("coding-workspaces"));
        set(data.resolve("coding-workspaces"), "rwxr-x---");
        var cg = dir.resolve("cg");
        Files.createDirectories(cg);
        Files.writeString(cg.resolve("cgroup.subtree_control"), "");
        Principals.configureForTests(data, cg, dir.resolve("w"),
            (argv, t) -> new HostHand.ExecResult(0, "", "", false));
        Principals.openTraverse();
        assertEquals("rwxr-xr-x", mode(data.resolve("coding-workspaces")));
    }
}
