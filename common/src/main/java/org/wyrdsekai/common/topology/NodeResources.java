package org.wyrdsekai.common.topology;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Resource snapshot for a household node, announced periodically via the Between.
 *
 * @param vramMb            available VRAM in megabytes (0 if no GPU)
 * @param ramMb             available RAM in megabytes
 * @param gpuModels         GPU model names (empty list if no GPU)
 * @param inferenceModels   model identifiers currently loaded for inference
 * @param loadPct           current CPU/system load as a percentage (0.0-100.0)
 * @param availableRoomSlots number of additional rooms this node can host
 */
public record NodeResources(
    @JsonProperty("vramMb") long vramMb,
    @JsonProperty("ramMb") long ramMb,
    @JsonProperty("gpuModels") List<String> gpuModels,
    @JsonProperty("inferenceModels") List<String> inferenceModels,
    @JsonProperty("loadPct") double loadPct,
    @JsonProperty("availableRoomSlots") int availableRoomSlots
) {}
