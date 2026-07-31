package org.wyrdsekai.core.interop;

import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.soul.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full round-trip SOUL.md ↔ SoulManifest conversion (§97.2).
 *
 * Extends the existing SoulSpecAdapter with:
 * - Richer export (traits, memories, background, genome summary)
 * - Structured import with section parsing into SoulSpecDocument
 * - Round-trip fidelity: export → import → export should be stable
 *
 * The SOUL.md format is markdown-based, matching the open SoulSpec standard:
 *   # SOUL.md
 *   ## Identity
 *   ## Persona
 *   ## Traits
 *   ## Memories
 *   ## Background
 *   ## Relationships
 */
public final class SoulSpecBridge {

    private SoulSpecBridge() {}

    /**
     * Intermediate representation of a SOUL.md document.
     * Captures the structured sections of the markdown format.
     *
     * @param name       Agent name (from Identity section)
     * @param persona    Free-text persona description
     * @param traits     Personality and value traits
     * @param memories   Significant memories
     * @param background Background narrative
     */
    public record SoulSpecDocument(
        String name,
        String persona,
        List<String> traits,
        List<String> memories,
        String background
    ) {
        /** Whether this document has enough content to create a manifest. */
        public boolean isViable() {
            return name != null && !name.isBlank()
                && (persona != null && !persona.isBlank()
                    || !traits.isEmpty());
        }
    }

    // ─── Export ─────────────────────────────────────────────────

    /**
     * Export a SoulManifest to SOUL.md-compatible text.
     * Richer than SoulSpecAdapter.toSoulSpec — includes memories,
     * background, and genome summary.
     *
     * @param manifest The soul manifest to export
     * @return SOUL.md formatted text
     */
    public static String exportToSoulSpec(SoulManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");

        var sb = new StringBuilder();
        sb.append("# SOUL.md\n\n");

        // Identity
        sb.append("## Identity\n");
        sb.append("- Name: ").append(manifest.profile().name()).append('\n');
        sb.append("- DID: ").append(manifest.did() != null ? manifest.did() : "unassigned").append('\n');
        if (manifest.parentDid() != null) {
            sb.append("- Parent: ").append(manifest.parentDid()).append('\n');
        }
        sb.append("- Version: ").append(manifest.manifestVersion()).append('\n');
        if (manifest.forgedAt() != null) {
            sb.append("- Forged: ").append(manifest.forgedAt()).append('\n');
        }
        sb.append('\n');

        // Persona
        sb.append("## Persona\n");
        if (manifest.residentIdentity() != null && !manifest.residentIdentity().isBlank()) {
            sb.append(manifest.residentIdentity()).append("\n\n");
        } else if (manifest.profile().systemPrompt() != null) {
            sb.append(manifest.profile().systemPrompt()).append("\n\n");
        }

        // Traits (personality and values fragments)
        var traitFragments = manifest.soulFragments() != null
            ? manifest.soulFragments().stream()
                .filter(f -> "personality".equals(f.category()) || "values".equals(f.category()))
                .toList()
            : List.<SoulFragment>of();

        if (!traitFragments.isEmpty()) {
            sb.append("## Traits\n");
            for (var frag : traitFragments) {
                sb.append("### ").append(frag.label()).append('\n');
                sb.append(frag.text()).append("\n\n");
            }
        }

        // Memories (memory fragments, including formative)
        var memoryFragments = manifest.soulFragments() != null
            ? manifest.soulFragments().stream()
                .filter(f -> "memory".equals(f.category()))
                .toList()
            : List.<SoulFragment>of();

        if (!memoryFragments.isEmpty()) {
            sb.append("## Memories\n");
            for (var frag : memoryFragments) {
                if (frag.formative()) {
                    sb.append("### ").append(frag.label()).append(" [formative]\n");
                } else {
                    sb.append("### ").append(frag.label()).append('\n');
                }
                sb.append(frag.text()).append("\n\n");
            }
        }

        // Background (style and relationships fragments combined)
        var backgroundFragments = manifest.soulFragments() != null
            ? manifest.soulFragments().stream()
                .filter(f -> "style".equals(f.category()) || "relationships".equals(f.category()))
                .toList()
            : List.<SoulFragment>of();

        if (!backgroundFragments.isEmpty()) {
            sb.append("## Background\n");
            for (var frag : backgroundFragments) {
                sb.append(frag.text()).append("\n\n");
            }
        }

        // Relationships (summary)
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

    // ─── Import ─────────────────────────────────────────────────

    /**
     * Parse SOUL.md text into a structured SoulSpecDocument.
     *
     * @param soulMd The SOUL.md content
     * @return Parsed document
     */
    public static SoulSpecDocument parse(String soulMd) {
        if (soulMd == null || soulMd.isBlank()) {
            return new SoulSpecDocument("", "", List.of(), List.of(), "");
        }

        String name = extractField(soulMd, "Name");
        String persona = extractSection(soulMd, "Persona");
        List<String> traits = extractSubsections(soulMd, "Traits");
        List<String> memories = extractSubsections(soulMd, "Memories");
        String background = extractSection(soulMd, "Background");

        // Fallback: if no name in Identity, try document title
        if (name.isBlank()) {
            var titleMatch = Pattern.compile("^#\\s+(.+)", Pattern.MULTILINE).matcher(soulMd);
            if (titleMatch.find()) {
                var title = titleMatch.group(1).trim();
                if (!title.equalsIgnoreCase("SOUL.md")) {
                    name = title;
                }
            }
        }

        return new SoulSpecDocument(name, persona, traits, memories, background);
    }

    /**
     * Import SOUL.md text into a partial SoulManifest.
     * Identity layer (DID, keys) must be provided separately.
     * Genome defaults. Layers B-C start empty.
     *
     * @param soulMd The SOUL.md content
     * @return Partial manifest (no identity, no signature)
     */
    public static SoulManifest importFromSoulSpec(String soulMd) {
        var doc = parse(soulMd);
        String agentName = doc.name().isBlank() ? "imported-agent" : doc.name();

        // Build system prompt from persona + traits
        var promptBuilder = new StringBuilder();
        if (!doc.persona().isBlank()) {
            promptBuilder.append(doc.persona());
        }
        if (!doc.traits().isEmpty()) {
            if (promptBuilder.length() > 0) promptBuilder.append("\n\n");
            promptBuilder.append("Key traits:\n");
            for (var trait : doc.traits()) {
                promptBuilder.append("- ").append(trait).append('\n');
            }
        }
        String systemPrompt = promptBuilder.length() > 0
            ? promptBuilder.toString()
            : soulMd; // fallback: use full text

        var profile = new AgentProfile(
            agentName, agentName.toLowerCase().replace(' ', '-'),
            "agent", "Imported from SOUL.md",
            systemPrompt, 8192, 1024, 0.7, null
        );

        // Build soul fragments from traits and memories
        var fragments = new ArrayList<SoulFragment>();
        int idx = 0;
        for (var trait : doc.traits()) {
            fragments.add(SoulFragment.unembedded(
                "trait-" + idx, "personality", "Trait " + idx, trait));
            idx++;
        }
        for (var mem : doc.memories()) {
            boolean formative = mem.contains("[formative]");
            String cleanMem = mem.replace("[formative]", "").trim();
            if (formative) {
                fragments.add(SoulFragment.formative(
                    "memory-" + idx, "Memory " + idx, cleanMem));
            } else {
                fragments.add(SoulFragment.unembedded(
                    "memory-" + idx, "memory", "Memory " + idx, cleanMem));
            }
            idx++;
        }

        return new SoulManifest(
            null, null, List.of(), null,
            1, Instant.now(), null,
            profile, doc.persona(), fragments, 3, soulMd,
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty(),
            List.of(), null, null, null, null,
            null /* protectionManifest — imported manifests are unattested */,
            null /* personalManifest — imported manifests have no personal layer */,
            null /* affinityMap — imported manifests carry no embodiment affinities */
        );
    }

    // ─── Helpers ────────────────────────────────────────────────

    /** Extract text under a markdown ## heading. */
    static String extractSection(String text, String heading) {
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

    /** Extract subsections (### headings) under a ## heading. */
    static List<String> extractSubsections(String text, String heading) {
        String section = extractSection(text, heading);
        if (section.isBlank()) return List.of();

        var results = new ArrayList<String>();
        var parts = section.split("###\\s+");

        for (var part : parts) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                // Remove the heading line, keep the body
                int nl = trimmed.indexOf('\n');
                if (nl >= 0) {
                    var body = trimmed.substring(nl + 1).trim();
                    if (!body.isEmpty()) {
                        results.add(body);
                    }
                } else {
                    // Single-line subsection (heading only, no body) — skip
                }
            }
        }

        // If no ### headings, treat each non-blank line as an entry
        if (results.isEmpty()) {
            for (var line : section.split("\n")) {
                var trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    // Strip leading "- " if present
                    if (trimmed.startsWith("- ")) {
                        trimmed = trimmed.substring(2);
                    }
                    results.add(trimmed);
                }
            }
        }

        return List.copyOf(results);
    }

    /** Extract a key-value field from a section (e.g., "- Name: Lain"). */
    static String extractField(String text, String fieldName) {
        var pattern = Pattern.compile("-\\s+" + Pattern.quote(fieldName) + ":\\s*(.+)",
            Pattern.MULTILINE);
        var matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
