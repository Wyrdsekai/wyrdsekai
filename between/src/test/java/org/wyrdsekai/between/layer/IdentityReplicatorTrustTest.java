package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anything on the household bus could publish on account.> (2026-09-28: a device on the Wi-Fi could
 * publish itself a steward account). Unsigned events, and events from machines not on the roster, are now
 * refused. With the transition setting WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED=true they still arrive, as
 * members only, and replicated removals and setting changes are refused. Signed replication from roster
 * machines is covered by IdentityReplicatorSigningTest.
 */
class IdentityReplicatorTrustTest {

    private AuthService auth;
    private InviteService invites;
    private IdentityReplicator replicator;          // the transition setting on
    private IdentityReplicator strictReplicator;    // the default
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = "jdbc:sqlite:file:repl-" + UUID.randomUUID() + "?mode=memory&cache=shared";
        var keep = DriverManager.getConnection(jdbc);   // keeps the in-memory database alive
        String sql;
        try (var in = AuthService.class.getResourceAsStream("/schema/sqlite-create-schema.sql")) {
            sql = new String(in.readAllBytes());
        }
        var cleaned = sql.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        for (var st : cleaned.split(";")) {
            var t = st.trim();
            if (t.isEmpty() || t.startsWith("PRAGMA")) continue;
            try (var s = keep.createStatement()) { s.execute(t); }
        }
        auth = new AuthService(jdbc);
        invites = new InviteService(jdbc);
        replicator = new IdentityReplicator(null, (s, b) -> { }, null, "local-node-0001",
            id -> Optional.empty(), List::of, auth, invites, true);
        strictReplicator = new IdentityReplicator(null, (s, b) -> { }, null, "local-node-0001",
            id -> Optional.empty(), List::of, auth, invites, false);
    }

    @Test
    void aReplicatedStewardArrivesAsAMember() throws Exception {
        var event = Map.of("userId", UUID.randomUUID().toString(), "username", "intruder",
            "passwordHash", "$2a$12$abcdefghijklmnopqrstuuYlS1v7xWb5rZ2xw3yJ0j8Qv3lGmCq2a",
            "displayName", "Intruder", "role", "steward", "sourceNodeId", "some-other-node", "timestamp", Instant.now().toString());
        replicator.apply("account.created", mapper.writeValueAsBytes(event));
        var u = auth.findUserByUsername("intruder").orElseThrow();
        assertThat(u.role()).isEqualTo("member");
    }

    @Test
    void aReplicatedRemovalIsRefused() throws Exception {
        var reg = auth.register("keeper", "correct-horse-battery", "Keeper").orElseThrow();
        replicator.apply("account.removed", mapper.writeValueAsBytes(Map.of("userId", reg.userId(),
            "sourceNodeId", "some-other-node", "timestamp", Instant.now().toString())));
        assertThat(auth.findUserByUsername("keeper")).isPresent();
    }

    @Test
    void byDefaultAnUnsignedAccountIsRefused() throws Exception {
        var event = Map.of("userId", UUID.randomUUID().toString(), "username", "intruder2",
            "passwordHash", "$2a$12$abcdefghijklmnopqrstuuYlS1v7xWb5rZ2xw3yJ0j8Qv3lGmCq2a",
            "displayName", "Intruder", "role", "steward", "sourceNodeId", "some-other-node", "timestamp", Instant.now().toString());
        strictReplicator.apply("account.created", mapper.writeValueAsBytes(event));
        assertThat(auth.findUserByUsername("intruder2")).isEmpty();
    }

    @Test
    void byDefaultAnUnsignedRemovalIsRefused() throws Exception {
        var reg = auth.register("keeper2", "correct-horse-battery", "Keeper").orElseThrow();
        strictReplicator.apply("account.removed", mapper.writeValueAsBytes(Map.of("userId", reg.userId(),
            "sourceNodeId", "some-other-node", "timestamp", Instant.now().toString())));
        assertThat(auth.findUserByUsername("keeper2")).isPresent();
    }
}
