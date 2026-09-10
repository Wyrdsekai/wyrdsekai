package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Not a test of correctness: a throughput probe for the sizing question "how long would a
 * re-embed of N chunks take on this box". Enabled by WYRDSEKAI_EMBED_PROBE=1; prints
 * chunks/second for the configured embedding model on the current CPU.
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_EMBED_PROBE", matches = "1")
class EmbeddingThroughputProbeTest {

    @Test
    void chunks_per_second() throws Exception {
        var svc = EmbeddingService.init();
        var texts = new ArrayList<String>();
        var para = "The Librarian explained to Kestan that a vel-shara is a speech with magical force; "
            + "in Sumerian the word means an incantation, and the vel-shara of Adrun was a counter-program "
            + "that stopped the tongues of the people from being of one speech. ";
        int n = Integer.parseInt(System.getenv().getOrDefault("WYRDSEKAI_EMBED_PROBE_N", "2000"));
        for (int i = 0; i < n; i++) texts.add(para.repeat(3) + " part " + i);
        // warm-up
        svc.embedBatch(texts.subList(0, 64));
        long t0 = System.nanoTime();
        int done = 0;
        for (int i = 0; i < texts.size(); i += 64) {
            var batch = texts.subList(i, Math.min(texts.size(), i + 64));
            List<List<Float>> out = svc.embedBatch(batch);
            done += out.size();
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        var dim = svc.embed(texts.getFirst()).size();
        System.out.printf("EMBED_PROBE model=%s dim=%d chunks=%d secs=%.1f rate=%.0f/s cores=%d%n",
            EmbeddingService.currentModelVersion(), dim, done, secs, done / secs,
            Runtime.getRuntime().availableProcessors());
    }
}
