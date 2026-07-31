package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the household-join LAN-IP bug: a docker bridge interface
 * ({@code docker0}/{@code br-*} at {@code 172.x}) is up, non-loopback, and
 * site-local, so a naive scan advertised an unreachable {@code 172.18.0.1}.
 * {@link Main#selectLanIp} must skip virtual/container interfaces by name and
 * pick the real NIC. Pure — no real network calls (dotted-quad
 * {@code InetAddress.getByName} does not do DNS).
 */
class MainLanIpTest {

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void picksRealNicOverDockerBridge() {
        // docker0 enumerated FIRST — must still lose to wlo1.
        var candidates = List.of(
            Map.entry("docker0", ip("172.18.0.1")),
            Map.entry("br-9af3", ip("172.19.0.1")),
            Map.entry("wlo1", ip("192.0.2.105")));
        assertThat(Main.selectLanIp(candidates)).isEqualTo("192.0.2.105");
    }

    @Test
    void picksEthSiteLocalWhenPresent() {
        var candidates = List.of(
            Map.entry("veth1234", ip("172.17.0.2")),
            Map.entry("eth0", ip("192.0.2.7")));
        assertThat(Main.selectLanIp(candidates)).isEqualTo("192.0.2.7");
    }

    @Test
    void noUsableInterfaceYieldsNull() {
        // Only virtual interfaces — caller then falls back to getLocalHost().
        var candidates = List.of(
            Map.entry("docker0", ip("172.18.0.1")),
            Map.entry("virbr0", ip("192.0.2.1")),
            Map.entry("tun0", ip("10.8.0.1")));
        assertThat(Main.selectLanIp(candidates)).isNull();
    }

    @Test
    void virtualNamesAreSkippedButRealNicNamesAreNot() {
        assertThat(Main.isVirtualIfaceName("docker0")).isTrue();
        assertThat(Main.isVirtualIfaceName("br-9af3c2")).isTrue();
        assertThat(Main.isVirtualIfaceName("veth1a2b")).isTrue();
        assertThat(Main.isVirtualIfaceName("virbr0")).isTrue();
        assertThat(Main.isVirtualIfaceName("tun0")).isTrue();
        assertThat(Main.isVirtualIfaceName("tap0")).isTrue();
        assertThat(Main.isVirtualIfaceName("vmnet8")).isTrue();
        assertThat(Main.isVirtualIfaceName("utun3")).isTrue();
        // Real NICs — note wlo1 CONTAINS "lo" but must NOT be skipped.
        assertThat(Main.isVirtualIfaceName("wlo1")).isFalse();
        assertThat(Main.isVirtualIfaceName("eth0")).isFalse();
        assertThat(Main.isVirtualIfaceName("enp3s0")).isFalse();
        assertThat(Main.isVirtualIfaceName("wlan0")).isFalse();
    }
}
