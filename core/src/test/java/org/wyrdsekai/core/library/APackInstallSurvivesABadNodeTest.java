package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One flaky storage node must not end a person's pack install.
 *
 * <h2>Measured, not assumed</h2>
 * The bundled knowledge packs live on archive.org, whose download URL redirects to one of
 * several storage nodes. On 2026-08-21 nine consecutive requests for the same file
 * produced two {@code 500 Internal Server Error} responses from nginx — <b>both from the
 * same node</b> — while its siblings served the file fine on every attempt:
 *
 * <pre>
 *   HEAD  -> 200 (dn720201)      plain 1 -> 500 (dn720201)
 *   range 1..4 -> 206 (dn720201) plain 2 -> 200 (ia600508)
 *   range 5 -> 500 (dn720201)    plain 3 -> 200 (ia600508)
 *   range 6 -> 206 (dn760107)
 * </pre>
 *
 * <p>{@code PackDownloader} made exactly one attempt, so a person who happened to be
 * routed to the sick node got {@code "HTTP 500 downloading …"} and a dead end. Because
 * each attempt is redirected afresh, retrying is a real chance at a different, healthy
 * node rather than mere hope.
 *
 * <p>The live test failing the suite was the symptom. This is the defect it exposed.
 */
class APackInstallSurvivesABadNodeTest {

    @Test
    void a_flaky_server_is_worth_asking_again() {
        assertThat(PackDownloader.worthRetrying(500)).isTrue();
        assertThat(PackDownloader.worthRetrying(502)).isTrue();
        assertThat(PackDownloader.worthRetrying(503)).isTrue();
    }

    /** Overload and rate-limit are the server saying "later", which is a retry. */
    @Test
    void being_asked_to_wait_is_worth_asking_again() {
        assertThat(PackDownloader.worthRetrying(408)).isTrue();
        assertThat(PackDownloader.worthRetrying(429)).isTrue();
    }

    /**
     * A 404 is an ANSWER, not a hiccup. Retrying it three more times would only make the
     * real reply slower and less clear — the same reasoning that keeps the invoke-once
     * smoke from treating a harness limitation as an item defect.
     */
    @Test
    void a_definite_answer_is_not_retried() {
        assertThat(PackDownloader.worthRetrying(404)).isFalse();
        assertThat(PackDownloader.worthRetrying(403)).isFalse();
        assertThat(PackDownloader.worthRetrying(401)).isFalse();
        assertThat(PackDownloader.worthRetrying(400)).isFalse();
        assertThat(PackDownloader.worthRetrying(200)).isFalse();
    }
}
