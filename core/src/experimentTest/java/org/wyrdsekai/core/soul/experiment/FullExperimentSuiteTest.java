package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full experiment suite tests.
 *
 * Live test requires inference endpoint + embedding endpoint + optionally multiple models.
 *
 * To run the full suite:
 *   SOUL_EXPERIMENT_URL=http://gpu-host:8090/v1 \
 *     SOUL_EXPERIMENT_MODEL=qwen2.5:7b \
 *     SOUL_EXPERIMENT_MODELS=qwen3:0.6b,qwen2.5:7b \
 *     SOUL_EMBEDDING_URL=http://gpu-host:11434 \
 *     ./gradlew :core:test --tests "*FullExperimentSuiteTest.live*"
 */
class FullExperimentSuiteTest {

    // === Framework test ===

    @Test void suite_class_instantiates() {
        var suite = new FullExperimentSuite(
            "http://localhost:11434/v1", "test-model",
            List.of("small-model"), null, null, null);
        assertThat(suite).isNotNull();
    }

    // === Live test ===

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @Test void live_full_suite(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var primaryModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var modelsEnv = System.getenv("SOUL_EXPERIMENT_MODELS");
        var secondaryModels = modelsEnv != null
            ? Arrays.asList(modelsEnv.split(","))
            : List.<String>of();
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var suite = new FullExperimentSuite(url, primaryModel, secondaryModels,
            outputDir, embeddingUrl, embeddingModel);
        var report = suite.run();

        System.out.println("\n" + report);
        assertThat(report).contains("DECISION GATE");
        assertThat(report).contains("RECOMMENDATION");

        System.out.println("Full report saved to: " + outputDir);
    }
}
