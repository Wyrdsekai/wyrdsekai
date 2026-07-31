package org.wyrdsekai.core.substrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * Pure Java implementation of a Closed-form Continuous-depth (CfC) neural network cell.
 *
 * <p>CfC equation (Hasani &amp; Lechner, Nature Machine Intelligence 2022):
 * <pre>
 *   x(t+Δt) = σ(-f · Δt) · g(x, I) + (1 - σ(-f · Δt)) · h(x, I)
 * </pre>
 * Where f = time-gate, g = fast response, h = slow attractor. All learned, input-dependent.
 *
 * <p>Architecture:
 * <pre>
 *   Input (32-dim): [8 tanks, 8 drives, 8 events, 8 archetype_vec]
 *   Backbone L1: Linear(32, 48) → SiLU
 *   Backbone L2: Linear(48, 32) → SiLU
 *   f_head: Linear(32, 16) → softplus (time-gate, always positive)
 *   g_head: Linear(32, 16) → tanh     (fast response)
 *   h_head: Linear(32, 16) → tanh     (slow attractor)
 *   Output (16-dim): [8 tank deltas, 8 drive deltas]
 * </pre>
 *
 * <p>~4,800 parameters. Sub-microsecond inference. Bounded output by construction.
 *
 * @see CfCTrainer for backpropagation and training
 */
public class CfCCell {

    // Dimensions — updated for 10 tanks (8 original + integrity + disgust)
    // Input: [10 tanks, 8 drives, 10 events, 8 archetype] = 36
    // Output: [10 tank deltas, 8 drive deltas] = 18
    public static final int INPUT_DIM = 36;
    public static final int BACKBONE1_DIM = 54;
    public static final int BACKBONE2_DIM = 36;
    public static final int OUTPUT_DIM = 18;   // 10 tank deltas + 8 drive deltas

    // Backbone layer 1: Linear(32, 48)
    final float[][] w1;   // [48][32]
    final float[] b1;     // [48]

    // Backbone layer 2: Linear(48, 32)
    final float[][] w2;   // [32][48]
    final float[] b2;     // [32]

    // f head (time-gate): Linear(32, 16)
    final float[][] wf;   // [16][32]
    final float[] bf;     // [16]

    // g head (fast response): Linear(32, 16)
    final float[][] wg;   // [16][32]
    final float[] bg;     // [16]

    // h head (slow attractor): Linear(32, 16)
    final float[][] wh;   // [16][32]
    final float[] bh;     // [16]

    // Hidden state
    float[] hidden;       // [16]

    public CfCCell() {
        w1 = new float[BACKBONE1_DIM][INPUT_DIM];
        b1 = new float[BACKBONE1_DIM];
        w2 = new float[BACKBONE2_DIM][BACKBONE1_DIM];
        b2 = new float[BACKBONE2_DIM];
        wf = new float[OUTPUT_DIM][BACKBONE2_DIM];
        bf = new float[OUTPUT_DIM];
        wg = new float[OUTPUT_DIM][BACKBONE2_DIM];
        bg = new float[OUTPUT_DIM];
        wh = new float[OUTPUT_DIM][BACKBONE2_DIM];
        bh = new float[OUTPUT_DIM];
        hidden = new float[OUTPUT_DIM];
    }

    /**
     * Initialize weights with Xavier uniform initialization.
     */
    public void initializeXavier(Random rng) {
        xavierInit(w1, rng); zeroInit(b1);
        xavierInit(w2, rng); zeroInit(b2);
        xavierInit(wf, rng); zeroInit(bf);
        xavierInit(wg, rng); zeroInit(bg);
        xavierInit(wh, rng); zeroInit(bh);
        zeroInit(hidden);
    }

    /**
     * CfC forward pass.
     *
     * @param input    input vector [32]: tanks(8) + drives(8) + events(8) + archetype(8)
     * @param deltaTime seconds since last tick
     * @return output deltas [16]: tank_deltas(8) + drive_deltas(8)
     */
    public float[] forward(float[] input, float deltaTime) {
        // Backbone L1: SiLU(W1 · input + b1)
        float[] a1 = linearSilu(w1, b1, input);

        // Backbone L2: SiLU(W2 · a1 + b2)
        float[] a2 = linearSilu(w2, b2, a1);

        // Three heads
        float[] f = linearSoftplus(wf, bf, a2);  // time-gate (positive)
        float[] g = linearTanh(wg, bg, a2);       // fast response
        float[] h = linearTanh(wh, bh, a2);       // slow attractor

        // CfC closed-form: x(t+dt) = σ(-f·dt)·g + (1-σ(-f·dt))·h
        float[] output = new float[OUTPUT_DIM];
        for (int i = 0; i < OUTPUT_DIM; i++) {
            float interp = sigmoid(-f[i] * deltaTime);
            output[i] = interp * g[i] + (1.0f - interp) * h[i];
        }

        // Update hidden state
        System.arraycopy(output, 0, hidden, 0, OUTPUT_DIM);

        return output;
    }

    /**
     * Forward pass with intermediate values saved for backpropagation.
     */
    public ForwardResult forwardWithGrad(float[] input, float deltaTime) {
        float[] a1 = linearSilu(w1, b1, input);
        float[] a2 = linearSilu(w2, b2, a1);
        float[] f = linearSoftplus(wf, bf, a2);
        float[] g = linearTanh(wg, bg, a2);
        float[] h = linearTanh(wh, bh, a2);

        float[] output = new float[OUTPUT_DIM];
        float[] interp = new float[OUTPUT_DIM];
        for (int i = 0; i < OUTPUT_DIM; i++) {
            interp[i] = sigmoid(-f[i] * deltaTime);
            output[i] = interp[i] * g[i] + (1.0f - interp[i]) * h[i];
        }

        System.arraycopy(output, 0, hidden, 0, OUTPUT_DIM);

        return new ForwardResult(input, a1, a2, f, g, h, interp, output, deltaTime);
    }

    /** Reset hidden state to zeros. */
    public void resetHidden() {
        zeroInit(hidden);
    }

    /** Get current hidden state (copy). */
    public float[] getHidden() {
        return hidden.clone();
    }

    /** Set hidden state. */
    public void setHidden(float[] h) {
        System.arraycopy(h, 0, hidden, 0, Math.min(h.length, OUTPUT_DIM));
    }

    // ── Weight Access ────────────────────────────────────────────────────

    /** Total number of trainable parameters. */
    public int paramCount() {
        return INPUT_DIM * BACKBONE1_DIM + BACKBONE1_DIM
             + BACKBONE1_DIM * BACKBONE2_DIM + BACKBONE2_DIM
             + BACKBONE2_DIM * OUTPUT_DIM * 3 + OUTPUT_DIM * 3;
    }

    /** Flatten all weights to a single array. */
    public float[] flattenWeights() {
        float[] flat = new float[paramCount()];
        int offset = 0;
        offset = flatten2d(w1, flat, offset);
        offset = flatten1d(b1, flat, offset);
        offset = flatten2d(w2, flat, offset);
        offset = flatten1d(b2, flat, offset);
        offset = flatten2d(wf, flat, offset);
        offset = flatten1d(bf, flat, offset);
        offset = flatten2d(wg, flat, offset);
        offset = flatten1d(bg, flat, offset);
        offset = flatten2d(wh, flat, offset);
        flatten1d(bh, flat, offset);
        return flat;
    }

    /** Load weights from a flat array. */
    public void loadWeights(float[] flat) {
        int offset = 0;
        offset = unflatten2d(flat, w1, offset);
        offset = unflatten1d(flat, b1, offset);
        offset = unflatten2d(flat, w2, offset);
        offset = unflatten1d(flat, b2, offset);
        offset = unflatten2d(flat, wf, offset);
        offset = unflatten1d(flat, bf, offset);
        offset = unflatten2d(flat, wg, offset);
        offset = unflatten1d(flat, bg, offset);
        offset = unflatten2d(flat, wh, offset);
        unflatten1d(flat, bh, offset);
    }

    /** Add Gaussian noise to weights (birth diversity). */
    public void addWeightNoise(Random rng, float sigma) {
        addNoise2d(w1, rng, sigma);
        addNoise2d(w2, rng, sigma);
        addNoise2d(wf, rng, sigma);
        addNoise2d(wg, rng, sigma);
        addNoise2d(wh, rng, sigma);
    }

    // ── Serialization ────────────────────────────────────────────────────

    /** Save weights to JSON file. */
    public void saveJson(Path path) throws IOException {
        var mapper = new ObjectMapper();
        var node = mapper.createObjectNode();
        node.set("w1", floatArrayToJson(mapper, flattenMatrix(w1)));
        node.set("b1", floatArrayToJson(mapper, b1));
        node.set("w2", floatArrayToJson(mapper, flattenMatrix(w2)));
        node.set("b2", floatArrayToJson(mapper, b2));
        node.set("wf", floatArrayToJson(mapper, flattenMatrix(wf)));
        node.set("bf", floatArrayToJson(mapper, bf));
        node.set("wg", floatArrayToJson(mapper, flattenMatrix(wg)));
        node.set("bg", floatArrayToJson(mapper, bg));
        node.set("wh", floatArrayToJson(mapper, flattenMatrix(wh)));
        node.set("bh", floatArrayToJson(mapper, bh));
        node.set("hidden", floatArrayToJson(mapper, hidden));
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), node);
    }

    /** Load weights from JSON file. */
    public static CfCCell loadJson(Path path) throws IOException {
        var mapper = new ObjectMapper();
        var node = mapper.readTree(path.toFile());
        return fromJsonNode(node);
    }

    /** Load from JSON input stream. */
    public static CfCCell loadJson(InputStream is) throws IOException {
        var mapper = new ObjectMapper();
        var node = mapper.readTree(is);
        return fromJsonNode(node);
    }

    private static CfCCell fromJsonNode(JsonNode node) {
        var cell = new CfCCell();
        unflattenMatrix(jsonToFloatArray(node.get("w1")), cell.w1);
        jsonToFloatArrayInto(node.get("b1"), cell.b1);
        unflattenMatrix(jsonToFloatArray(node.get("w2")), cell.w2);
        jsonToFloatArrayInto(node.get("b2"), cell.b2);
        unflattenMatrix(jsonToFloatArray(node.get("wf")), cell.wf);
        jsonToFloatArrayInto(node.get("bf"), cell.bf);
        unflattenMatrix(jsonToFloatArray(node.get("wg")), cell.wg);
        jsonToFloatArrayInto(node.get("bg"), cell.bg);
        unflattenMatrix(jsonToFloatArray(node.get("wh")), cell.wh);
        jsonToFloatArrayInto(node.get("bh"), cell.bh);
        if (node.has("hidden")) {
            jsonToFloatArrayInto(node.get("hidden"), cell.hidden);
        }
        return cell;
    }

    // ── Intermediate result for backprop ─────────────────────────────────

    public record ForwardResult(
        float[] input,     // [32]
        float[] a1,        // [48] backbone L1 output
        float[] a2,        // [32] backbone L2 output
        float[] f,         // [16] time-gate
        float[] g,         // [16] fast response
        float[] h,         // [16] slow attractor
        float[] interp,    // [16] σ(-f·dt)
        float[] output,    // [16] final output
        float deltaTime
    ) {}

    // ── Activation Functions ─────────────────────────────────────────────

    static float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    static float silu(float x) {
        return x * sigmoid(x);
    }

    static float softplus(float x) {
        return (float) Math.log(1.0 + Math.exp(x));
    }

    // ── Linear Layer Operations ──────────────────────────────────────────

    static float[] linearSilu(float[][] w, float[] b, float[] x) {
        float[] out = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            float sum = b[i];
            for (int j = 0; j < x.length; j++) {
                sum += w[i][j] * x[j];
            }
            out[i] = silu(sum);
        }
        return out;
    }

    static float[] linearSoftplus(float[][] w, float[] b, float[] x) {
        float[] out = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            float sum = b[i];
            for (int j = 0; j < x.length; j++) {
                sum += w[i][j] * x[j];
            }
            out[i] = softplus(sum);
        }
        return out;
    }

    static float[] linearTanh(float[][] w, float[] b, float[] x) {
        float[] out = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            float sum = b[i];
            for (int j = 0; j < x.length; j++) {
                sum += w[i][j] * x[j];
            }
            out[i] = (float) Math.tanh(sum);
        }
        return out;
    }

    // ── Weight Utilities ─────────────────────────────────────────────────

    private static void xavierInit(float[][] w, Random rng) {
        float limit = (float) Math.sqrt(6.0 / (w.length + w[0].length));
        for (float[] row : w) {
            for (int j = 0; j < row.length; j++) {
                row[j] = (rng.nextFloat() * 2 - 1) * limit;
            }
        }
    }

    private static void zeroInit(float[] a) {
        Arrays.fill(a, 0.0f);
    }

    private static void addNoise2d(float[][] w, Random rng, float sigma) {
        for (float[] row : w) {
            for (int j = 0; j < row.length; j++) {
                row[j] += (float) rng.nextGaussian() * sigma;
            }
        }
    }

    private static int flatten2d(float[][] w, float[] flat, int offset) {
        for (float[] row : w) {
            System.arraycopy(row, 0, flat, offset, row.length);
            offset += row.length;
        }
        return offset;
    }

    private static int flatten1d(float[] b, float[] flat, int offset) {
        System.arraycopy(b, 0, flat, offset, b.length);
        return offset + b.length;
    }

    private static int unflatten2d(float[] flat, float[][] w, int offset) {
        for (float[] row : w) {
            System.arraycopy(flat, offset, row, 0, row.length);
            offset += row.length;
        }
        return offset;
    }

    private static int unflatten1d(float[] flat, float[] b, int offset) {
        System.arraycopy(flat, offset, b, 0, b.length);
        return offset + b.length;
    }

    private static float[] flattenMatrix(float[][] m) {
        int total = 0;
        for (float[] row : m) total += row.length;
        float[] flat = new float[total];
        int offset = 0;
        for (float[] row : m) {
            System.arraycopy(row, 0, flat, offset, row.length);
            offset += row.length;
        }
        return flat;
    }

    private static void unflattenMatrix(float[] flat, float[][] m) {
        int offset = 0;
        for (float[] row : m) {
            System.arraycopy(flat, offset, row, 0, row.length);
            offset += row.length;
        }
    }

    private static ArrayNode floatArrayToJson(
            ObjectMapper mapper, float[] arr) {
        var node = mapper.createArrayNode();
        for (float v : arr) node.add(v);
        return node;
    }

    private static float[] jsonToFloatArray(JsonNode node) {
        float[] arr = new float[node.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = (float) node.get(i).asDouble();
        }
        return arr;
    }

    private static void jsonToFloatArrayInto(JsonNode node, float[] target) {
        for (int i = 0; i < Math.min(node.size(), target.length); i++) {
            target[i] = (float) node.get(i).asDouble();
        }
    }
}
