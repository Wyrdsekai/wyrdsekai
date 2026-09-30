package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A social reach waits for a peer's whole turn; a query does not; the window starts when the line goes out. */
class SocialProbeWindowTest {

    @Test
    void a_social_reach_is_still_awaiting_where_a_query_has_given_up() {
        long between = (ProbeLoop.WINDOW_SECONDS + ProbeLoop.SOCIAL_WINDOW_SECONDS) / 2;
        assertThat(ProbeLoop.persistVerdict(between, 0, 0.9, 0.9, true)).isEqualTo(ProbeLoop.Verdict.AWAITING);
        assertThat(ProbeLoop.persistVerdict(between, 0, 0.9, 0.9, false)).isNotEqualTo(ProbeLoop.Verdict.AWAITING);
        assertThat(ProbeLoop.persistVerdict(ProbeLoop.SOCIAL_WINDOW_SECONDS + 1, 0, 0.9, 0.9, true))
            .isNotEqualTo(ProbeLoop.Verdict.AWAITING);
    }

    @Test
    void delivery_restamps_the_probe_once() {
        var registered = Instant.parse("2026-09-27T09:00:21Z");
        var pr = new ProbeLoop.PendingProbe("Affiliation", "rose", registered, 1);
        assertThat(pr.delivered()).isFalse();
        var out = pr.delivered(registered.plusSeconds(18));
        assertThat(out.delivered()).isTrue();
        assertThat(out.sentAt()).isEqualTo(registered.plusSeconds(18));
        assertThat(out.target()).isEqualTo("rose");
    }
}
