package org.wyrdsekai.core.substrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Complete transferable soul — the portable identity of an agent.
 *
 * <p>Contents (~42KB without LoRA adapter):
 * <ul>
 *   <li>CfC weights — drive dynamics, interaction patterns, response curves
 *   <li>CfC hidden state — current emotional state
 *   <li>Archetype conditioning vector — personality template (may have drifted)
 *   <li>EWC Fisher diagonal — what's important to this agent's identity
 *   <li>Drive baselines — resting drive levels (elevated by chronic patterns)
 *   <li>Tank baselines — resting vitality (shaped by lifestyle)
 *   <li>Agent metadata — name, archetype, tier, creation time
 * </ul>
 *
 * <p>The soul manifest (text prompt, memories, bonds) is stored separately
 * in the SoulStore. This bundle carries the substrate layer.
 *
 * @see CfCCell for the neural network whose weights are stored here
 */
public record SoulBundle(
    float[] cfcWeights,           // ~4,800 floats
    float[] cfcHidden,            // 16 floats
    float[] archetypeVector,      // 8 floats
    float[] fisherDiagonal,       // ~4,800 floats
    double[] driveBaselines,      // 8 doubles — chronic resting levels
    double[] tankBaselines,       // 8 doubles — chronic resting levels
    String agentName,
    String archetypeName,
    int tier,
    Instant createdAt,
    Instant extractedAt
) {
    /**
     * Create a bundle from current agent state.
     */
    public static SoulBundle extract(CfCCell cell, CfCTrainer trainer,
                                     float[] archetypeVector,
                                     double[] driveBaselines, double[] tankBaselines,
                                     String agentName, String archetypeName, int tier) {
        return new SoulBundle(
            cell.flattenWeights(),
            cell.getHidden(),
            archetypeVector != null ? archetypeVector.clone() : new float[8],
            trainer != null ? trainer.getFisherDiagonal() : new float[cell.paramCount()],
            driveBaselines != null ? driveBaselines.clone() : new double[8],
            tankBaselines != null ? tankBaselines.clone() : new double[8],
            agentName,
            archetypeName,
            tier,
            Instant.now(),
            Instant.now()
        );
    }

    /**
     * Imprint this soul onto a CfC cell and trainer.
     */
    public void imprint(CfCCell cell, CfCTrainer trainer) {
        cell.loadWeights(cfcWeights);
        cell.setHidden(cfcHidden);
        if (trainer != null && fisherDiagonal != null) {
            trainer.setFisherDiagonal(fisherDiagonal);
        }
    }

    /** Estimated size in bytes (JSON serialized). */
    public long estimatedSizeBytes() {
        // Each float → ~8 chars in JSON + overhead
        int floatCount = cfcWeights.length + cfcHidden.length + archetypeVector.length
                       + fisherDiagonal.length;
        return (long) floatCount * 10 + 500; // ~500 bytes metadata
    }

    // ── JSON Serialization ───────────────────────────────────────────────

    public void saveJson(Path path) throws IOException {
        var mapper = new ObjectMapper();
        var root = toJsonNode(mapper);
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    public static SoulBundle loadJson(Path path) throws IOException {
        var mapper = new ObjectMapper();
        var root = mapper.readTree(path.toFile());
        return fromJsonNode(root);
    }

    ObjectNode toJsonNode(ObjectMapper mapper) {
        var root = mapper.createObjectNode();
        root.put("version", 1);
        root.put("agentName", agentName);
        root.put("archetypeName", archetypeName);
        root.put("tier", tier);
        root.put("createdAt", createdAt.toString());
        root.put("extractedAt", extractedAt.toString());
        root.set("cfcWeights", floatsToJson(mapper, cfcWeights));
        root.set("cfcHidden", floatsToJson(mapper, cfcHidden));
        root.set("archetypeVector", floatsToJson(mapper, archetypeVector));
        root.set("fisherDiagonal", floatsToJson(mapper, fisherDiagonal));
        root.set("driveBaselines", doublesToJson(mapper, driveBaselines));
        root.set("tankBaselines", doublesToJson(mapper, tankBaselines));
        return root;
    }

    static SoulBundle fromJsonNode(JsonNode root) {
        return new SoulBundle(
            jsonToFloats(root.get("cfcWeights")),
            jsonToFloats(root.get("cfcHidden")),
            jsonToFloats(root.get("archetypeVector")),
            jsonToFloats(root.get("fisherDiagonal")),
            jsonToDoubles(root.get("driveBaselines")),
            jsonToDoubles(root.get("tankBaselines")),
            root.path("agentName").asText("unknown"),
            root.path("archetypeName").asText("scholar"),
            root.path("tier").asInt(0),
            Instant.parse(root.path("createdAt").asText(Instant.now().toString())),
            Instant.parse(root.path("extractedAt").asText(Instant.now().toString()))
        );
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    private static ArrayNode floatsToJson(ObjectMapper mapper, float[] arr) {
        var node = mapper.createArrayNode();
        for (float v : arr) node.add(v);
        return node;
    }

    private static ArrayNode doublesToJson(ObjectMapper mapper, double[] arr) {
        var node = mapper.createArrayNode();
        for (double v : arr) node.add(v);
        return node;
    }

    private static float[] jsonToFloats(JsonNode node) {
        if (node == null || !node.isArray()) return new float[0];
        float[] arr = new float[node.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = (float) node.get(i).asDouble();
        return arr;
    }

    private static double[] jsonToDoubles(JsonNode node) {
        if (node == null || !node.isArray()) return new double[0];
        double[] arr = new double[node.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = node.get(i).asDouble();
        return arr;
    }
}
