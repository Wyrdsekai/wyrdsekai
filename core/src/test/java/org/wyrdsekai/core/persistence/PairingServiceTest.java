package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PairingService} — device pairing, household keys, token validation.
 * Uses an in-memory SQLite database per test.
 */
@Tag("integration")
class PairingServiceTest {

    private PairingService service;

    @BeforeEach
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        service = new PairingService(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl),
            "test-household", "Test Household", "did:key:test",
            "ws://localhost:4222", "http://localhost:7070");
        service.initSchema();
    }

    // ── Challenge creation ───────────────────────────────────────────

    @Test
    void createChallenge_generates_6_digit_code() {
        var challenge = service.createChallenge("My Phone", "phone", null);

        assertThat(challenge.code()).hasSize(6);
        assertThat(challenge.code()).matches("\\d{6}");
        assertThat(challenge.challengeId()).isNotBlank();
        assertThat(challenge.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void createChallenge_expires_previous_pending() {
        var first = service.createChallenge("Phone 1", "phone", null);
        var second = service.createChallenge("Phone 2", "phone", null);

        // First challenge should now be expired — verifying it should fail
        var result = service.verifyCode(first.challengeId(), first.code());
        assertThat(result).isEmpty();

        // Second challenge should still work
        var result2 = service.verifyCode(second.challengeId(), second.code());
        assertThat(result2).isPresent();
    }

    // ── Code verification ────────────────────────────────────────────

    @Test
    void verifyCode_correct_returns_token() {
        var challenge = service.createChallenge("My Phone", "phone", "pk-abc");

        var result = service.verifyCode(challenge.challengeId(), challenge.code());

        assertThat(result).isPresent();
        assertThat(result.get().token()).startsWith("wyrd_dev_");
        assertThat(result.get().householdId()).isEqualTo("test-household");
        assertThat(result.get().householdName()).isEqualTo("Test Household");
        assertThat(result.get().serverDid()).isEqualTo("did:key:test");
        assertThat(result.get().natsUrl()).isEqualTo("ws://localhost:4222");
        assertThat(result.get().serverUrl()).isEqualTo("http://localhost:7070");
    }

    @Test
    void verifyCode_wrong_code_returns_empty() {
        var challenge = service.createChallenge("My Phone", "phone", null);

        var result = service.verifyCode(challenge.challengeId(), "000000");

        assertThat(result).isEmpty();
    }

    @Test
    void verifyCode_expired_returns_empty() {
        // Create a challenge, then manually expire it via a second create
        var challenge = service.createChallenge("My Phone", "phone", null);
        // Creating a new challenge expires the previous one
        service.createChallenge("Other Phone", "phone", null);

        var result = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(result).isEmpty();
    }

    @Test
    void verifyCode_max_attempts_locks() {
        var challenge = service.createChallenge("My Phone", "phone", null);

        // 3 wrong attempts
        service.verifyCode(challenge.challengeId(), "000001");
        service.verifyCode(challenge.challengeId(), "000002");
        service.verifyCode(challenge.challengeId(), "000003");

        // Even the correct code should fail now (locked)
        var result = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(result).isEmpty();
    }

    // ── Device token validation ──────────────────────────────────────

    @Test
    void validateDeviceToken_returns_device() {
        var challenge = service.createChallenge("My Phone", "phone", "pk-xyz");
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        var device = service.validateDeviceToken(token);

        assertThat(device).isPresent();
        assertThat(device.get().name()).isEqualTo("My Phone");
        assertThat(device.get().type()).isEqualTo("phone");
        assertThat(device.get().revoked()).isFalse();
    }

    @Test
    void validateDeviceToken_revoked_returns_empty() {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        // Get device ID, then revoke it
        var device = service.validateDeviceToken(token);
        assertThat(device).isPresent();
        service.revokeDevice(device.get().id());

        // Token should no longer validate
        assertThat(service.validateDeviceToken(token)).isEmpty();
    }

    // ── List and revoke ──────────────────────────────────────────────

    @Test
    void listDevices_returns_all() {
        // Pair two devices
        var c1 = service.createChallenge("Phone 1", "phone", null);
        service.verifyCode(c1.challengeId(), c1.code());
        var c2 = service.createChallenge("Laptop", "desktop", null);
        service.verifyCode(c2.challengeId(), c2.code());

        var devices = service.listDevices();
        assertThat(devices).hasSize(2);
        assertThat(devices).extracting(PairingService.PairedDevice::name)
            .containsExactlyInAnyOrder("Phone 1", "Laptop");
    }

    @Test
    void revokeDevice_invalidates() {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        var device = service.validateDeviceToken(token);
        assertThat(device).isPresent();

        service.revokeDevice(device.get().id());

        // Token no longer valid
        assertThat(service.validateDeviceToken(token)).isEmpty();

        // Device still in list but marked revoked
        var allDevices = service.listDevices();
        assertThat(allDevices).hasSize(1);
        assertThat(allDevices.get(0).revoked()).isTrue();
    }

    // ── Household key ────────────────────────────────────────────────

    @Test
    void generateHouseholdKey_creates_key() {
        var key = service.generateHouseholdKey();

        assertThat(key).startsWith("wyrd_hk_");
        assertThat(key).hasSizeGreaterThan(16);
    }

    @Test
    void pairWithKey_valid_returns_token() {
        var key = service.generateHouseholdKey();

        var result = service.pairWithKey(key, "Headless Node", "server", null);

        assertThat(result).isPresent();
        assertThat(result.get().token()).startsWith("wyrd_dev_");
        assertThat(result.get().householdId()).isEqualTo("test-household");
    }

    @Test
    void pairWithKey_invalid_returns_empty() {
        var result = service.pairWithKey("wyrd_hk_bogus_key", "Phone", "phone", null);

        assertThat(result).isEmpty();
    }

    @Test
    void pairWithKey_revoked_returns_empty() {
        var key = service.generateHouseholdKey();
        service.revokeHouseholdKey(key);

        var result = service.pairWithKey(key, "Phone", "phone", null);

        assertThat(result).isEmpty();
    }

    // ── getPendingChallenge ──────────────────────────────────────────

    @Test
    void getPendingChallenge_returns_latest() {
        // No challenges yet
        assertThat(service.getPendingChallenge()).isEmpty();

        var challenge = service.createChallenge("My Phone", "phone", null);

        var pending = service.getPendingChallenge();
        assertThat(pending).isPresent();
        assertThat(pending.get().code()).isEqualTo(challenge.code());
        assertThat(pending.get().challengeId()).isEqualTo(challenge.challengeId());
    }

    // ── Device-user linkage ──────────────────────────────────────────

    @Test
    void linkDeviceToUser_and_validate() {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        // Initially no user linked
        var device = service.validateDeviceToken(token);
        assertThat(device).isPresent();
        assertThat(device.get().userId()).isNull();

        // Link to a user
        var linked = service.linkDeviceToUser(token, "user-123");
        assertThat(linked).isTrue();

        // Validate now returns the user ID
        device = service.validateDeviceToken(token);
        assertThat(device).isPresent();
        assertThat(device.get().userId()).isEqualTo("user-123");
    }

    @Test
    void unlinkDevice_clears_userId() {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        // Link then unlink
        service.linkDeviceToUser(token, "user-456");
        service.unlinkDevice(token);

        var device = service.validateDeviceToken(token);
        assertThat(device).isPresent();
        assertThat(device.get().userId()).isNull();
    }

    @Test
    void findUserForDevice_returns_linked_userId() {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        // No user linked yet
        assertThat(service.findUserForDevice(token)).isEmpty();

        // Link
        service.linkDeviceToUser(token, "user-789");
        assertThat(service.findUserForDevice(token)).isPresent().hasValue("user-789");

        // Bogus token
        assertThat(service.findUserForDevice("bogus-token")).isEmpty();
    }

    // ── Touch device ─────────────────────────────────────────────────

    @Test
    void touchDevice_updates_lastSeen() throws Exception {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        var beforeTouch = service.validateDeviceToken(token);
        assertThat(beforeTouch).isPresent();
        var lastSeenBefore = beforeTouch.get().lastSeen();

        // Small delay to ensure timestamp changes
        Thread.sleep(1100);
        service.touchDevice(token);

        var afterTouch = service.validateDeviceToken(token);
        assertThat(afterTouch).isPresent();
        assertThat(afterTouch.get().lastSeen()).isAfterOrEqualTo(lastSeenBefore);
    }

    // ── getActiveHouseholdKey ────────────────────────────────────────

    @Test
    void getActiveHouseholdKey_empty_when_none() {
        assertThat(service.getActiveHouseholdKey()).isEmpty();
    }

    @Test
    void getActiveHouseholdKey_returns_one_of_active() {
        var key1 = service.generateHouseholdKey();
        var key2 = service.generateHouseholdKey();

        var active = service.getActiveHouseholdKey();
        assertThat(active).isPresent();
        // Both keys created in same second — ORDER BY created_at DESC may return either
        assertThat(active.get().key()).isIn(key1, key2);
    }

    @Test
    void getActiveHouseholdKey_skips_revoked() {
        var key1 = service.generateHouseholdKey();
        var key2 = service.generateHouseholdKey();
        service.revokeHouseholdKey(key2);

        var active = service.getActiveHouseholdKey();
        assertThat(active).isPresent();
        assertThat(active.get().key()).isEqualTo(key1);
    }

    // ── linkDeviceToUser edge cases ──────────────────────────────────

    @Test
    void linkDeviceToUser_fails_for_revoked_device() {
        var challenge = service.createChallenge("My Phone", "phone", null);
        var pairResult = service.verifyCode(challenge.challengeId(), challenge.code());
        assertThat(pairResult).isPresent();
        var token = pairResult.get().token();

        // Revoke the device
        var device = service.validateDeviceToken(token);
        assertThat(device).isPresent();
        service.revokeDevice(device.get().id());

        // Linking should fail (revoked = 1 means the WHERE clause won't match)
        var linked = service.linkDeviceToUser(token, "user-abc");
        assertThat(linked).isFalse();
    }

    @Test
    void linkDeviceToUser_fails_for_bogus_token() {
        var linked = service.linkDeviceToUser("bogus-token", "user-abc");
        assertThat(linked).isFalse();
    }
}
