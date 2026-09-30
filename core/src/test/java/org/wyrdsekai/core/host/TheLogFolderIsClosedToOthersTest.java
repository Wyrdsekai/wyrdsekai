package org.wyrdsekai.core.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package's log folder was 755 with 644 files, outside the data directory closed to other
 * users, and at DEBUG the log carries people's words (2026-09-29).
 */
class TheLogFolderIsClosedToOthersTest {

    @Test
    void otherUsersLoseTheFolderAndTheFiles(@TempDir Path tmp) throws Exception {
        var logs = Files.createDirectory(tmp.resolve("logs"));
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwxr-xr-x"));
        var log = Files.writeString(logs.resolve("wyrdsekai.log"), "Tool call raw content: ...");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-r--r--"));

        Principals.closeLogFolder(logs);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(logs))).isEqualTo("rwxr-x---");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log))).isEqualTo("rw-r-----");
    }

    @Test
    void aMissingFolderIsLeftAlone(@TempDir Path tmp) {
        Principals.closeLogFolder(tmp.resolve("nowhere"));
        assertThat(Files.exists(tmp.resolve("nowhere"))).isFalse();
    }
}
