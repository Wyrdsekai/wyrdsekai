package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.library.Provenance;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (authoring time, stage 1) — proves anchor mining:
 * <ul>
 *   <li>mined anchors carry the provenance of the snippet they came from;</li>
 *   <li>the leakage barrier drops anchors the model failed to ground in a real source;</li>
 *   <li>no evidence retrieved &rarr; no anchors (honest "unverified", never invented);</li>
 *   <li>the full chain mine &rarr; {@link HarnessGenerator} &rarr; {@link SkillVerifier}
 *       discriminates a correct skill from a buggy one.</li>
 * </ul>
 * Both the retrieval and the model are stubs here; the SAME code runs against the Library
 * pipeline + the local 9B / a cloud model (the injected-function cloud-optional seam).
 */
class AnchorMinerTest {

    private static final String GOOD = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";
    private static final String BUGGY = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 }; }";

    private static final SourcedSnippet FREEZE = new SourcedSnippet(
        "At standard pressure water freezes at 0 degrees Celsius, which is 32 degrees Fahrenheit.",
        new Provenance.Source("wiki", "Celsius", "https://en.wikipedia.org/wiki/Celsius",
            "Celsius — Wikipedia", List.of(), 2026),
        Provenance.TrustTier.WIKI);

    private static final SourcedSnippet BOIL = new SourcedSnippet(
        "Water boils at 100 degrees Celsius, equal to 212 degrees Fahrenheit, at sea level.",
        new Provenance.Source("wiki", "Fahrenheit", "https://en.wikipedia.org/wiki/Fahrenheit",
            "Fahrenheit — Wikipedia", List.of(), 2026),
        Provenance.TrustTier.WIKI);

    private static Function<String, List<SourcedSnippet>> retrieves(SourcedSnippet... s) {
        return q -> List.of(s);
    }

    @Test
    void mined_anchors_carry_their_source_provenance() {
        // Model cites snippet [0] and [1] — both valid.
        String modelOut = """
            {"anchors":[
              {"fact":"water freezes at 0C = 32F","kind":"REFERENCE_VALUE","sourceIndex":0},
              {"fact":"water boils at 100C = 212F","kind":"REFERENCE_VALUE","sourceIndex":1}
            ]}""";
        var miner = new ModelAnchorMiner(retrieves(FREEZE, BOIL), p -> modelOut);

        var anchors = miner.mine("celsius_to_fahrenheit", "Convert Celsius to Fahrenheit", GOOD);

        assertThat(anchors).hasSize(2);
        assertThat(anchors).allMatch(VerificationAnchor::isGrounded);
        assertThat(anchors.get(0).source().url()).isEqualTo("https://en.wikipedia.org/wiki/Celsius");
        assertThat(anchors.get(0).trustTier()).isEqualTo(Provenance.TrustTier.WIKI);
        assertThat(anchors.get(1).source().title()).isEqualTo("Fahrenheit — Wikipedia");
    }

    @Test
    void leakage_barrier_drops_ungrounded_and_out_of_range_anchors() {
        // [0] valid; second has no sourceIndex (guess); third cites a snippet that doesn't exist.
        String modelOut = """
            {"anchors":[
              {"fact":"water freezes at 0C = 32F","kind":"REFERENCE_VALUE","sourceIndex":0},
              {"fact":"absolute zero is -273.15C","kind":"REFERENCE_VALUE"},
              {"fact":"the meaning of life is 42","kind":"REFERENCE_VALUE","sourceIndex":7}
            ]}""";
        var miner = new ModelAnchorMiner(retrieves(FREEZE), p -> modelOut);

        var anchors = miner.mine("celsius_to_fahrenheit", "Convert Celsius to Fahrenheit", GOOD);

        assertThat(anchors).as("only the cited, in-range anchor survives the leakage barrier").hasSize(1);
        assertThat(anchors.get(0).fact()).isEqualTo("water freezes at 0C = 32F");
    }

    @Test
    void no_evidence_means_no_anchors_never_invented() {
        var miner = new ModelAnchorMiner(q -> List.of(), p -> {
            throw new AssertionError("model must not be called when there is no evidence");
        });
        assertThat(miner.mine("s", "d", GOOD)).isEmpty();
    }

    @Test
    void mine_then_generate_then_verify_discriminates_good_from_buggy() {
        // Stage 1: mine anchors (model A cites the evidence).
        String minerOut = """
            {"anchors":[
              {"fact":"water freezes at 0C = 32F","kind":"REFERENCE_VALUE","sourceIndex":0},
              {"fact":"water boils at 100C = 212F","kind":"REFERENCE_VALUE","sourceIndex":1}
            ]}""";
        var miner = new ModelAnchorMiner(retrieves(FREEZE, BOIL), p -> minerOut);
        var anchors = miner.mine("celsius_to_fahrenheit", "Convert Celsius to Fahrenheit", GOOD);
        assertThat(anchors).hasSize(2);

        // Stage 2: compile mined facts into a frozen harness (model B turns facts into cases).
        String harnessOut = """
            {"skillName":"celsius_to_fahrenheit","cases":[
              {"params":{"celsius":0},"outputKey":"fahrenheit",
               "check":{"kind":"NUMERIC_EQUALS","expected":32.0,"epsilon":1e-9},"source":"water freezes at 0C = 32F"},
              {"params":{"celsius":100},"outputKey":"fahrenheit",
               "check":{"kind":"NUMERIC_EQUALS","expected":212.0,"epsilon":1e-9},"source":"water boils at 100C = 212F"}
            ]}""";
        var generator = new ModelHarnessGenerator(p -> harnessOut);
        AnchorHarness harness = generator.generate("celsius_to_fahrenheit",
            "Convert Celsius to Fahrenheit", GOOD, anchors.stream().map(VerificationAnchor::fact).toList());
        assertThat(harness).isNotNull();
        assertThat(harness.cases()).hasSize(2);

        // Stage 3: the frozen harness gates skills — deterministic, no model.
        var verifier = new SkillVerifier(new ItemScriptExecutor());
        var restricted = ItemCapabilitySet.of(List.of());
        assertThat(verifier.verify("good", GOOD, harness, StubItemWorldApiProvider.INSTANCE, restricted).passed())
            .as("correct skill passes anchors mined from the open world").isTrue();
        assertThat(verifier.verify("buggy", BUGGY, harness, StubItemWorldApiProvider.INSTANCE, restricted).passed())
            .as("buggy skill (missing +32) fails the mined anchors").isFalse();
    }
}
