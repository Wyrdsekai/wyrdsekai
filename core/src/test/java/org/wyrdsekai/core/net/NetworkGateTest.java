package org.wyrdsekai.core.net;

import java.util.List;
import java.util.Set;

import com.typesafe.config.ConfigFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * allowlist gate semantics. ssh/scp default-deny
 * http/https default-allow, allowlist match carries the credential handle, and
 * an explicit http entry flips that kind to restrict-mode.
 */
final class NetworkGateTest {

    @Test
    void empty_gate_denies_ssh_scp_allows_http() {
        var g = NetworkGate.empty();
        assertFalse(g.check("ssh", "second-node", null).allowed(), "ssh closed by default");
        assertFalse(g.check("scp", "second-node", null).allowed(), "scp closed by default");
        assertTrue(g.check("http", "example.com", "http").allowed(), "http open by default");
        assertTrue(g.check("https", "api.weather.gov", "https").allowed(), "https open by default");
    }

    @Test
    void allowlisted_ssh_host_is_permitted_and_carries_keyref() {
        var entry = new NetworkAllowEntry("second-node", Set.of("ssh", "scp"), "household:second-node", null, null);
        var g = new NetworkGate(List.of(entry), null);
        var v = g.check("ssh", "second-node", null);
        assertTrue(v.allowed());
        assertEquals("allow:allowlist", v.reason());
        assertNotNull(v.entry());
        assertEquals("household:second-node", v.entry().keyRef());
        // A different host stays denied.
        assertFalse(g.check("ssh", "elsewhere", null).allowed());
    }

    @Test
    void wildcard_host_matches() {
        var entry = new NetworkAllowEntry("*.example.com", Set.of("scp"), "chest:backup", null, null);
        var g = new NetworkGate(List.of(entry), null);
        assertTrue(g.check("scp", "backups.example.com", null).allowed());
        assertFalse(g.check("scp", "example.org", null).allowed());
    }

    @Test
    void kind_scoping_is_respected() {
        // entry grants scp only — ssh to the same host stays denied.
        var entry = new NetworkAllowEntry("backups.example", Set.of("scp"), "chest:k", null, null);
        var g = new NetworkGate(List.of(entry), null);
        assertTrue(g.check("scp", "backups.example", null).allowed());
        assertFalse(g.check("ssh", "backups.example", null).allowed());
    }

    @Test
    void http_becomes_restrict_mode_when_an_http_entry_exists() {
        var entry = new NetworkAllowEntry("api.weather.gov", Set.of("https"), null, List.of("https"), null);
        var g = new NetworkGate(List.of(entry), null);
        // On-list host allowed...
        assertTrue(g.check("https", "api.weather.gov", "https").allowed());
        // ...but ANY https entry flips https to restrict-mode → off-list denied.
        assertFalse(g.check("https", "evil.example", "https").allowed(),
            "an explicit https allowlist entry opts into restrict-mode");
        // http (no entry for that kind) stays permissive.
        assertTrue(g.check("http", "anything.example", "http").allowed());
    }

    @Test
    void scheme_restriction_on_http_entry() {
        var entry = new NetworkAllowEntry("api.example", Set.of("http", "https"), null, List.of("https"), null);
        var g = new NetworkGate(List.of(entry), null);
        assertTrue(g.check("https", "api.example", "https").allowed());
        assertFalse(g.check("http", "api.example", "http").allowed(), "only https permitted for this host");
    }

    @Test
    void command_prefix_constrains_far_hand() {
        var entry = new NetworkAllowEntry("second-node", Set.of("ssh"), "household:second-node", null, "wyrd backup");
        var g = new NetworkGate(List.of(entry), null);
        assertTrue(g.checkSshCommand("second-node", "wyrd backup --now").allowed());
        assertFalse(g.checkSshCommand("second-node", "rm -rf /").allowed());
        assertEquals("deny:command-prefix", g.checkSshCommand("second-node", "rm -rf /").reason());
    }

    @Test
    void from_config_parses_entries_and_defaults() {
        var cfg = ConfigFactory.parseString("""
            wyrdsekai.net.allowlist = [
              { host = "second-node",            kinds = ["ssh","scp"], key-ref = "household:second-node" }
              { host = "backups.example", kinds = ["scp"],       key-ref = "chest:backup-key", command-prefix = "rsync" }
            ]
            wyrdsekai.net.default { ssh = deny, scp = deny, http = allow, https = allow }
            """);
        var g = NetworkGate.fromConfig(cfg);
        assertEquals(2, g.allowlist().size());
        assertTrue(g.check("ssh", "second-node", null).allowed());
        assertEquals("chest:backup-key", g.check("scp", "backups.example", null).entry().keyRef());
        assertFalse(g.defaultFor("ssh"));
        assertTrue(g.defaultFor("http"));
    }

    @Test
    void from_config_absent_is_empty_gate() {
        var g = NetworkGate.fromConfig(ConfigFactory.parseString("wyrdsekai {}"));
        assertTrue(g.allowlist().isEmpty());
        assertFalse(g.check("ssh", "x", null).allowed());
        assertTrue(g.check("http", "x", "http").allowed());
    }

    @Test
    void default_override_can_close_http() {
        // A cautious steward flips http to deny-by-default.
        var cfg = ConfigFactory.parseString("wyrdsekai.net.default.http = deny");
        var g = NetworkGate.fromConfig(cfg);
        assertFalse(g.check("http", "anything", "http").allowed());
    }
}
