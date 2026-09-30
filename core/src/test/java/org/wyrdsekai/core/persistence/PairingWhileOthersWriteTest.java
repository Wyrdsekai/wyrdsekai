package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checking a pairing code reads the challenge and then writes the device. With the read still open,
 * the write failed at once whenever another connection had written in between (SQLite in WAL mode,
 * SQLITE_BUSY_SNAPSHOT), as a login did (LoginWhileOthersWriteTest).
 */
@Tag("integration")
class PairingWhileOthersWriteTest {

    @Test
    void pairingCodesAreAcceptedWhileAnotherConnectionKeepsWriting(@TempDir Path dir) throws Exception {
        var url = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE churn (id INTEGER PRIMARY KEY, v TEXT)");
        }
        var pairing = new PairingService(url, SqlDialect.fromJdbcUrl(url), "home", "Home", "did:key:home",
            "wss://home:4223", "https://home:7443");
        var stop = new AtomicBoolean();
        var writer = new Thread(() -> {
            try (var c = DriverManager.getConnection(url);
                 var ps = c.prepareStatement("INSERT INTO churn (v) VALUES (?)")) {
                while (!stop.get()) {
                    ps.setString(1, "x");
                    ps.executeUpdate();
                    Thread.sleep(2);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        var failures = new ArrayList<String>();
        try {
            for (int i = 0; i < 60; i++) {
                try {
                    var ch = pairing.createChallenge("phone " + i, "phone", null);
                    if (pairing.verifyCode(ch.challengeId(), ch.code()).isEmpty()) failures.add("refused");
                } catch (RuntimeException e) {
                    Throwable root = e;
                    while (root.getCause() != null) root = root.getCause();
                    failures.add(root.toString());
                }
            }
        } finally {
            stop.set(true);
            writer.join(10_000);
        }
        assertThat(failures).as("pairings that failed while another connection wrote").isEmpty();
    }
}
