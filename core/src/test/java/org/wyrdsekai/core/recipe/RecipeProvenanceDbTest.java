package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 — the provenance instrument over real SQLite:
 * {@link SqlRecipeQueue#completedSince} + {@link AuthoredRecipeLog} feeding
 * {@link RecipeProvenanceReport#compute(SqlRecipeQueue, RecipeProvenanceReport.Window, int)}.
 */
class RecipeProvenanceDbTest {

    @TempDir Path tmp;

    private String jdbc() {
        return "jdbc:sqlite:" + tmp.resolve("prov.db").toAbsolutePath();
    }

    private void seed(SqlRecipeQueue q, QueuedRecipe.TriggerSource src, Instant completedAt) {
        var id = UUID.randomUUID().toString();
        var row = new QueuedRecipe(id, "r", Map.of(), "reason", src,
            completedAt.minusSeconds(60), completedAt.minusSeconds(30), completedAt,
            QueuedRecipe.Status.SUCCEEDED, "did:test:a", CadenceTier.WARMUP, 0, "run", "ok");
        q.enqueue(row);
        q.markAttempted(id, completedAt.minusSeconds(30));
        q.markCompleted(id, QueuedRecipe.Status.SUCCEEDED, completedAt,
            CadenceTier.WARMUP, 0, "run", "ok");
    }

    @Test
    void queue_and_authored_log_combine_into_one_provenance() {
        var jdbc = jdbc();
        var queue = new SqlRecipeQueue(jdbc);
        var authored = new AuthoredRecipeLog(jdbc);
        var now = Instant.now();

        seed(queue, QueuedRecipe.TriggerSource.AGENT, now.minusSeconds(100));
        seed(queue, QueuedRecipe.TriggerSource.CRON, now.minusSeconds(90));
        seed(queue, QueuedRecipe.TriggerSource.GAP, now.minusSeconds(80));
        authored.record("did:test:a", "my-first-recipe", now.minusSeconds(70));
        authored.record("did:test:a", "my-second-recipe", now.minusSeconds(60));

        var from = now.minus(Duration.ofDays(1));
        int authoredCount = authored.countSince(from, null);
        assertThat(authoredCount).isEqualTo(2);

        var p = RecipeProvenanceReport.compute(queue,
            new RecipeProvenanceReport.Window(from, now.plusSeconds(1), null), authoredCount);

        assertThat(p.agent()).isEqualTo(1);       // 1 request_recipe run
        assertThat(p.authored()).isEqualTo(2);    // 2 shape_recipe acts
        assertThat(p.cron()).isEqualTo(1);
        assertThat(p.gap()).isEqualTo(1);
        assertThat(p.agentInitiated()).isEqualTo(3); // 1 + 2
        assertThat(p.total()).isEqualTo(5);          // 3 runs + 2 authored
        assertThat(p.agentFraction()).isEqualTo(0.6);
    }

    @Test
    void agent_scope_filters_to_one_did() {
        var jdbc = jdbc();
        var authored = new AuthoredRecipeLog(jdbc);
        var now = Instant.now();
        authored.record("did:test:a", "a1", now.minusSeconds(50));
        authored.record("did:test:b", "b1", now.minusSeconds(40));

        var from = now.minus(Duration.ofDays(1));
        assertThat(authored.countSince(from, "did:test:a")).isEqualTo(1);
        assertThat(authored.countSince(from, null)).isEqualTo(2);
    }
}
