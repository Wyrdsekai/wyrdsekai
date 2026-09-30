package org.wyrdsekai.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 ( W2): a paired phone gets its own login for the home bus in the pairing
 * reply ({@code nats_user}, {@code nats_pass}); revoking the phone removes it. A household key pasted
 * as a join key ({@code <key>.<home_ca_fp>}) still validates.
 */
@Tag("integration")
class PairingBusLoginTest {

    @TempDir Path data;
    private PairingService service;
    private HouseholdBus bus;

    @BeforeEach
    void setUp() throws Exception {
        var jdbcUrl = TestDb.createInMemory();
        service = new PairingService(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl),
            "test-household", "Test Household", "did:key:test", "nats://127.0.0.1:4222", "https://192.0.2.5:7443");
        service.initSchema();
        bus = HouseholdBus.open(data);
        service.useHouseholdBus(bus);
    }

    private int busUsers() throws Exception {
        return new ObjectMapper().readTree(data.resolve("nats").resolve(HouseholdBus.USERS).toFile()).path("users").size();
    }

    @Test
    void everyPairingPathHandsOutTheDevicesOwnBusLogin() throws Exception {
        var c = service.createChallenge("Phone", "phone", "pk");
        var byCode = service.verifyCode(c.challengeId(), c.code()).orElseThrow();
        assertThat(byCode.natsUser()).startsWith("phone-");
        assertThat(byCode.natsPass()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(byCode.natsCredentialFields()).containsOnlyKeys("nats_user", "nats_pass");

        var byKey = service.pairWithKey(service.generateHouseholdKey(), "Tablet", "phone", null).orElseThrow();
        assertThat(byKey.natsUser()).startsWith("phone-").isNotEqualTo(byCode.natsUser());

        var forUser = service.pairForUser("user-1", "Pixel", "phone");
        var again = service.pairForUser("user-1", "Pixel", "phone");
        assertThat(again.natsUser()).isEqualTo(forUser.natsUser());
        assertThat(again.natsPass()).as("only a hash is kept: a new password each time").isNotEqualTo(forUser.natsPass());
        assertThat(busUsers()).isEqualTo(3);
    }

    @Test
    void revokingTheDeviceRemovesItsBusLogin() throws Exception {
        var c = service.createChallenge("Phone", "phone", "pk");
        service.verifyCode(c.challengeId(), c.code()).orElseThrow();
        var device = service.listDevices().getFirst();
        assertThat(busUsers()).isEqualTo(1);
        service.revokeDevice(device.id());
        assertThat(busUsers()).isZero();
    }

    @Test
    void withoutABusThereIsNoLoginAndPairingStillWorks() {
        var jdbcUrl = TestDb.createInMemory();
        var plain = new PairingService(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl),
            "h", "H", "", "", "");
        plain.initSchema();
        var c = plain.createChallenge("Phone", "phone", null);
        var r = plain.verifyCode(c.challengeId(), c.code()).orElseThrow();
        assertThat(r.token()).startsWith("wyrd_dev_");
        assertThat(r.natsCredentialFields()).isEmpty();
    }

    @Test
    void aJoinKeyCarriesTheFingerprintAndStillValidates() {
        var key = service.generateHouseholdKey();
        var fp = "ab".repeat(32);
        assertThat(service.validateHouseholdKey(key + "." + fp)).isTrue();
        assertThat(PairingService.householdKeyPart(key + "." + fp)).isEqualTo(key);
        assertThat(PairingService.householdKeyPart(key)).isEqualTo(key);
        assertThat(service.validateHouseholdKey(key + ".nothex")).isFalse();
    }
}
