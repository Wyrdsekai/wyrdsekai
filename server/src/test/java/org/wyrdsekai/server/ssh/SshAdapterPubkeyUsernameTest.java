package org.wyrdsekai.server.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.AuthService;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 2026-07-18 SSH identity fix: a registered public key used to
 * authenticate ANY requested username into the key's account — with the
 * steward's key loaded, {@code ssh alice@host} landed in the steward account,
 * and claiming a second account required manually disabling pubkey auth.
 * A key authenticates only the account it is bound to.
 */
class SshAdapterPubkeyUsernameTest {

    private static final AuthService.User STEWARD = new AuthService.User(
        "5e04f078-dca8-47a4-8f13-cc082d29aaac", "steward", "Steward",
        "steward", Instant.EPOCH);

    @Test
    @DisplayName("the key's own username matches, case-insensitively")
    void ownUsernameMatches() {
        assertTrue(SshAdapter.pubkeyUsernameMatches("steward", STEWARD));
        assertTrue(SshAdapter.pubkeyUsernameMatches("Steward", STEWARD));
        assertTrue(SshAdapter.pubkeyUsernameMatches("  steward ", STEWARD));
    }

    @Test
    @DisplayName("the account id is accepted as a requested name")
    void accountIdMatches() {
        assertTrue(SshAdapter.pubkeyUsernameMatches(
            "5e04f078-dca8-47a4-8f13-cc082d29aaac", STEWARD));
    }

    @Test
    @DisplayName("any other username is refused — the live bypass case")
    void otherUsernameRefused() {
        assertFalse(SshAdapter.pubkeyUsernameMatches("alice", STEWARD));
        assertFalse(SshAdapter.pubkeyUsernameMatches("root", STEWARD));
    }

    @Test
    @DisplayName("null/blank requested names never match")
    void blankNeverMatches() {
        assertFalse(SshAdapter.pubkeyUsernameMatches(null, STEWARD));
        assertFalse(SshAdapter.pubkeyUsernameMatches("", STEWARD));
        assertFalse(SshAdapter.pubkeyUsernameMatches("   ", STEWARD));
        assertFalse(SshAdapter.pubkeyUsernameMatches("steward", null));
    }
}
