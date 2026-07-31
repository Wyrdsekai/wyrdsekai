package org.wyrdsekai.core.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Room template for MCP-backed rooms (§91.4).
 * A template = room script (.js) + metadata describing required services,
 * objects, commands, and narrative fragments.
 *
 * Sources:
 * - Built-in: shipped with Wyrdsekai (§88 rooms)
 * - Community: shared via Between federation
 * - Generated: agent creates from MCP server tool descriptions
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomTemplate(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("requires_services") List<String> requiredServices,
    @JsonProperty("objects") List<TemplateObject> objects,
    @JsonProperty("script") String scriptPath,
    @JsonProperty("category") String category,
    @JsonProperty("trust_minimum") double trustMinimum,
    @JsonProperty("version") String version,
    @JsonProperty("source") String source
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemplateObject(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("takeable") boolean takeable
    ) {}

    /** Whether all required services are available in the registry. */
    public boolean canInstall(McpServiceRegistry registry) {
        if (requiredServices == null || requiredServices.isEmpty()) return true;
        return requiredServices.stream().allMatch(registry::isAvailable);
    }

    /** Whether the template meets a given trust threshold. */
    public boolean meetsTrustThreshold(double threshold) {
        return trustMinimum >= threshold;
    }

    /** Whether this is a built-in template. */
    public boolean isBuiltIn() {
        return "built-in".equals(source);
    }
}
