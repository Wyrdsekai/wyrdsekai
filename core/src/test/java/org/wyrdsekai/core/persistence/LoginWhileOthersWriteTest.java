package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A login reads the account and then writes the session. The world database is in WAL mode, and a
 * login that kept its read open while writing failed at once ("Login failed", SQLITE_BUSY_SNAPSHOT)
 * whenever another connection had written in between: a phone signing in right after it was paired
 * (2026-09-28 rehearsal). Before the fix every login here failed.
 */
@Tag("integration")
class LoginWhileOthersWriteTest {

    @Test
    void loginsSucceedWhileAnotherConnectionKeepsWriting(@TempDir Path dir) throws Exception {
        var url = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE churn (id INTEGER PRIMARY KEY, v TEXT)");
        }
        var auth = new AuthService(url);
        assertThat(auth.register("ada", "a-long-password", "Ada")).isPresent();

        var stop = new AtomicBoolean();
        var writer = new Thread(() -> {
            try (var c = DriverManager.getConnection(url);
                 var ps = c.prepareStatement("INSERT INTO churn (v) VALUES (?)")) {
                // Short writes with pauses, like the node's own (a pairing, a memory, a session).
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

        var failures = new ConcurrentLinkedQueue<String>();
        var pool = Executors.newFixedThreadPool(4);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            runs.add(pool.submit(() -> {
                try {
                    assertThat(auth.login("ada", "a-long-password")).isPresent();
                } catch (Throwable t) {
                    var root = t;
                    while (root.getCause() != null) root = root.getCause();
                    failures.add(root.toString());
                }
            }));
        }
        for (var r : runs) r.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        stop.set(true);
        writer.join(10_000);

        assertThat(failures).as("logins that failed while another connection wrote").isEmpty();
    }
}
