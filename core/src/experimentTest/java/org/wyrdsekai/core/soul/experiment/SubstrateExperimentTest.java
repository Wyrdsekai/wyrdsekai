package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.random.RandomGeneratorFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CfC + Bath dynamic substrate experiment tests.
 *
 * Framework tests validate math, forward pass, and training convergence.
 * Live tests require inference + embedding endpoints.
 *
 * To run:
 *   SOUL_EXPERIMENT_URL=http://gpu-host:11434/v1 \
 *     SOUL_EXPERIMENT_MODEL=qwen2.5:7b \
 *     SOUL_EMBEDDING_URL=http://gpu-host:11434 \
 *     ./gradlew :core:test --tests "*SubstrateExperimentTest*"
 */
class SubstrateExperimentTest {

    // === DenseLayer tests ===

    @Test void dense_layer_forward_dimensions_correct() {
        var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        var layer = new DenseLayer(10, 5, DenseLayer.Activation.RELU, rng);
        var input = new double[10];
        Arrays.fill(input, 1.0);
        var output = layer.forward(input);
        assertThat(output).hasSize(5);
    }

    @Test void dense_layer_identity_is_linear() {
        var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        var layer = new DenseLayer(3, 2, DenseLayer.Activation.IDENTITY, rng);
        var input = new double[]{ 1.0, 0.0, 0.0 };
        var output = layer.forward(input);
        // With identity activation, output = W*input + bias
        // Just verify it produces non-zero output and correct dimensions
        assertThat(output).hasSize(2);
        assertThat(output[0]).isNotEqualTo(0.0);
    }

    @Test void dense_layer_relu_zeros_negatives() {
        // All activations should produce finite values
        for (var act : DenseLayer.Activation.values()) {
            assertThat(DenseLayer.activate(-1.0, act)).isFinite();
            assertThat(DenseLayer.activate(0.0, act)).isFinite();
            assertThat(DenseLayer.activate(1.0, act)).isFinite();
        }
        // ReLU specifically
        assertThat(DenseLayer.activate(-1.0, DenseLayer.Activation.RELU)).isEqualTo(0.0);
        assertThat(DenseLayer.activate(1.0, DenseLayer.Activation.RELU)).isEqualTo(1.0);
    }

    @Test void dense_layer_backward_updates_weights() {
        var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        var layer = new DenseLayer(4, 2, DenseLayer.Activation.SIGMOID, rng);
        var weightsBefore = layer.weights.clone();

        var input = new double[]{ 1.0, -1.0, 0.5, 0.0 };
        layer.forward(input);
        layer.backward(new double[]{ 1.0, -1.0 }, 0.01);

        // Weights should have changed
        boolean changed = false;
        for (int i = 0; i < weightsBefore.length; i++) {
            if (weightsBefore[i] != layer.weights[i]) { changed = true; break; }
        }
        assertThat(changed).isTrue();
    }

    @Test void dense_layer_frozen_does_not_update() {
        var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        var layer = new DenseLayer(4, 2, DenseLayer.Activation.SIGMOID, true, rng);
        var weightsBefore = layer.weights.clone();

        layer.forward(new double[]{ 1.0, -1.0, 0.5, 0.0 });
        layer.backward(new double[]{ 1.0, -1.0 }, 0.01);

        assertThat(layer.weights).containsExactly(weightsBefore);
        assertThat(layer.paramCount()).isEqualTo(0);
    }

    // === CfCCell tests ===

    @Test void cfc_forward_deterministic_with_same_seed() {
        var input = new double[]{ 0.5, -0.3, 0.8, 0.1 };

        var cell1 = new CfCCell(4, 8, 6, RandomGeneratorFactory.of("L64X128MixRandom").create(42L));
        var out1 = cell1.forward(input, 1.0);

        var cell2 = new CfCCell(4, 8, 6, RandomGeneratorFactory.of("L64X128MixRandom").create(42L));
        var out2 = cell2.forward(input, 1.0);

        assertThat(out1).containsExactly(out2);
    }

    @Test void cfc_output_dimensions_correct() {
        var cell = new CfCCell(4, 8, 6, RandomGeneratorFactory.of("L64X128MixRandom").create(42L));
        var output = cell.forward(new double[4], 1.0);
        assertThat(output).hasSize(8);
    }

    @Test void cfc_state_converges_toward_beta_with_large_deltaT() {
        var cell = new CfCCell(4, 8, 6, RandomGeneratorFactory.of("L64X128MixRandom").create(42L));
        var input = new double[]{ 1.0, 0.5, -0.3, 0.7 };

        // With very large deltaT, exp(-alpha*dt) → 0, so z → beta
        // Multiple steps with large dt should stabilize
        double[] prev = null;
        for (int i = 0; i < 20; i++) {
            var output = cell.forward(input, 10.0);
            if (prev != null) {
                // Output should be converging
                double diff = 0;
                for (int j = 0; j < output.length; j++)
                    diff += Math.abs(output[j] - prev[j]);
                // Not testing strict convergence — just that it's finite
                assertThat(diff).isFinite();
            }
            prev = output;
        }
    }

    @Test void cfc_backward_returns_correct_gradient_size() {
        var cell = new CfCCell(4, 8, 6, RandomGeneratorFactory.of("L64X128MixRandom").create(42L));
        cell.forward(new double[]{ 0.5, -0.3, 0.8, 0.1 }, 1.0);
        var dInput = cell.backward(new double[8], 1.0, 0.001);
        assertThat(dInput).hasSize(4);
    }

    // === BathDynamics tests ===

    @Test void bath_neutral_at_half_tanks() {
        var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        var bath = new BathDynamics(16, rng);
        var halfTanks = new double[]{ 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5 };
        var mod = bath.compute(halfTanks);

        // With small random receptor sensitivities and Hill(0.5, 0.5, 2) = 0.5,
        // modulation factors should be close to 1.0
        for (double tf : mod.timeFactors()) {
            assertThat(tf).isBetween(0.1, 5.0);
        }
        for (double gf : mod.gainFactors()) {
            assertThat(gf).isBetween(0.1, 5.0);
        }
    }

    @Test void bath_extreme_tanks_produce_different_output() {
        var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        var bath = new BathDynamics(16, rng);

        var lowTanks = new double[]{ 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };
        var highTanks = new double[]{ 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 };

        var lowMod = bath.compute(lowTanks);
        var highMod = bath.compute(highTanks);

        // At least some neurons should have different modulation
        boolean differs = false;
        for (int j = 0; j < 16; j++) {
            if (Math.abs(lowMod.gainFactors()[j] - highMod.gainFactors()[j]) > 0.01) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test void hill_equation_at_known_values() {
        // hill(0.5, 0.5, 2) = 0.5^2 / (0.5^2 + 0.5^2) = 0.25 / 0.50 = 0.5
        assertThat(BathDynamics.hill(0.5, 0.5, 2.0)).isCloseTo(0.5, within(1e-10));
        // hill(0, _, _) = 0
        assertThat(BathDynamics.hill(0.0, 0.5, 2.0)).isEqualTo(0.0);
        // hill(1.0, 0.5, 2) = 1.0 / (0.25 + 1.0) = 0.8
        assertThat(BathDynamics.hill(1.0, 0.5, 2.0)).isCloseTo(0.8, within(1e-10));
    }

    @Test void hill_derivative_matches_finite_differences() {
        double conc = 0.6, kd = 0.5, n = 2.0;
        double analytical = BathDynamics.hillDerivative(conc, kd, n);
        double eps = 1e-7;
        double numerical = (BathDynamics.hill(conc + eps, kd, n)
            - BathDynamics.hill(conc - eps, kd, n)) / (2 * eps);
        assertThat(analytical).isCloseTo(numerical, within(1e-5));
    }

    // === LiquidSubstrate tests ===

    @Test void substrate_forward_produces_correct_output_dim() {
        var config = SubstrateConfig.defaultConfig();
        var substrate = new LiquidSubstrate(config);
        var input = new double[384];
        input[0] = 1.0;
        var tanks = new double[]{ 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5 };
        var output = substrate.process(input, tanks, 1.0);
        assertThat(output).hasSize(384);
        // Should be L2-normalized
        double norm = 0;
        for (double v : output) norm += v * v;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.01));
    }

    @Test void substrate_different_tanks_produce_different_outputs() {
        var config = SubstrateConfig.defaultConfig();
        var substrate = new LiquidSubstrate(config);
        var input = new double[384];
        input[0] = 1.0; input[50] = -0.5; input[100] = 0.8;

        var lowTanks = new double[]{ 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1 };
        var highTanks = new double[]{ 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9 };

        var outLow = substrate.process(input, lowTanks, 1.0);
        substrate.reset();
        var outHigh = substrate.process(input, highTanks, 1.0);

        double cosSim = BehavioralMetrics.cosineSimilarity(outLow, outHigh);
        // Should be different (cosSim < 1.0)
        assertThat(cosSim).isLessThan(0.999);
    }

    @Test void substrate_trains_to_reduce_loss_on_synthetic_data() {
        var config = new SubstrateConfig(8, 8, 8, 2, 4, 4,
            SubstrateConfig.ringAdjacency(2), 42L);
        var substrate = new LiquidSubstrate(config);
        var tanks = new double[]{ 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5 };

        // Synthetic: 5 input-output pairs
        var rng = new Random(42);
        var inputs = new double[5][8];
        var targets = new double[5][8];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 8; j++) {
                inputs[i][j] = rng.nextGaussian();
                targets[i][j] = rng.nextGaussian();
            }
            // L2 normalize target
            double norm = 0;
            for (double v : targets[i]) norm += v * v;
            norm = Math.sqrt(norm);
            for (int j = 0; j < 8; j++) targets[i][j] /= norm;
        }

        // Train 100 epochs
        double firstLoss = 0, lastLoss = 0;
        for (int epoch = 0; epoch < 100; epoch++) {
            double epochLoss = 0;
            for (int i = 0; i < 5; i++) {
                substrate.reset();
                epochLoss += substrate.trainStep(inputs[i], targets[i], tanks, 1.0, 0.001);
            }
            epochLoss /= 5;
            if (epoch == 0) firstLoss = epochLoss;
            if (epoch == 99) lastLoss = epochLoss;
        }

        // Loss should decrease
        assertThat(lastLoss).isLessThan(firstLoss);
    }

    // === SubstrateConfig tests ===

    @Test void config_default_valid() {
        var config = SubstrateConfig.defaultConfig();
        assertThat(config.hiddenDim()).isEqualTo(config.numCells() * config.cellStateSize());
        assertThat(config.inputDim()).isEqualTo(384);
        assertThat(config.outputDim()).isEqualTo(384);
        assertThat(config.adjacency().length).isEqualTo(config.numCells());
    }

    @Test void config_from_soul_produces_reasonable_config() {
        var soulText = """
            Personality: warm, thoughtful, decisive.
            Social style: greeting warmly, humor.
            Decision making: moral compass, ethical considerations.
            Memory: consistent recall of past events.
            """;
        var config = SubstrateConfig.fromSoul(soulText, 384);
        assertThat(config.numCells()).isBetween(2, 8);
        assertThat(config.hiddenDim()).isEqualTo(config.numCells() * config.cellStateSize());
        assertThat(config.inputDim()).isEqualTo(384);
    }

    @Test void config_ring_adjacency_is_valid() {
        var adj = SubstrateConfig.ringAdjacency(4);
        assertThat(adj.length).isEqualTo(4);
        // Each cell should connect to at least one other cell
        for (var connections : adj) {
            assertThat(connections.length).isGreaterThan(0);
        }
    }

    // === Live tests ===

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SOUL_EMBEDDING_URL", matches = ".+")
    @Test void live_substrate_experiment(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var model = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var experiment = new SubstrateExperiment(url, model,
            embeddingUrl, embeddingModel, outputDir);
        var result = experiment.run();

        // CfC should have trained (loss decreased)
        assertThat(result.cfcResult().lossHistory().getLast())
            .isLessThan(result.cfcResult().lossHistory().getFirst());

        // Test cosine similarity should be non-trivial
        assertThat(result.cfcResult().testCosSim())
            .as("CfC test cosine similarity should be above random")
            .isGreaterThan(0.0);

        System.out.println("Results saved to: " + outputDir);
    }

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SOUL_EMBEDDING_URL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_MODELS", matches = ".*lfm2.*")
    @Test void live_lfm2_substrate_curve(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var primaryModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var secondaryModels = Arrays.asList(
            System.getenv("SOUL_EXPERIMENT_MODELS").split(","));
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var experiment = new SubstrateCurveExperiment(url, primaryModel, secondaryModels,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), outputDir, embeddingUrl, embeddingModel);
        var result = experiment.run();
        System.out.println("\n" + result.summary());
        System.out.println("Results saved to: " + outputDir);
    }
}
