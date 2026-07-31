package org.wyrdsekai.core.soul.experiment;

/**
 * Configuration for a CfC + Bath substrate.
 *
 * @param inputDim        Embedding dimension (384 for all-minilm)
 * @param hiddenDim       Total hidden state dimension across all cells
 * @param outputDim       Output embedding dimension (384 for all-minilm)
 * @param numCells        Number of CfC cells
 * @param cellStateSize   State size per cell (hiddenDim / numCells)
 * @param backboneHidden  Hidden size for CfC backbone MLP
 * @param adjacency       Sparse inter-cell connections [numCells][variable]
 * @param seed            Random seed for reproducibility
 */
record SubstrateConfig(
    int inputDim,
    int hiddenDim,
    int outputDim,
    int numCells,
    int cellStateSize,
    int backboneHidden,
    int[][] adjacency,
    long seed
) {
    SubstrateConfig {
        if (hiddenDim != numCells * cellStateSize)
            throw new IllegalArgumentException(
                "hiddenDim (%d) must equal numCells (%d) * cellStateSize (%d)"
                    .formatted(hiddenDim, numCells, cellStateSize));
        if (numCells < 1 || cellStateSize < 1)
            throw new IllegalArgumentException("Need at least 1 cell with at least 1 state unit");
    }

    /**
     * Default configuration for 20-scenario behavioral learning.
     * 4 cells × 32 state = 128 hidden. ~20K trainable params.
     */
    static SubstrateConfig defaultConfig() {
        return new SubstrateConfig(384, 128, 384, 4, 32, 24,
            ringAdjacency(4), 42L);
    }

    /**
     * Configure substrate based on soul complexity.
     *
     * @param soulText     Extracted soul fingerprint text
     * @param embeddingDim Dimension of embedding model (384 for all-minilm)
     */
    static SubstrateConfig fromSoul(String soulText, int embeddingDim) {
        // Count behavioral dimensions mentioned → cell count
        int dims = 0;
        if (soulText.contains("social") || soulText.contains("greeting")) dims++;
        if (soulText.contains("decision") || soulText.contains("moral")) dims++;
        if (soulText.contains("style") || soulText.contains("tone")) dims++;
        if (soulText.contains("memory") || soulText.contains("recall")) dims++;
        if (soulText.contains("humor") || soulText.contains("wit")) dims++;
        if (soulText.contains("emotion") || soulText.contains("empathy")) dims++;
        int numCells = Math.max(2, Math.min(8, dims > 0 ? dims : 4));

        // Soul complexity → state size per cell
        int soulTokens = soulText.length() / 4;
        int cellStateSize;
        if (soulTokens > 1000) cellStateSize = 48;
        else if (soulTokens > 400) cellStateSize = 32;
        else cellStateSize = 24;

        int hiddenDim = numCells * cellStateSize;
        int backboneHidden = Math.max(16, cellStateSize * 3 / 4);

        return new SubstrateConfig(embeddingDim, hiddenDim, embeddingDim,
            numCells, cellStateSize, backboneHidden,
            ringAdjacency(numCells), 42L);
    }

    /**
     * Ring + skip adjacency: each cell connects to next and one skip-ahead.
     * Gives connectivity without O(n²) connections.
     */
    static int[][] ringAdjacency(int numCells) {
        if (numCells <= 1) return new int[][]{ {} };
        if (numCells == 2) return new int[][]{ {1}, {0} };

        var adj = new int[numCells][];
        for (int i = 0; i < numCells; i++) {
            int next = (i + 1) % numCells;
            int skip = (i + 2) % numCells;
            if (next == skip) {
                adj[i] = new int[]{ next };
            } else {
                adj[i] = new int[]{ next, skip };
            }
        }
        return adj;
    }
}
