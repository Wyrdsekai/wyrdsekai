package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.*;

/**
 * Sanity checks on the {@link EmbeddingModel} registry. The registry is the
 * source of truth for model id → metadata, so a typo in id, dimension, or
 * version would bypass the migration framework's per-row version check and
 * silently corrupt the Lucene index.
 */
class EmbeddingModelRegistryTest {

    @Test
    void registryHasExpectedEntries() {
        assertThat(EmbeddingModel.byId("paraphrase-l12")).isSameAs(EmbeddingModel.PARAPHRASE_L12);
        assertThat(EmbeddingModel.byId("e5-small")).isSameAs(EmbeddingModel.E5_SMALL);
        assertThat(EmbeddingModel.byId("e5-base")).isSameAs(EmbeddingModel.E5_BASE);
        assertThat(EmbeddingModel.byId("bge-m3")).isSameAs(EmbeddingModel.BGE_M3);
    }

    @Test
    void byIdIsCaseInsensitive() {
        assertThat(EmbeddingModel.byId("BGE-M3")).isSameAs(EmbeddingModel.BGE_M3);
        assertThat(EmbeddingModel.byId("E5-small")).isSameAs(EmbeddingModel.E5_SMALL);
        assertThat(EmbeddingModel.byId("  paraphrase-l12  ")).isSameAs(EmbeddingModel.PARAPHRASE_L12);
    }

    @Test
    void byIdReturnsNullForUnknown() {
        assertThat(EmbeddingModel.byId("nonsense")).isNull();
        assertThat(EmbeddingModel.byId("")).isNull();
        assertThat(EmbeddingModel.byId(null)).isNull();
    }

    @Test
    void byIdOrDefaultFallsBack() {
        assertThat(EmbeddingModel.byIdOrDefault("nonsense"))
            .isSameAs(EmbeddingModel.bundledDefault());
        assertThat(EmbeddingModel.byIdOrDefault(null))
            .isSameAs(EmbeddingModel.bundledDefault());
    }

    @Test
    void dimensionsAreCorrect() {
        assertThat(EmbeddingModel.PARAPHRASE_L12.dimension()).isEqualTo(384);
        assertThat(EmbeddingModel.E5_SMALL.dimension()).isEqualTo(384);
        assertThat(EmbeddingModel.E5_BASE.dimension()).isEqualTo(768);
        assertThat(EmbeddingModel.BGE_M3.dimension()).isEqualTo(1024);
    }

    @Test
    void noDuplicateIds() {
        var seen = new HashSet<String>();
        for (var m : EmbeddingModel.all()) {
            assertThat(seen.add(m.id()))
                .as("duplicate id: %s", m.id())
                .isTrue();
        }
        assertThat(seen).hasSize(EmbeddingModel.all().size());
    }

    @Test
    void noModelClaimsToBeClasspathBundled() {
        // None of the registered embedding models ship inside the JAR — they
        // are all fetched at packaging time (paraphrase-l12 default) or at
        // operator request (`wyrd embedding-model download <id>`). The
        // bundled() flag is preserved on the builder for future use but must
        // currently be false everywhere.
        assertThat(EmbeddingModel.all().stream().filter(EmbeddingModel::bundled))
            .as("no registered model should claim bundled=true")
            .isEmpty();
    }

    @Test
    void versionsAreDated() {
        // Migration framework keys off the version string; a bare model id without
        // a date suffix won't differentiate between two consecutive swaps of the
        // same model family.
        for (var m : EmbeddingModel.all()) {
            assertThat(m.version())
                .as("%s.version()", m.id())
                .matches(".*\\d{4}-\\d{2}-\\d{2}.*");
        }
    }

    @Test
    void downloadUrlsResolveForNonBundled() {
        // Every non-bundled model needs a working HF resolve URL, otherwise
        // `wyrd embedding-model download <id>` has nothing to fetch.
        for (var m : EmbeddingModel.all()) {
            if (m.bundled()) continue;
            assertThat(m.onnxDownloadUrl())
                .as("%s.onnxDownloadUrl()", m.id())
                .startsWith("https://huggingface.co/")
                .contains(m.hfId());
            assertThat(m.tokenizerDownloadUrl())
                .as("%s.tokenizerDownloadUrl()", m.id())
                .startsWith("https://huggingface.co/");
        }
    }

    @Test
    void minRamHintsAreMonotonicWithSize() {
        // Larger models should never declare a lower RAM floor than smaller ones.
        // Catches accidental edits that would steer the recommender wrong.
        assertThat(EmbeddingModel.E5_SMALL.minRamGB())
            .isLessThanOrEqualTo(EmbeddingModel.E5_BASE.minRamGB());
        assertThat(EmbeddingModel.E5_BASE.minRamGB())
            .isLessThanOrEqualTo(EmbeddingModel.BGE_M3.minRamGB());
    }
}
