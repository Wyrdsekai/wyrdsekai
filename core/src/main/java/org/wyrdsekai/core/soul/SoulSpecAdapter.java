package org.wyrdsekai.core.soul;

import org.wyrdsekai.core.agent.AgentProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter for SoulSpec/SOUL.md interop.
 * Converts between Wyrdsekai SoulManifest and the plain-text
 * SOUL.md format used by other agent frameworks.
 *
 * Import: Parse SOUL.md text -> populate profile + partial fingerprint.
 * Export: Generate SOUL.md from manifest (lossy: behavioral trace lost).
 */
public final class SoulSpecAdapter {

    private SoulSpecAdapter() {}

    /**
     * Export a SoulManifest to SOUL.md-compatible text.
     * Lossy: behavioral trace, genome, and detailed memory are not represented.
     */
    public static String toSoulSpec(SoulManifest manifest) {
        var sb = new StringBuilder();
        sb.append("# SOUL.md\n\n");

        // Identity
        sb.append("## Identity\n");
        sb.append("- Name: ").append(manifest.profile().name()).append('\n');
        sb.append("- DID: ").append(manifest.did()).append('\n');
        if (manifest.parentDid() != null) {
            sb.append("- Parent: ").append(manifest.parentDid()).append('\n');
        }
        sb.append('\n');

        // Persona
        sb.append("## Persona\n");
        if (manifest.residentIdentity() != null && !manifest.residentIdentity().isBlank()) {
            sb.append(manifest.residentIdentity()).append("\n\n");
        }

        // Fragments (personality and values only, not memories)
        var personalityFragments = manifest.soulFragments().stream()
            .filter(f -> "personality".equals(f.category()) || "values".equals(f.category()))
            .toList();
        if (!personalityFragments.isEmpty()) {
            sb.append("## Traits\n");
            for (var frag : personalityFragments) {
                sb.append("### ").append(frag.label()).append('\n');
                sb.append(frag.text()).append("\n\n");
            }
        }

        // Relationships (summary only)
        if (manifest.relationships() != null && !manifest.relationships().isEmpty()) {
            sb.append("## Relationships\n");
            for (var rel : manifest.relationships()) {
                sb.append("- ").append(rel.entityName())
                    .append(" (trust: ").append(String.format("%.1f", rel.trust()))
                    .append(", bond: ").append(rel.bondDepth())
                    .append("): ").append(rel.summary()).append('\n');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Import a SOUL.md text into a partial SoulManifest.
     * Creates profile from persona text. Layers B-C start empty.
     * Genome gets defaults. Identity must be provided separately.
     *
     * @param soulSpecText The SOUL.md content
     * @param agentName    Name for the agent profile
     * @return Partial manifest (no identity, no signature)
     */
    public static SoulManifest fromSoulSpec(String soulSpecText, String agentName) {
        // Extract persona section as the system prompt
        String systemPrompt = extractSection(soulSpecText, "Persona");
        if (systemPrompt.isBlank()) {
            systemPrompt = soulSpecText; // use full text as fallback
        }

        var profile = new AgentProfile(
            agentName, agentName.toLowerCase().replace(' ', '-'),
            "agent", "Imported from SOUL.md",
            systemPrompt, 8192, 1024, 0.7, null
        );

        return new SoulManifest(
            null, null, List.of(), null,
            1, Instant.now(), null,
            profile, systemPrompt, List.of(), 3, soulSpecText,
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty(),
            List.of(), null, null, null, null,
            null /* protectionManifest — SOUL.md imports are unattested */,
            null /* personalManifest — SOUL.md imports have no personal layer */,
            null /* affinityMap — SOUL.md imports carry no embodiment affinities */
        );
    }

    /** Extract text under a markdown ## heading. */
    private static String extractSection(String text, String heading) {
        var pattern = "## " + heading;
        int start = text.indexOf(pattern);
        if (start < 0) return "";
        start = text.indexOf('\n', start);
        if (start < 0) return "";
        start++;

        int end = text.indexOf("\n## ", start);
        if (end < 0) end = text.length();

        return text.substring(start, end).strip();
    }
}
