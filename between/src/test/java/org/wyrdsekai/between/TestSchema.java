package org.wyrdsekai.between;

import org.wyrdsekai.core.persistence.AuthService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A fresh in-memory SQLite database with the shipped schema, kept alive for the test run. */
public final class TestSchema {

    private static final List<Connection> KEEP = new ArrayList<>();

    private TestSchema() {}

    public static synchronized String freshDb() throws Exception {
        var jdbc = "jdbc:sqlite:file:between-" + UUID.randomUUID() + "?mode=memory&cache=shared";
        var keep = DriverManager.getConnection(jdbc);
        KEEP.add(keep);
        String sql;
        try (var in = AuthService.class.getResourceAsStream("/schema/sqlite-create-schema.sql")) {
            sql = new String(in.readAllBytes());
        }
        var cleaned = sql.lines().filter(l -> !l.trim().startsWith("--"))
            .map(l -> l.contains("--") ? l.substring(0, l.indexOf("--")) : l)
            .reduce("", (a, b) -> a + "\n" + b);
        for (var st : cleaned.split(";")) {
            var t = st.trim();
            if (t.isEmpty() || t.startsWith("PRAGMA")) continue;
            try (var s = keep.createStatement()) {
                s.execute(t);
            } catch (Exception ignored) {
                // statements that need extensions (FTS etc.) are not needed here
            }
        }
        return jdbc;
    }
}
