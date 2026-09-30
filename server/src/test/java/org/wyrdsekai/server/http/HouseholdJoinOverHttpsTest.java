package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.crypto.HouseholdTls;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.server.RelayNkeyAdminMain;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `wyrd join <hub> --household-key <key>.<home_ca_fp>` end to end ( W2): the real
 * command, run as its own process with its own data folder, reaches a hub serving HTTPS with the
 * household certificate, sends the key only after the hub's certificate matched, and saves the bus
 * login and CA the hub issued. With another home's fingerprint it sends nothing and saves nothing.
 */
@Tag("integration")
class HouseholdJoinOverHttpsTest {

    private Javalin hubApp;
    private Object keepAlive;

    @AfterEach
    void stop() {
        if (hubApp != null) hubApp.stop();
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private record Hub(int port, String key, HouseholdBus bus, String fp) {}

    private Hub startHub(Path hubDir) throws Exception {
        var jdbcUrl = "jdbc:sqlite:file:hj-" + UUID.randomUUID() + "?mode=memory&cache=shared";
        var conn = DriverManager.getConnection(jdbcUrl);
        keepAlive = conn;
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE households(household_id TEXT PRIMARY KEY, public_key BLOB NOT NULL, "
                + "fingerprint TEXT NOT NULL, did_key TEXT, x25519_public_key BLOB, registered_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL DEFAULT (unixepoch()))");
        }
        var pairing = new PairingService(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl), "hub", "Hub", "",
            "nats://127.0.0.1:4222", "https://127.0.0.1:7443");
        pairing.initSchema();
        var bus = HouseholdBus.open(hubDir);
        pairing.useHouseholdBus(bus);
        var key = pairing.generateHouseholdKey();
        var identity = NodeIdentity.loadOrGenerate(hubDir.resolve("node-identity.json"));
        var routes = new HouseholdJoinRoutes(pairing, new HouseholdStore(jdbcUrl), identity, () -> "127.0.0.1");
        int tlsPort = freePort();
        var config = ConfigFactory.parseMap(Map.of("wyrdsekai.tls.port", tlsPort));
        hubApp = Javalin.create(cfg -> {
            TlsConfig.configure(cfg, config, hubDir);
            routes.register(cfg.routes);
        });
        hubApp.start("127.0.0.1", freePort());
        return new Hub(tlsPort, key, bus, HouseholdTls.fingerprint(HouseholdTls.readCa(hubDir)));
    }

    private static int join(Path joinerDir, String... args) throws Exception {
        NodeIdentity.loadOrGenerate(joinerDir.resolve("node-identity.json"));
        var cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), RelayNkeyAdminMain.class.getName(), "household-join"));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true)
            .redirectOutput(joinerDir.resolve("join.log").toFile());
        pb.environment().put("WYRDSEKAI_DATA_DIR", joinerDir.toString());
        pb.environment().put("WYRDSEKAI_CONF", joinerDir.resolve("wyrdsekai.conf").toString());
        var p = pb.start();
        assertThat(p.waitFor(90, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    private static int machineUsers(Path hubDir) throws Exception {
        var users = new ObjectMapper().readTree(hubDir.resolve("nats").resolve(HouseholdBus.USERS).toFile()).path("users");
        int n = 0;
        for (var u : users) if (u.path("kind").asText().equals("MACHINE")) n++;
        return n;
    }

    @Test
    void theJoinKeyReachesThePinnedHubAndTheMachineKeepsItsLogin(@TempDir Path hubDir, @TempDir Path joinerDir) throws Exception {
        var hub = startHub(hubDir);
        int rc = join(joinerDir, "127.0.0.1:" + hub.port(), "--household-key", hub.key() + "." + hub.fp());
        assertThat(rc).as(Files.readString(joinerDir.resolve("join.log"))).isZero();

        var link = HouseholdBus.readHubLink(joinerDir).orElseThrow();
        assertThat(link.user()).startsWith("machine-");
        assertThat(HouseholdTls.fingerprint(link.ca())).isEqualTo(hub.fp());
        assertThat(link.url()).isEqualTo("nats://127.0.0.1:4222");
        assertThat(machineUsers(hubDir)).isEqualTo(1);
        assertThat(Files.readString(joinerDir.resolve("wyrdsekai.conf"))).contains("WYRDSEKAI_NATS_URL=nats://127.0.0.1:4222");
    }

    @Test
    void anotherHomesFingerprintSendsNothing(@TempDir Path hubDir, @TempDir Path joinerDir, @TempDir Path other) throws Exception {
        var hub = startHub(hubDir);
        var wrongFp = HouseholdTls.ensure(other).caFingerprint();
        int rc = join(joinerDir, "127.0.0.1:" + hub.port(), "--household-key", hub.key() + "." + wrongFp);
        assertThat(rc).isNotZero();
        assertThat(HouseholdBus.readHubLink(joinerDir)).isEmpty();
        assertThat(Files.exists(hubDir.resolve("nats").resolve(HouseholdBus.USERS))
            ? machineUsers(hubDir) : 0).isZero();
    }
}
