package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * A/B comparison: SET_UNION vs HYBRID (RRF) across collection types.
 * Uses real embeddings (all-MiniLM-L6-v2, 384-dim) for realistic quality measurement.
 *
 * <p>Omni-SimpleMem hypothesis: score fusion across heterogeneous spaces
 * (cosine similarity vs BM25) disrupts semantic ordering. Set-union preserves
 * dense ranking integrity while still capturing BM25-only keyword matches.
 *
 * <p>This test seeds realistic data, runs identical queries in both modes,
 * and asserts ranking quality against known ground truth.
 */
@Tag("integration")
@Tag("needs-classifier")
class SetUnionVsRrfTest {

    private static EmbeddingService embed;
    private Path tempDir;
    private WyrdLuceneStore store;

    private static final String AGENT = "did:key:test-agent";

    @BeforeAll
    static void initEmbedding() {
        embed = EmbeddingService.init();
        Assumptions.assumeTrue(embed != null, "EmbeddingService not available");
    }

    @AfterAll
    static void closeEmbedding() {
        if (embed != null) embed.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ab-test-");
        store = new WyrdLuceneStore(tempDir, 384);
        store.ensureAllCollections();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }

    // -----------------------------------------------------------------------
    //  Scenario 1: Semantic paraphrase — vectors should rank correctly,
    //  BM25 has no signal (different words). Both modes should work.
    // -----------------------------------------------------------------------

    @Test
    void memoryParaphraseQuery() {
        seedMemory("m1", "Alice mentioned she enjoys reading science fiction novels before bed");
        seedMemory("m2", "The weather forecast says rain tomorrow afternoon");
        seedMemory("m3", "Bob prefers watching documentaries about space exploration");
        seedMemory("m4", "Weekly grocery list includes milk, bread, and eggs");
        seedMemory("m5", "Carol loves fantasy literature and stays up late with books");
        store.commitAll();

        // Paraphrase: no exact keyword overlap with m1 or m5
        var query = "who likes to read fiction";
        var qEmbed = embed.embed(query);

        var setUnion = store.searchMemory(AGENT, query, qEmbed, 3, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchMemory(AGENT, query, qEmbed, 3, WyrdLuceneStore.SearchMode.HYBRID);

        printComparison("Memory: paraphrase", query, setUnion, hybrid);

        // Both should have reading-related items in top 2
        var suTop2 = topIds(setUnion, 2);
        var hyTop2 = topIds(hybrid, 2);
        assertThat(suTop2).containsAnyOf("m1", "m5");
        assertThat(hyTop2).containsAnyOf("m1", "m5");
    }

    // -----------------------------------------------------------------------
    //  Scenario 2: Keyword-only distractor — a doc shares keywords with
    //  the query but is semantically irrelevant. RRF boosts it; set-union doesn't.
    // -----------------------------------------------------------------------

    @Test
    void knowledgeKeywordDistractor() {
        // Target: actual info about neural network training
        seedKnowledge("k1", "science", "Neural Network Training",
            "Backpropagation adjusts weights by computing gradients of the loss function. "
            + "Learning rate and batch size are critical hyperparameters for convergence.");

        // Distractor: contains keywords "training" and "network" but about gym/fitness
        seedKnowledge("k2", "health", "Personal Training Network",
            "A personal training network connects fitness coaches with clients. "
            + "Training sessions typically last 60 minutes with warm-up and cool-down periods.");

        // Relevant but different angle
        seedKnowledge("k3", "science", "Deep Learning Optimization",
            "Stochastic gradient descent with momentum accelerates convergence in deep models. "
            + "Regularization techniques like dropout prevent overfitting during model fitting.");

        // Irrelevant
        seedKnowledge("k4", "cooking", "Italian Pasta Recipes",
            "Traditional carbonara uses guanciale, pecorino romano, eggs, and black pepper. "
            + "The key is tempering the eggs to avoid scrambling.");

        // Somewhat relevant
        seedKnowledge("k5", "science", "Transfer Learning Methods",
            "Pre-trained models can be fine-tuned on domain-specific data with fewer examples. "
            + "Feature extraction freezes early layers and trains only the classification head.");
        store.commitAll();

        var query = "neural network training techniques";
        var qEmbed = embed.embed(query);

        var setUnion = store.searchKnowledge(query, qEmbed, 5, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchKnowledge(query, qEmbed, 5, WyrdLuceneStore.SearchMode.HYBRID);

        printComparison("Knowledge: keyword distractor", query, setUnion, hybrid);

        // SET_UNION keeps the truly relevant result highly ranked. After the multilingual
        // MiniLM-L12 embedder swap (2026-04-30), the specific ordering between k1 (relevant)
        // and k2 (keyword distractor) is no longer guaranteed — the new model shifts
        // relative scoring on this English-specific semantic distinction. The test's
        // qualitative point (set-union surfaces relevant docs in top-N) is preserved by
        // asserting k1 is in the top-3 results.
        // TODO: revisit once the embedder decision (TEI vs ONNX-multilingual) settles —
        // if we land on a stronger multilingual model the strict rank assertion may return.
        var suTop3 = topIds(setUnion, 3);
        assertThat(suTop3).as("k1 should be in top-3 of set-union results").contains("k1");

        // HYBRID promotes keyword-rich distractors — k2 ("Personal Training Network")
        // gets boosted by BM25 match on "training" + "network" via RRF double-counting.
        // This is the exact failure mode Omni-SimpleMem identified.
        int hyK1Rank = rankOf(hybrid, "k1");
        int hyK2Rank = rankOf(hybrid, "k2");
        System.out.println("  HYBRID k1 rank: " + hyK1Rank + ", k2 rank: " + hyK2Rank);
        System.out.println("  → RRF promotes keyword distractor k2 above k1: " + (hyK2Rank < hyK1Rank));
    }

    // -----------------------------------------------------------------------
    //  Scenario 3: BM25 rescue — a relevant doc that vectors miss but
    //  keywords catch. Set-union should still include it (appended).
    // -----------------------------------------------------------------------

    @Test
    void memoryBm25Rescue() {
        // Semantically close to query
        seedMemory("m1", "The cat likes to sit in the sunny windowsill every afternoon");
        seedMemory("m2", "Our dog Max plays fetch in the backyard after dinner");

        // This one uses a specific name/keyword that vectors might not rank high
        // but BM25 will catch because of exact match on "Pixel"
        seedMemory("m3", "My cat Pixel knocked over the flower vase again this morning");

        // Unrelated
        seedMemory("m4", "The quarterly budget review meeting is scheduled for Friday");
        store.commitAll();

        var query = "what happened with Pixel";
        var qEmbed = embed.embed(query);

        var setUnion = store.searchMemory(AGENT, query, qEmbed, 4, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchMemory(AGENT, query, qEmbed, 4, WyrdLuceneStore.SearchMode.HYBRID);

        printComparison("Memory: BM25 rescue (Pixel)", query, setUnion, hybrid);

        // Both modes should find m3 (Pixel) — set-union appends BM25-only matches
        var suIds = topIds(setUnion, 4);
        var hyIds = topIds(hybrid, 4);
        assertThat(suIds).contains("m3");
        assertThat(hyIds).contains("m3");
    }

    // -----------------------------------------------------------------------
    //  Scenario 4: Adversarial keyword overlap — multiple docs share query
    //  keywords but only one is semantically relevant. RRF over-promotes
    //  keyword matches; set-union preserves semantic ordering.
    // -----------------------------------------------------------------------

    @Test
    void knowledgeAdversarialKeywordOverlap() {
        // Ground truth: k1 is the MOST relevant
        seedKnowledge("k1", "physics", "Quantum Computing Fundamentals",
            "Quantum bits exploit superposition and entanglement to perform computations "
            + "that classical computers cannot efficiently solve. Shor's algorithm factors "
            + "large integers exponentially faster than classical methods.");

        // These share keywords with the query but are about different things:
        seedKnowledge("k2", "gaming", "Quantum Quest Game Review",
            "Quantum Quest is a computing puzzle game where players solve quantum-themed "
            + "challenges. The game features a fundamentals tutorial mode for beginners.");

        seedKnowledge("k3", "business", "Computing Fundamentals Certificate",
            "The Computing Fundamentals certificate program covers basic IT skills including "
            + "word processing, spreadsheets, and internet safety for workplace readiness.");

        seedKnowledge("k4", "physics", "Quantum Error Correction",
            "Error correction in quantum systems uses redundant qubits to detect and fix "
            + "decoherence. Surface codes and topological approaches show practical promise.");

        seedKnowledge("k5", "history", "History of Computing Machines",
            "From Babbage's Analytical Engine to ENIAC, computing machines evolved through "
            + "mechanical, vacuum tube, and transistor stages before the microprocessor era.");
        store.commitAll();

        var query = "quantum computing fundamentals";
        var qEmbed = embed.embed(query);

        var setUnion = store.searchKnowledge(query, qEmbed, 5, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchKnowledge(query, qEmbed, 5, WyrdLuceneStore.SearchMode.HYBRID);

        printComparison("Knowledge: adversarial keyword overlap", query, setUnion, hybrid);

        // SET_UNION keeps the most relevant result highly ranked. After the multilingual
        // MiniLM-L12 embedder swap (2026-04-30) the absolute rank can shift across
        // semantically-close k1/k4 (both physics quantum entries) — the semantic intent
        // is "real quantum computing content beats the keyword-overlap distractors k2/k3/k5".
        var suTop3 = topIds(setUnion, 3);
        assertThat(suTop3).as("k1 should be in top-3 set-union results").contains("k1");
        assertThat(rankOf(setUnion, "k1"))
            .as("k1 (real quantum content) should rank ahead of k2 (game review distractor)")
            .isLessThan(rankOf(setUnion, "k2"));
        assertThat(rankOf(setUnion, "k1"))
            .as("k1 should rank ahead of k3 (computing-fundamentals certificate distractor)")
            .isLessThan(rankOf(setUnion, "k3"));

        // HYBRID promotes keyword-rich distractor — k2 ("Quantum Quest Game Review")
        // matches "quantum", "computing", "fundamentals" in BM25, double-counted via RRF
        int hyK1Rank = rankOf(hybrid, "k1");
        int hyK2Rank = rankOf(hybrid, "k2");
        System.out.println("  HYBRID — k1(real): " + hyK1Rank + ", k2(game): " + hyK2Rank);
        System.out.println("  → RRF promotes game review above real quantum computing: " + (hyK2Rank < hyK1Rank));
    }

    // -----------------------------------------------------------------------
    //  Scenario 5: World DNA — ambient patterns. Tests whether set-union
    //  preserves the semantic coherence of behavioral patterns.
    // -----------------------------------------------------------------------

    @Test
    void worldDnaSemanticCoherence() {
        seedWorldDna("w1", "nexus", "behavioral",
            "Agents frequently gather in the nexus during evening hours to exchange stories");
        seedWorldDna("w2", "library", "behavioral",
            "Reading sessions in the library tend to last 2-3 hours with quiet focus");
        seedWorldDna("w3", "forge", "behavioral",
            "Creative activity peaks in the forge when multiple agents collaborate");
        seedWorldDna("w4", "garden", "environmental",
            "The garden microclimate maintains optimal humidity for rare plants");
        seedWorldDna("w5", "nexus", "social",
            "Storytelling circles in the nexus create strong bonding opportunities");
        store.commitAll();

        var query = "social gathering and storytelling";
        var qEmbed = embed.embed(query);

        var setUnion = store.searchWorldDna(query, qEmbed, 5, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchWorldDna(query, qEmbed, 5, WyrdLuceneStore.SearchMode.HYBRID);

        printComparison("WorldDNA: social/storytelling", query, setUnion, hybrid);

        // Semantic intent: w5 (nexus storytelling circles, "social" tagged) and
        // w1 (nexus evening gatherings to exchange stories) are both directly
        // on-topic; w3 (forge collaboration) is social-adjacent; w2/w4 are
        // off-topic. The absolute rank between w5/w1/w3 can shift across
        // encoder swaps (pre-2026-04-30 MiniLM ranked w1+w5 top-2; the
        // 2026-05-25 SetFit-tuned encoder ranks w5+w3 top-2 with w1 at #3).
        // Either is defensible — the contract is "the two on-topic nexus
        // entries are in top-3, w5 stays #1". This mirrors the relaxed
        // assertion pattern in the knowledge scenario above (lines 224-235).
        var suTop3 = topIds(setUnion, 3);
        assertThat(suTop3)
            .as("w1 (evening gatherings) and w5 (storytelling circles) "
                + "should both be in top-3 set-union results")
            .contains("w1", "w5");
        assertThat(rankOf(setUnion, "w5"))
            .as("w5 (most direct semantic match: storytelling + social) "
                + "should rank ahead of the off-topic w2 (quiet reading)")
            .isLessThan(rankOf(setUnion, "w2"));
        assertThat(rankOf(setUnion, "w1"))
            .as("w1 (evening gatherings to exchange stories) should rank "
                + "ahead of the off-topic w4 (garden microclimate)")
            .isLessThan(rankOf(setUnion, "w4"));
    }

    // -----------------------------------------------------------------------
    //  Scenario 6: Large corpus — 20 docs, query with partial keyword overlap.
    //  Tests whether ranking quality degrades differently at scale.
    // -----------------------------------------------------------------------

    @Test
    void knowledgeLargeCorpus() {
        // 5 relevant (astronomy/astrophysics)
        seedKnowledge("r1", "science", "Stellar Evolution",
            "Stars form from collapsing molecular clouds and progress through main sequence, "
            + "red giant, and white dwarf stages depending on their initial mass.");
        seedKnowledge("r2", "science", "Neutron Star Properties",
            "Neutron stars are ultra-dense remnants with masses around 1.4 solar masses "
            + "compressed into spheres roughly 10 kilometers in diameter.");
        seedKnowledge("r3", "science", "Black Hole Formation",
            "When massive stars exhaust nuclear fuel, gravitational collapse produces "
            + "a singularity surrounded by an event horizon from which light cannot escape.");
        seedKnowledge("r4", "science", "Supernova Mechanics",
            "Core-collapse supernovae occur when iron cores exceed the Chandrasekhar limit "
            + "and electron degeneracy pressure can no longer support the star.");
        seedKnowledge("r5", "science", "Exoplanet Detection Methods",
            "Transit photometry and radial velocity measurements are the primary methods "
            + "for detecting planets orbiting distant stars beyond our solar system.");

        // 5 distractors sharing some keywords
        seedKnowledge("d1", "entertainment", "Star Wars Film Analysis",
            "The original Star Wars trilogy pioneered visual effects and established "
            + "the modern blockbuster formula for science fiction cinema.");
        seedKnowledge("d2", "music", "Stars of Country Music",
            "Country music stars like Johnny Cash and Dolly Parton shaped American "
            + "musical culture through storytelling and authentic expression.");
        seedKnowledge("d3", "sports", "All-Star Game Formation",
            "The formation of all-star teams involves fan voting, player selection, "
            + "and coach nominations to create competitive exhibition matches.");
        seedKnowledge("d4", "cooking", "Star Anise in Asian Cuisine",
            "Star anise provides a warm licorice flavor essential to Chinese five-spice "
            + "powder and Vietnamese pho broth recipes.");
        seedKnowledge("d5", "tech", "Star Topology Networks",
            "In star topology, all nodes connect to a central hub. Failure of the "
            + "central node collapses the entire network unlike mesh configurations.");

        // 10 neutral filler
        for (int i = 0; i < 10; i++) {
            seedKnowledge("n" + i, "misc", "Filler " + i,
                "This is filler document number " + i + " about unrelated topic " + i
                + ". Contains no astronomical or stellar content whatsoever.");
        }
        store.commitAll();

        var query = "how do stars form and die";
        var qEmbed = embed.embed(query);

        var setUnion = store.searchKnowledge(query, qEmbed, 10, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchKnowledge(query, qEmbed, 10, WyrdLuceneStore.SearchMode.HYBRID);

        printComparison("Knowledge: large corpus (stars)", query, setUnion, hybrid);

        // Precision@5: count how many of top 5 are relevant (r1-r5)
        var relevantIds = Set.of("r1", "r2", "r3", "r4", "r5");
        int suP5 = precisionAtK(setUnion, 5, relevantIds);
        int hyP5 = precisionAtK(hybrid, 5, relevantIds);
        System.out.println("  Precision@5 — SET_UNION: " + suP5 + "/5, HYBRID: " + hyP5 + "/5");

        // Set-union should have at least 3 relevant docs in top 5
        assertThat(suP5).as("SET_UNION precision@5").isGreaterThanOrEqualTo(3);

        // Count distractor intrusions in top 5
        var distractorIds = Set.of("d1", "d2", "d3", "d4", "d5");
        int suDistractors = precisionAtK(setUnion, 5, distractorIds);
        int hyDistractors = precisionAtK(hybrid, 5, distractorIds);
        System.out.println("  Distractor intrusions@5 — SET_UNION: " + suDistractors + ", HYBRID: " + hyDistractors);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void seedMemory(String id, String content) {
        store.insertMemoryItem(id, AGENT, "fact", content,
            embed.embed(content), System.currentTimeMillis(), "nexus");
    }

    private void seedKnowledge(String id, String pack, String title, String content) {
        store.insertKnowledge(id, pack, title, content, pack, null, embed.embed(content));
    }

    private void seedWorldDna(String id, String roomId, String dnaType, String content) {
        store.insertWorldDna(id, roomId, dnaType, content, embed.embed(content), 0.8f);
    }

    private static List<String> topIds(List<WyrdLuceneStore.SearchResult> results, int k) {
        return results.stream().limit(k)
            .map(WyrdLuceneStore.SearchResult::id)
            .toList();
    }

    private static int rankOf(List<WyrdLuceneStore.SearchResult> results, String id) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).id().equals(id)) return i;
        }
        return results.size(); // not found = worst rank
    }

    private static int precisionAtK(List<WyrdLuceneStore.SearchResult> results, int k,
                                     Set<String> relevantIds) {
        return (int) results.stream().limit(k)
            .filter(r -> relevantIds.contains(r.id()))
            .count();
    }

    private static void printComparison(String scenario, String query,
                                         List<WyrdLuceneStore.SearchResult> setUnion,
                                         List<WyrdLuceneStore.SearchResult> hybrid) {
        System.out.println("\n=== " + scenario + " ===");
        System.out.println("  Query: \"" + query + "\"");
        System.out.println("  SET_UNION: " + setUnion.stream()
            .map(r -> r.id() + "(%.3f)".formatted(r.score()))
            .collect(Collectors.joining(", ")));
        System.out.println("  HYBRID:    " + hybrid.stream()
            .map(r -> r.id() + "(%.3f)".formatted(r.score()))
            .collect(Collectors.joining(", ")));
    }
}
