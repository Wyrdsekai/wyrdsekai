package org.wyrdsekai.core.net;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * capability enforcement, without spawning ssh. A
 * recording exec asserts that the gate blocks BEFORE I/O, the resolved keyfile
 * lands in argv, and command-prefix is enforced.
 */
final class NetworkCapabilityTest {

    /** Records the argv it was asked to run; returns a canned success. */
    private static final class RecordingExec implements NetworkCapability.NetworkExec {
        List<String> lastArgv;
        int calls;
        @Override public NetworkCapability.ExecResult run(List<String> argv, Duration timeout) {
            this.lastArgv = new ArrayList<>(argv);
            this.calls++;
            return new NetworkCapability.ExecResult(0, "ok-stdout", "", false);
        }
    }

    private static NetworkCapability.CredentialResolver resolver(String keyfile) {
        return ref -> ref == null ? Optional.empty() : Optional.of(keyfile);
    }

    private static NetworkGate gateAllowing(String host, String... kinds) {
        var entry = new NetworkAllowEntry(host, Set.of(kinds), "household:" + host, null, null);
        return new NetworkGate(List.of(entry), null);
    }

    @Test
    void denied_host_never_execs() {
        var exec = new RecordingExec();
        var cap = new NetworkCapability(NetworkGate.empty(), resolver("/k/id"), exec, null);
        var res = cap.sshRun("second-node", "uptime", Map.of());
        assertFalse((Boolean) res.get("ok"));
        assertEquals(Boolean.TRUE, res.get("denied"));
        assertEquals(0, exec.calls, "gate must block before any exec");
    }

    @Test
    void allowed_ssh_execs_with_resolved_keyfile() {
        var exec = new RecordingExec();
        var cap = new NetworkCapability(gateAllowing("second-node", "ssh"), resolver("/keys/second-node.id"), exec, null);
        var res = cap.sshRun("second-node", "uptime", Map.of());
        assertTrue((Boolean) res.get("ok"));
        assertEquals(1, exec.calls);
        assertTrue(exec.lastArgv.contains("/keys/second-node.id"), "resolved keyfile in argv: " + exec.lastArgv);
        assertTrue(exec.lastArgv.contains("second-node"));
        assertTrue(exec.lastArgv.contains("uptime"));
        assertEquals("ok-stdout", res.get("stdout"));
    }

    @Test
    void allowed_host_but_no_credential_is_denied() {
        var exec = new RecordingExec();
        var cap = new NetworkCapability(gateAllowing("second-node", "ssh"),
            ref -> Optional.empty(), exec, null);   // resolver can't produce a key
        var res = cap.sshRun("second-node", "uptime", Map.of());
        assertFalse((Boolean) res.get("ok"));
        assertEquals("deny:no-credential", res.get("reason"));
        assertEquals(0, exec.calls);
    }

    @Test
    void command_prefix_enforced_before_exec() {
        var entry = new NetworkAllowEntry("second-node", Set.of("ssh"), "household:second-node", null, "wyrd backup");
        var exec = new RecordingExec();
        var cap = new NetworkCapability(new NetworkGate(List.of(entry), null), resolver("/k"), exec, null);
        assertFalse((Boolean) cap.sshRun("second-node", "rm -rf /", Map.of()).get("ok"));
        assertEquals(0, exec.calls, "off-prefix command blocked before exec");
        assertTrue((Boolean) cap.sshRun("second-node", "wyrd backup --now", Map.of()).get("ok"));
        assertEquals(1, exec.calls);
    }

    @Test
    void scp_to_builds_target_spec_and_execs() {
        var exec = new RecordingExec();
        var cap = new NetworkCapability(gateAllowing("second-node", "scp"), resolver("/k"), exec, null);
        var res = cap.scpTo("second-node", "/local/f.txt", "/remote/f.txt", Map.of("user", "operator"));
        assertTrue((Boolean) res.get("ok"));
        assertTrue(exec.lastArgv.contains("/local/f.txt"));
        assertTrue(exec.lastArgv.contains("operator@second-node:/remote/f.txt"), "user@host:path spec: " + exec.lastArgv);
        assertEquals("to", res.get("direction"));
    }

    @Test
    void scp_denied_when_only_ssh_allowed() {
        var exec = new RecordingExec();
        var cap = new NetworkCapability(gateAllowing("second-node", "ssh"), resolver("/k"), exec, null);
        var res = cap.scpTo("second-node", "/a", "/b", Map.of());
        assertFalse((Boolean) res.get("ok"));
        assertEquals(0, exec.calls);
    }

    @Test
    void household_copy_without_transport_is_unwired_not_crash() {
        var cap = new NetworkCapability(NetworkGate.empty(), resolver("/k"), new RecordingExec(), null);
        var res = cap.householdCopy("second-node-node", "/a", "/b");
        assertFalse((Boolean) res.get("ok"));
        assertEquals("deny:no-transport", res.get("reason"));
    }

    @Test
    void household_copy_uses_bus_not_gate() {
        var transport = new java.util.concurrent.atomic.AtomicReference<String>();
        NetworkCapability.HouseholdTransport bus = (node, lp, rp) -> {
            transport.set(node);
            return NetworkCapability.HouseholdTransport.Result.success(rp);
        };
        // Note: empty gate (ssh/scp denied) — household copy must STILL work,
        // it rides the roster trust boundary, not the host allowlist.
        var cap = new NetworkCapability(NetworkGate.empty(), resolver("/k"), new RecordingExec(), bus);
        var res = cap.householdCopy("second-node-node", "/local/f", "/remote/f");
        assertTrue((Boolean) res.get("ok"));
        assertEquals("second-node-node", transport.get());
    }
}
