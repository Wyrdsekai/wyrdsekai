package org.wyrdsekai.core.familiar;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A named, reusable template for summoning a {@link Familiar}.
 *
 * <p>. A thought form is <em>data, not a running thing</em>.
 * It is shaped (authored) at the Workshop's workbench, stored in the author
 * agent's FamilyLocker, and repeatedly summoned from anywhere. Summoning
 * manifests a {@link Familiar} from the form and a specific task.</p>
 *
 * <p>The form's system prompt is deliberately thin — no soul, no personality,
 * no continuity across summonings. The form is <strong>what Wyrd does</strong>,
 * not who she is. The <em>habit</em> of form-making is soul-shaped (§12);
 * the form itself is an item.</p>
 *
 * @param id             opaque identity
 * @param name           agent-chosen short name ("researcher", "gardener", "coder")
 * @param version        semver; refinements bump minor/patch, new lineage bumps major
 * @param provenance     author chain, never strippable
 * @param systemPrompt   focused task prompt (no soul)
 * @param toolSurface    names of tools a summoned familiar may use
 * @param defaultTanks   default resource allocation per summoning
 * @param maxTanks       ceiling the author agent may raise to without user approval
 * @param maxTrials      how many times the familiar retries on failure (default 3)
 * @param maxNestDepth   0 = familiar cannot spawn its own familiars
 * @param evalCriteria   natural-language success specification
 * @param createdAt      form authoring timestamp
 * @param revisedAt      last edit
 * @param summonCount    usage statistic (feeds Forge)
 * @param successCount
 * @param failureCount
 * @param bondCharge     relational charge [0.0, 1.0] — rises with attachment
 */
public record ThoughtForm(
    String id,
    String name,
    String version,
    Provenance provenance,
    String systemPrompt,
    Set<String> toolSurface,
    Tanks defaultTanks,
    Tanks maxTanks,
    int maxTrials,
    int maxNestDepth,
    String evalCriteria,
    Instant createdAt,
    Instant revisedAt,
    long summonCount,
    long successCount,
    long failureCount,
    float bondCharge
) {

    public ThoughtForm {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (version == null || version.isBlank()) version = "1.0.0";
        if (provenance == null) throw new IllegalArgumentException("provenance required");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt required");
        }
        toolSurface = toolSurface == null ? Set.of() : Set.copyOf(toolSurface);
        if (defaultTanks == null) defaultTanks = Tanks.defaults();
        if (maxTanks == null) maxTanks = Tanks.maxCeiling();
        if (!defaultTanks.withinCeiling(maxTanks)) {
            throw new IllegalArgumentException("defaultTanks must be within maxTanks");
        }
        if (maxTrials < 1) maxTrials = 3;
        if (maxNestDepth < 0) maxNestDepth = 0;
        if (evalCriteria == null) evalCriteria = "";
        if (createdAt == null) createdAt = Instant.now();
        if (revisedAt == null) revisedAt = createdAt;
        if (bondCharge < 0f) bondCharge = 0f;
        if (bondCharge > 1f) bondCharge = 1f;
    }

    /** Fresh form authored by an agent — initial stats at zero. */
    public static ThoughtForm author(String authorDid, String name, String systemPrompt,
                                      Set<String> toolSurface, String evalCriteria) {
        var now = Instant.now();
        return new ThoughtForm(
            UUID.randomUUID().toString(),
            name,
            "1.0.0",
            Provenance.authoredBy(authorDid, "initial authoring"),
            systemPrompt,
            toolSurface,
            Tanks.defaults(),
            Tanks.maxCeiling(),
            3,
            0,
            evalCriteria,
            now, now,
            0, 0, 0,
            0f
        );
    }

    /** Increment summon count (called when a familiar is spawned from this form). */
    public ThoughtForm incrementSummon() {
        return new ThoughtForm(id, name, version, provenance, systemPrompt, toolSurface,
            defaultTanks, maxTanks, maxTrials, maxNestDepth, evalCriteria,
            createdAt, revisedAt,
            summonCount + 1, successCount, failureCount, bondCharge);
    }

    /** Record a successful outcome for a familiar summoned from this form. */
    public ThoughtForm recordSuccess() {
        return new ThoughtForm(id, name, version, provenance, systemPrompt, toolSurface,
            defaultTanks, maxTanks, maxTrials, maxNestDepth, evalCriteria,
            createdAt, revisedAt,
            summonCount, successCount + 1, failureCount, bondCharge);
    }

    /** Record a failed outcome. */
    public ThoughtForm recordFailure() {
        return new ThoughtForm(id, name, version, provenance, systemPrompt, toolSurface,
            defaultTanks, maxTanks, maxTrials, maxNestDepth, evalCriteria,
            createdAt, revisedAt,
            summonCount, successCount, failureCount + 1, bondCharge);
    }

    /** Success ratio — 0.0 if no summonings, else successes / (successes + failures). */
    public double successRatio() {
        var total = successCount + failureCount;
        if (total == 0) return 0.0;
        return (double) successCount / total;
    }
}
