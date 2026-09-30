package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** node-identity.json holds the node's private keys: readable by the node's user only (W6 item 17). */
class NodeIdentityFileModeTest {

    private static String mode(Path f) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(f));
    }

    @Test
    void aNewIdentityIsWrittenPrivate(@TempDir Path data) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var f = data.resolve("node-identity.json");
        NodeIdentity.loadOrGenerate(f);
        assertThat(mode(f)).isEqualTo("rw-------");
        assertThat(Files.exists(data.resolve("node-identity.json.tmp"))).isFalse();
    }

    @Test
    void anOlderWorldReadableIdentityIsMadePrivateOnLoad(@TempDir Path data) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var f = data.resolve("node-identity.json");
        var id = NodeIdentity.loadOrGenerate(f).nodeId();
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(NodeIdentity.loadOrGenerate(f).nodeId()).isEqualTo(id);
        assertThat(mode(f)).isEqualTo("rw-------");
    }
}
