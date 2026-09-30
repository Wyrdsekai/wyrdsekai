package org.wyrdsekai.core.crypto;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** W2: who may use the household bus, and the nats-server settings for it. */
class HouseholdBusTest {

    private static HouseholdTls.Material tls(Path data) throws Exception {
        return HouseholdTls.ensure(data, Set.of("localhost", "127.0.0.1"), Instant.now());
    }

    private static HouseholdBus.Listen listen(boolean plaintext) {
        return new HouseholdBus.Listen("0.0.0.0", 4222, 4223, "ferngrove", plaintext);
    }

    @Test
    void everyClientLogsInAndTheBusIsTls(@TempDir Path data) throws Exception {
        var bus = HouseholdBus.open(data);
        var node = bus.nodeCredential();
        assertThat(node.user()).isEqualTo(HouseholdBus.NODE_USER);
        assertThat(node.pass()).hasSizeGreaterThanOrEqualTo(40);

        var conf = bus.render(tls(data), listen(false));
        assertThat(conf).contains("listen: \"0.0.0.0:4222\"");
        assertThat(conf).contains("tls {").contains("household-leaf.pem").contains("household-leaf.key");
        assertThat(conf).contains("authorization {").contains("user: \"node\"");
        assertThat(conf).doesNotContain("no_auth_user").doesNotContain("allow_non_tls").doesNotContain("no_tls");
        assertThat(conf).doesNotContain(node.pass());   // only the bcrypt hash
        var ws = conf.substring(conf.indexOf("websocket {"));
        assertThat(ws).contains("listen: \"0.0.0.0:4223\"").contains("tls {");
    }

    @Test
    void thePackagedBusServiceCanReachTheIncludeThroughItsFolder(@TempDir Path data) throws Exception {
        // The deb's wyrdsekai-nats runs as nobody with the bus group; on second-node the nats/ folder came out
        // 0750 root:root and the service failed "permission denied" (2026-09-28). Traverse-only, like tls/.
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var bus = HouseholdBus.open(data);
        var f = bus.writeInclude(tls(data), listen(false));
        // Traverse for the bus group only (or the node's alone where the group does not exist); never
        // open to others, which the boot-time closing of the data folder would take away anyway.
        var dirMode = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.getParent()));
        assertThat(dirMode).isIn("rwx--x---", "rwx------");
        if (dirMode.equals("rwx--x---")) {
            assertThat(Files.readAttributes(f.getParent(), PosixFileAttributes.class)
                .group().getName()).isEqualTo("wyrdsekai-nats");
        }
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
            f.getParent().resolve("bus-users.json")))).isEqualTo("rw-------");
    }

    @Test
    void phonesAndMachinesGetTheirOwnLoginsAndPhonesOnlyTheirSubjects(@TempDir Path data) throws Exception {
        var bus = HouseholdBus.open(data);
        var phone = bus.issue(HouseholdBus.Kind.PHONE, "3f2a9c1e-device");
        var machine = bus.issue(HouseholdBus.Kind.MACHINE, "node-7b1d");
        assertThat(phone.user()).isEqualTo("phone-3f2a9c1e-device");
        assertThat(machine.user()).isEqualTo("machine-node-7b1d");

        var conf = bus.render(tls(data), listen(false));
        var phoneLine = conf.lines().filter(l -> l.contains(phone.user() + "\"")).findFirst().orElseThrow();
        assertThat(phoneLine).contains("wyrd.zone.ferngrove.>").contains("wyrd.tunnel.ferngrove.*.down")
            .contains("_INBOX." + phone.user() + ".>").contains("allow_responses: true")
            .doesNotContain("\"_INBOX.>\"").doesNotContain("account.")
            .contains("between.ferngrove." + phone.user() + ".*.study.sync")
            .contains("between.ferngrove.*." + phone.user() + ".study.sync")
            .contains("federation.inference.stream." + phone.user() + ".*")
            .doesNotContain("between.ferngrove.*.*").doesNotContain("\"federation.inference.stream.*\"");
        var machineLine = conf.lines().filter(l -> l.contains(machine.user() + "\"")).findFirst().orElseThrow();
        assertThat(machineLine).doesNotContain("permissions");

        // The stored hash is bcrypt, the one nats-server checks, and matches the handed-out password.
        var users = new ObjectMapper().readTree(data.resolve("nats").resolve(HouseholdBus.USERS).toFile()).path("users");
        var stored = users.get(0).path("hash").asText();
        assertThat(stored).startsWith("$2a$");
        assertThat(BCrypt.verifyer().verify(phone.pass().toCharArray(), stored).verified).isTrue();
    }

    @Test
    void issuingAgainReplacesAndRevokingRemoves(@TempDir Path data) throws Exception {
        var bus = HouseholdBus.open(data);
        var changes = new AtomicInteger();
        bus.onChange(changes::incrementAndGet);
        bus.writeInclude(tls(data), listen(false));

        var first = bus.issue(HouseholdBus.Kind.PHONE, "dev-1");
        var second = bus.issue(HouseholdBus.Kind.PHONE, "dev-1");
        assertThat(second.pass()).isNotEqualTo(first.pass());
        var include = Files.readString(bus.includeFile());
        assertThat(include.lines().filter(l -> l.contains("\"phone-dev-1\"")).count()).isEqualTo(1);

        assertThat(bus.revoke("dev-1")).isTrue();
        assertThat(Files.readString(bus.includeFile())).doesNotContain("phone-dev-1");
        assertThat(bus.revoke("dev-1")).isFalse();
        assertThat(changes.get()).isEqualTo(3);
    }

    @Test
    void theTransitionReopensTheOldPlainDoorAndSaysSoInTheSettings(@TempDir Path data) throws Exception {
        var conf = HouseholdBus.open(data).render(tls(data), listen(true));
        assertThat(conf).contains("allow_non_tls: true").contains("no_auth_user: \"legacy-open\"")
            .contains("{user: \"legacy-open\"}");
        var ws = conf.substring(conf.indexOf("websocket {"));
        assertThat(ws).contains("no_tls: true").contains("no_auth_user");
    }

    @Test
    void withoutACertificateTheBusStaysOnThisMachine(@TempDir Path data) throws Exception {
        var conf = HouseholdBus.open(data).render(null, listen(false));
        assertThat(conf).contains("listen: \"127.0.0.1:4222\"").contains("listen: \"127.0.0.1:4223\"");
        assertThat(conf).contains("authorization {").doesNotContain("cert_file");
    }

    @Test
    void theUsersFileIsPrivate(@TempDir Path data) throws Exception {
        HouseholdBus.open(data).issue(HouseholdBus.Kind.PHONE, "dev-2");
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
            data.resolve("nats").resolve(HouseholdBus.USERS)))).isEqualTo("rw-------");
    }

    @Test
    void aHubLinkRoundTripsAndMatchesItsServer(@TempDir Path data) throws Exception {
        var ca = tls(data).ca();
        var pem = HouseholdTls.pem("CERTIFICATE", ca.getEncoded());
        HouseholdBus.saveHubLink(data, new HouseholdBus.HubLink("nats://192.0.2.10:4222", "machine-x", "pw", pem));
        var link = HouseholdBus.readHubLink(data).orElseThrow();
        assertThat(link.ca()).isEqualTo(ca);
        assertThat(HouseholdBus.sameServer("tls://192.0.2.10:4222", link.url())).isTrue();
        assertThat(HouseholdBus.sameServer("nats://192.0.2.10", link.url())).isTrue();
        assertThat(HouseholdBus.sameServer("nats://192.0.2.11:4222", link.url())).isFalse();
        assertThat(List.of(HouseholdBus.readHubLink(data.resolve("elsewhere")).isPresent())).containsExactly(false);
    }
}
