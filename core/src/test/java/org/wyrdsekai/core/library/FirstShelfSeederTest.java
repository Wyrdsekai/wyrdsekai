package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * a fresh zone is born with a real Library: the Book of the World
 * the reference core, and the trilingual culture seed, all searchable with provenance; and
 * seeding is idempotent across boots.
 */
class FirstShelfSeederTest {

    @Test
    void fresh_store_gains_a_searchable_first_shelf(@TempDir Path tmp) throws Exception {
        var lucene = newStore(tmp);
        int written = FirstShelfSeeder.seed(lucene);
        assertThat(written).as("bundled content seeded").isGreaterThan(13);

        // The Book of the World: agents can read about their own world...
        assertThat(top(lucene, "what sleep does the Forge consolidation"))
            .contains("Forge");
        // ...including the world.* API for skill authors.
        assertThat(top(lucene, "searchKnowledge skill author capability manifest"))
            .contains("world.");
        // Reference core: anchor-shaped facts (the verifier's densest substrate).
        assertThat(top(lucene, "poultry internal temperature"))
            .contains("165");
        // Trilingual culture seed — es and ja are first-class.
        assertThat(top(lucene, "Sor Juana derecho al estudio"))
            .contains("Sor Juana");
        assertThat(top(lucene, "もののあはれ 源氏物語"))
            .contains("もののあはれ");
    }

    @Test
    void reseeding_same_version_is_a_noop(@TempDir Path tmp) throws Exception {
        var lucene = newStore(tmp);
        int first = FirstShelfSeeder.seed(lucene);
        assertThat(first).isGreaterThan(0);
        long countAfterFirst = lucene.listKnowledgePacks().getOrDefault(FirstShelfSeeder.PACK, 0L);
        assertThat(countAfterFirst).isGreaterThan(0);

        int second = FirstShelfSeeder.seed(lucene);
        assertThat(second).as("version marker short-circuits the second boot").isZero();
        long countAfterSecond = lucene.listKnowledgePacks().getOrDefault(FirstShelfSeeder.PACK, 0L);
        assertThat(countAfterSecond).as("re-seed leaves the index unchanged").isEqualTo(countAfterFirst);
    }

    @Test
    void manifest_lists_every_bundled_file() throws Exception {
        var manifest = FirstShelfSeeder.readManifest();
        assertThat(manifest).isNotNull();
        assertThat(manifest.entries()).hasSizeGreaterThanOrEqualTo(13);
        assertThat(manifest.entries()).extracting(FirstShelfSeeder.ManifestEntry::language)
            .contains("en", "es", "ja");
        for (var e : manifest.entries()) {
            try (var is = FirstShelfSeeder.class.getResourceAsStream("/first-shelf/" + e.path())) {
                assertThat(is).as("bundled file exists: %s", e.path()).isNotNull();
            }
        }
    }

    private static WyrdLuceneStore newStore(Path tmp) throws Exception {
        Path dir = tmp.resolve("lucene");
        Files.createDirectories(dir);
        return new WyrdLuceneStore(dir, 384);
    }

    private static String top(WyrdLuceneStore lucene, String query) {
        var hits = lucene.searchKnowledgeText(query, 3);
        assertThat(hits).as("hits for '%s'", query).isNotEmpty();
        return hits.stream().map(h -> h.content()).reduce("", (a, b) -> a + "\n" + b);
    }
}
