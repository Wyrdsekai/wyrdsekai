package org.wyrdsekai.core.update;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nodes update each other through the mesh: the decision, with no network in it.
 *
 * <p>The heartbeats carried every peer's version and nothing read them (2026-10-03).
 */
class MeshReleaseTest {

    private static final Duration DELAY = Duration.ofMinutes(5);
    private static final Instant T0 = Instant.parse("2026-10-03T20:00:00Z");

    private static MeshRelease.Peer peer(String id, String v) { return new MeshRelease.Peer(id, v, true, "http://" + id + ":7070"); }

    @Test
    void a_secondary_follows_a_peers_newer_release_once_it_has_been_stable_for_the_delay() {
        var m = new MeshRelease();
        m.observe(List.of(peer("laptop", "0.5.4")), T0);
        assertThat(m.candidate("0.5.3", false, DELAY, T0.plusSeconds(60))).as("not stable yet").isEmpty();
        m.observe(List.of(peer("laptop", "0.5.4")), T0.plusSeconds(200));
        var c = m.candidate("0.5.3", false, DELAY, T0.plusSeconds(301)).orElseThrow();
        assertThat(c.version()).isEqualTo("0.5.4");
        assertThat(c.nodeId()).isEqualTo("laptop");
        assertThat(c.httpUrl()).isEqualTo("http://laptop:7070");
        assertThat(c.stableSince()).isEqualTo(T0);
    }

    @Test
    void a_dev_build_an_older_release_a_failed_version_and_a_peer_that_left_are_not_followed() {
        var m = new MeshRelease();
        m.observe(List.of(peer("a", "0.5.4~dev12"), peer("b", "0.5.2"), peer("c", "0.5.5"), peer("d", "0.5.6")), T0);
        var later = T0.plus(DELAY).plusSeconds(1);
        m.observe(List.of(peer("a", "0.5.4~dev12"), peer("b", "0.5.2"), peer("c", "0.5.5"), peer("d", "0.5.6")), later);
        assertThat(m.candidate("0.5.3", false, DELAY, later).orElseThrow().version()).as("the highest stable release").isEqualTo("0.5.6");
        m.markFailed("0.5.6");
        assertThat(m.candidate("0.5.3", false, DELAY, later).orElseThrow().version()).isEqualTo("0.5.5");
        // d leaves: its version goes with it; c stays
        m.observe(List.of(peer("a", "0.5.4~dev12"), peer("b", "0.5.2"), peer("c", "0.5.5")), later.plusSeconds(30));
        assertThat(m.candidate("0.5.3", false, DELAY, later.plusSeconds(30)).orElseThrow().version()).isEqualTo("0.5.5");
        m.observe(List.of(peer("a", "0.5.4~dev12"), peer("b", "0.5.2")), later.plusSeconds(60));
        assertThat(m.candidate("0.5.3", false, DELAY, later.plusSeconds(60))).as("nothing newer left").isEmpty();
        assertThat(m.candidate("0.5.6", false, DELAY, later)).as("already beyond them").isEmpty();
    }

    @Test
    void the_stewards_node_goes_last_only_when_every_peer_with_a_version_is_already_there() {
        var m = new MeshRelease();
        var peers = List.of(peer("laptop", "0.5.4"), peer("nas", "0.5.3"), new MeshRelease.Peer("phone", null, true, null));
        m.observe(peers, T0);
        var later = T0.plus(DELAY).plusSeconds(1);
        m.observe(peers, later);
        assertThat(m.candidate("0.5.3", true, DELAY, later)).as("the nas is still behind").isEmpty();
        assertThat(m.candidate("0.5.3", false, DELAY, later)).as("a secondary would follow").isPresent();
        var all = List.of(peer("laptop", "0.5.4"), peer("nas", "0.5.4"), new MeshRelease.Peer("phone", null, true, null));
        m.observe(all, later.plusSeconds(10));
        assertThat(m.candidate("0.5.3", true, DELAY, later.plusSeconds(10)).orElseThrow().version()).isEqualTo("0.5.4");
    }

    @Test
    void the_clock_starts_over_when_the_first_peer_at_a_version_disconnects() {
        var m = new MeshRelease();
        m.observe(List.of(peer("laptop", "0.5.4")), T0);
        m.observe(List.of(new MeshRelease.Peer("laptop", "0.5.4", false, null), peer("nas", "0.5.4")), T0.plusSeconds(240));
        assertThat(m.candidate("0.5.3", false, DELAY, T0.plusSeconds(301))).as("the nas has only been seen for a minute").isEmpty();
        m.observe(List.of(peer("nas", "0.5.4")), T0.plusSeconds(560));
        assertThat(m.candidate("0.5.3", false, DELAY, T0.plusSeconds(561))).isPresent();
    }
}
