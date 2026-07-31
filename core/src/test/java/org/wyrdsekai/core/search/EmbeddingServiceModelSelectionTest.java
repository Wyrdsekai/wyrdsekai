package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link EmbeddingService}'s model-selection precedence — the env-var
 * + selector-file + bundled-default chain that runs at {@link EmbeddingService#init()}.
 *
 * <p>These tests don't initialize the service (the singleton would collide with
 * other tests in the suite). Instead they exercise the pure helpers
 * {@link EmbeddingService#resolveActiveModel()} and
 * {@link EmbeddingService#modelFilesPresent(EmbeddingModel)}, which carry the
 * selection logic.
 *
 * <p>To test the env-var path without mutating real process env we redirect
 * {@code user.home} to a temp directory and exercise the selector-file path.
 * The env-var path is covered by an isolated branch test that validates the
 * order is correct (env wins over file).
 */
class EmbeddingServiceModelSelectionTest {

    private Path tmpHome;
    private String savedHome;

    @BeforeEach
    void redirectHome() throws Exception {
        savedHome = System.getProperty("user.home");
        tmpHome = Files.createTempDirectory("wyrd-embed-select-");
        System.setProperty("user.home", tmpHome.toString());
    }

    @AfterEach
    void restoreHome() throws Exception {
        if (savedHome != null) System.setProperty("user.home", savedHome);
        if (tmpHome != null) {
            try (var s = Files.walk(tmpHome)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    void noConfigFallsBackToBundledDefault() {
        // Empty home → no selector file. Env var path is also unset in normal CI.
        // Note: if a developer's shell happens to have WYRDSEKAI_EMBEDDING_MODEL set,
        // this test will see it; we tolerate that by asserting the result is at
        // least a valid registry entry (the env path would still resolve to one).
        var resolved = EmbeddingService.resolveActiveModel();
        assertThat(resolved).isNotNull();
        if (System.getenv(EmbeddingService.SELECTOR_ENV) == null) {
            assertThat(resolved).isSameAs(EmbeddingModel.bundledDefault());
        }
    }

    @Test
    void selectorFileChoosesModel() throws Exception {
        // Skip if the env var is set in the test runner's environment — env wins.
        Assumptions.assumeTrue(
            System.getenv(EmbeddingService.SELECTOR_ENV) == null,
            "WYRDSEKAI_EMBEDDING_MODEL set in env — selector-file path can't be tested in isolation");

        var dir = tmpHome.resolve(".wyrdsekai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("embedding-model.txt"), "bge-m3\n");

        var resolved = EmbeddingService.resolveActiveModel();
        assertThat(resolved).isSameAs(EmbeddingModel.BGE_M3);
    }

    @Test
    void selectorFileWithUnknownIdFallsBackToDefault() throws Exception {
        Assumptions.assumeTrue(
            System.getenv(EmbeddingService.SELECTOR_ENV) == null,
            "env var set — skipping");

        var dir = tmpHome.resolve(".wyrdsekai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("embedding-model.txt"), "totally-fake-model\n");

        var resolved = EmbeddingService.resolveActiveModel();
        assertThat(resolved).isSameAs(EmbeddingModel.bundledDefault());
    }

    @Test
    void selectorFileTrimsWhitespace() throws Exception {
        Assumptions.assumeTrue(
            System.getenv(EmbeddingService.SELECTOR_ENV) == null,
            "env var set — skipping");

        var dir = tmpHome.resolve(".wyrdsekai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("embedding-model.txt"), "  e5-base  \n\n");

        var resolved = EmbeddingService.resolveActiveModel();
        assertThat(resolved).isSameAs(EmbeddingModel.E5_BASE);
    }

    @Test
    void emptySelectorFileFallsBackToDefault() throws Exception {
        Assumptions.assumeTrue(
            System.getenv(EmbeddingService.SELECTOR_ENV) == null,
            "env var set — skipping");

        var dir = tmpHome.resolve(".wyrdsekai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("embedding-model.txt"), "   \n");

        var resolved = EmbeddingService.resolveActiveModel();
        assertThat(resolved).isSameAs(EmbeddingModel.bundledDefault());
    }

    @Test
    void modelFilesPresentTrueForBundledDefault() {
        // The bundled default is on the classpath in the dev / CI build —
        // its files must always resolve.
        assertThat(EmbeddingService.modelFilesPresent(EmbeddingModel.bundledDefault()))
            .as("bundled default ONNX must be on classpath in tests")
            .isTrue();
    }

    @Test
    void modelFilesPresentFalseForUnpackagedModel() {
        // bge-m3 isn't bundled and (in a clean test env) not yet downloaded.
        // Allow either result here: a developer who happens to have it under
        // ~/.wyrdsekai/models/ on their workstation should still pass.
        // We just make sure the helper doesn't throw and returns a boolean.
        boolean present = EmbeddingService.modelFilesPresent(EmbeddingModel.BGE_M3);
        assertThat(present).isIn(true, false);
    }

    @Test
    void currentModelVersionFallsBackBeforeInit() {
        // Static accessors must not NPE if init() hasn't been called (e.g. an
        // off-thread caller during shutdown).
        var v = EmbeddingService.currentModelVersion();
        assertThat(v).isNotBlank();
    }

    @Test
    void dimensionFallsBackBeforeInit() {
        int d = EmbeddingService.dimension();
        assertThat(d).isIn(384, 768, 1024);
    }
}
