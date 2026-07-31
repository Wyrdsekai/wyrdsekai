package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plane-B — gate explicitly OFF (steward opt-out): the
 * SAME dispatched task now runs with the daemon's ambient credentials intact,
 * proving the {@code wyrdsekai.coding.egress-gate.enabled = false} knob
 * actually loosens a live subprocess (and documenting what "off" costs — the
 * subprocess regains {@code SSH_AUTH_SOCK} and cloud tokens, so a
 * prompt-injected backend could pivot with the steward's keys).
 *
 * <p>Identical harness + ambient env to the ON test; the only variable is the
 * config knob, so the ON→OFF contrast is exactly what the gate controls.
 */
@Tag("e2e")
final class CodingEgressGateOffPermitsE2ETest extends CodingEgressGateE2EBase {

    @Test
    void disabled_gate_leaves_ambient_credentials_in_the_live_subprocess() throws Exception {
        var out = runProbeThroughGate(disabledFromConfig(), envReportProbe(null));

        assertEquals(0, out.exitCode(), () -> "probe failed:\n" + out.stdout());
        assertTrue(out.sawSshSock(),
            () -> "gate OFF must leave SSH_AUTH_SOCK inherited (knob loosened); got:\n" + out.stdout());
        assertTrue(out.sawCloudToken(),
            () -> "gate OFF must leave MYCLOUD_TOKEN inherited; got:\n" + out.stdout());
    }

    @Test
    void the_knob_is_the_only_difference_between_on_and_off() throws Exception {
        var on = runProbeThroughGate(enforcingFromConfig(), envReportProbe(null));
        var off = runProbeThroughGate(disabledFromConfig(), envReportProbe(null));

        // Same ambient input, opposite outcomes — pins causation to the knob.
        assertTrue(on.sshSockEmpty() && on.cloudTokenEmpty(),
            () -> "ON should scrub both:\n" + on.stdout());
        assertTrue(off.sawSshSock() && off.sawCloudToken(),
            () -> "OFF should keep both:\n" + off.stdout());
    }
}
