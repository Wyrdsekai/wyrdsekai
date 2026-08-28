package org.wyrdsekai.server.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A second ingest of the same collection is refused, not raced.
 *
 * <p>Every POST to {@code /api/study/add} used to fire a fresh async job
 * unconditionally. A double-submit — an impatient re-run, a retried curl —
 * meant two full passes over a 74k-book tree at once: the index survives
 * (content-derived ids, last-write-wins) but hours of extraction CPU double,
 * two writers interleave one ledger file, and the progress log becomes two
 * shuffled streams. Asked directly during the 2026-08-09 fresh-install ingest:
 * "what happens if someone runs ingest twice" — the honest answer was
 * "nothing good, and nothing stops you". Now something stops you.</p>
 */
class OneIngestPerCollectionTest {

    private static String src() throws Exception {
        var rel = "server/src/main/java/org/wyrdsekai/server/http/StudyRoutes.java";
        var fromServer = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromServer) ? fromServer
                : Paths.get(rel));
    }

    /** THE case: the claim is atomic and the duplicate is told, not raced. */
    @Test
    void a_duplicate_submit_is_refused_with_409() throws Exception {
        var s = src();

        assertThat(s)
            .as("add() on a concurrent set is the atomic claim — check-then-act would race")
            .contains("if (!ACTIVE_INGESTS.add(ingestKey)) {");
        assertThat(s).contains("ctx.status(409)");
        assertThat(s).contains("\"already_indexing\"");
    }

    /** Keyed per (owner, collection) — two DIFFERENT collections may run. */
    @Test
    void different_collections_are_not_blocked_by_each_other() throws Exception {
        assertThat(src())
            .contains("var ingestKey = owner + \"|\" + collection;");
    }

    /** The slot is released in finally — a failed job must not wedge future runs. */
    @Test
    void the_slot_is_always_released() throws Exception {
        var s = src();
        int finallyIdx = s.indexOf("} finally {");
        assertThat(finallyIdx).isGreaterThan(0);
        assertThat(s.substring(finallyIdx, finallyIdx + 400))
            .as("a leaked slot refuses every future ingest until restart")
            .contains("ACTIVE_INGESTS.remove(ingestKey);");
    }

    /** Node-global, because the ledger and workers are node-global. */
    @Test
    void the_claim_set_is_static_and_concurrent() throws Exception {
        assertThat(src()).contains(
            "private static final Set<String> ACTIVE_INGESTS = ConcurrentHashMap.newKeySet();");
    }
}
