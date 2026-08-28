package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model-invented {@code workspace} must not kill the build it was attached to.
 *
 * <p>Both values below were produced live on 2026-08-21 by a correct {@code dispatch_task}
 * call. Each one ended the build before any backend ran, and the companion told the
 * steward to widen {@code host.open_roots} for a directory nobody asked for.
 */
class AnInventedWorkspaceIsNotAVetoTest {

    private static final List<String> ROOTS = List.of("/home/steward/projects");

    @Test
    void a_bare_token_is_noise_not_a_refusal() {
        for (var invented : List.of("core", "stewards_study:librarian_toolbox", "workshop")) {
            var d = DispatchWorkspace.decide(invented, ROOTS);
            assertThat(d.refused()).as(invented).isFalse();
            assertThat(d.workspace()).as("falls to the per-task scratch").isEmpty();
            assertThat(d.ignoredNoise()).isEqualTo(invented);
        }
    }

    @Test
    void nothing_named_means_nothing_to_decide() {
        for (var empty : new String[] {null, "", "   "}) {
            var d = DispatchWorkspace.decide(empty, ROOTS);
            assertThat(d.refused()).isFalse();
            assertThat(d.workspace()).isEmpty();
            assertThat(d.ignoredNoise()).isNull();
        }
    }

    /** The gate keeps its teeth for the case it exists for: a real path outside the roots. */
    @Test
    void an_absolute_path_outside_the_roots_is_still_refused() {
        var d = DispatchWorkspace.decide("/etc", ROOTS);
        assertThat(d.refused()).isTrue();
        assertThat(d.workspace()).isEqualTo("/etc");
        assertThat(d.ignoredNoise()).isNull();
    }

    @Test
    void an_absolute_path_inside_the_roots_is_honoured() {
        var d = DispatchWorkspace.decide("/home/steward/projects/site", ROOTS);
        assertThat(d.refused()).isFalse();
        assertThat(d.workspace()).isEqualTo("/home/steward/projects/site");
    }

    /** A root must match as a directory, not as a string prefix. */
    @Test
    void a_sibling_that_merely_shares_a_prefix_is_outside() {
        var d = DispatchWorkspace.decide("/home/steward/projects-evil", ROOTS);
        assertThat(d.refused()).isTrue();
    }
}
