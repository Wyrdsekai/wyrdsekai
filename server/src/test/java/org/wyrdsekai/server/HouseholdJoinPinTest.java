package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `wyrd join` sends the household key only to a hub whose certificate chains to the CA the key names
 * ( W2): a key without the hub's fingerprint is refused before anything is sent.
 */
class HouseholdJoinPinTest {

    @Test
    void aKeyWithoutTheHubsFingerprintIsRefusedBeforeAnythingIsSent() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int rc = RelayNkeyAdminMain.run(new PrintStream(out), new PrintStream(err),
            "household-join", "192.0.2.10", "--household-key", "wyrd_hk_" + "a".repeat(64));
        assertThat(rc).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("wyrd household key");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("enrolling");
    }

    @Test
    void aMalformedFingerprintIsRefusedToo() {
        var err = new ByteArrayOutputStream();
        int rc = RelayNkeyAdminMain.run(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err),
            "household-join", "192.0.2.10", "--household-key", "wyrd_hk_abc", "--ca-fp", "not-a-fingerprint");
        assertThat(rc).isEqualTo(1);
    }
}
