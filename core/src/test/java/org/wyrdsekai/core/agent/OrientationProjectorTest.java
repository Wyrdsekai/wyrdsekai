package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 / #1057 — projector contract on null/empty
 * stores. The integration-level read tests for WantStore + StoryService +
 * ChronicleEntryStore live in their respective dedicated test suites; this
 * file pins the defensive contract of OrientationProjector itself —
 * specifically that any combination of missing stores produces an empty
 * (but valid) ProjectedOrientation rather than throwing.
 */
class OrientationProjectorTest {

    @Test
    void allNullStoresProduceEmptyProjection() {
        var p = new OrientationProjector(
            "did:wyrd:test", null, null, null);
        var o = p.project(ProjectedOrientation.Lookahead.WHILE_AWAY);
        assertThat(o).isNotNull();
        assertThat(o.isEmpty()).isTrue();
        assertThat(o.lookahead()).isEqualTo(ProjectedOrientation.Lookahead.WHILE_AWAY);
    }

    @Test
    void nullAgentDidProducesEmptyProjection() {
        var p = new OrientationProjector(null, null, null, null);
        var o = p.project(ProjectedOrientation.Lookahead.ON_OWN_TIME);
        assertThat(o.isEmpty()).isTrue();
    }

    @Test
    void nullLookaheadDefaultsToUnspecified() {
        var p = new OrientationProjector("did:wyrd:test", null, null, null);
        var o = p.project(null);
        assertThat(o.lookahead()).isEqualTo(ProjectedOrientation.Lookahead.UNSPECIFIED);
    }

    @Test
    void projectIsIdempotent() {
        var p = new OrientationProjector("did:wyrd:test", null, null, null);
        var a = p.project(ProjectedOrientation.Lookahead.WHILE_AWAY);
        var b = p.project(ProjectedOrientation.Lookahead.WHILE_AWAY);
        // Both empty, both same shape — pure read, no mutation.
        assertThat(a.isEmpty()).isEqualTo(b.isEmpty());
    }
}
