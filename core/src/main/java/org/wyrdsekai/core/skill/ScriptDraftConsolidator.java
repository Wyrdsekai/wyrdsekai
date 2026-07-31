package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.companion.PersonalProject;
import org.wyrdsekai.core.companion.PersonalProjectStore;
import org.wyrdsekai.core.familiar.FormEvolutionClassifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Track A Phase 4 — sleep-cycle script-draft consolidation.
 *
 * <p>End-of-day Forge pass: read all {@code craft.script_draft} entries from
 * the last 24h, cluster them by structural similarity, and emit a
 * {@link SkillDraft} proposal for each recurring cluster (≥ 3 similar drafts).
 * The proposal lands on the workshop pinboard via {@link SkillDraftStore}; the
 * existing approve / materialize flow handles the rest.
 *
 * <h2>Similarity</h2>
 * Reuses {@link FormEvolutionClassifier} (the same cosine + jaccard fallback
 * used by form-evolution detection). For script bodies the embedder is rarely
 * wired (cold start, script-text not naturally embedded yet) — production
 * runs almost always fall through to normalized-text Jaccard. That's a
 * known limitation: clusters identifiers, not semantics. Phase 5+ can wire
 * a JS-AST tokenizer if the heuristic proves too coarse.
 *
 * <h2>Stateless</h2>
 * Pure function over inputs. Caller (ForgeActor / sleep cycle) owns
 * scheduling and persistence-side effects.
 */
public final class ScriptDraftConsolidator {

    private static final Logger log = LoggerFactory.getLogger(ScriptDraftConsolidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimum drafts in a cluster before it becomes a skill candidate (§5.4). */
    public static final int MIN_CLUSTER_SIZE = 3;

    /** Look-back window — last 24h of entries are considered. */
    public static final Duration WINDOW = Duration.ofHours(24);

    /**
     * Cosine/jaccard distance ≤ this means "similar enough to cluster."
     * Tuned conservatively — false-grouping a draft is worse than missing
     * one (the agent will improvise the same shape again tomorrow).
     */
    public static final double SIMILARITY_THRESHOLD = 0.30;

    /** A clustered set of similar drafts that became one skill proposal. */
    public record Cluster(
        String exemplarScript,        // representative — first member's body
        List<DraftEntry> members,
        Instant detectedAt
    ) {
        public Cluster {
            if (members == null) members = List.of();
            members = List.copyOf(members);
            if (detectedAt == null) detectedAt = Instant.now();
        }
        public int size() { return members.size(); }
    }

    /** A single {@code craft.script_draft} project entry, decoded. */
    public record DraftEntry(
        Instant at,
        String script,
        String summary,
        String tier,
        boolean ok
    ) {}

    private ScriptDraftConsolidator() {}

    /**
     * Run a consolidation pass for one agent. Loads recent script drafts from
     * the agent's {@link PersonalProjectStore}, clusters them, and writes a
     * {@link SkillDraft} per cluster into {@code draftStore}.
     *
     * @param agentDid    subject of consolidation
     * @param projects    agent's personal-project store
     * @param draftStore  where new skill-draft proposals are written
     * @param model       provenance label for the proposal (e.g. model id)
     * @return list of proposals created
     */
    public static List<SkillDraft> consolidate(
            String agentDid,
            PersonalProjectStore projects,
            SkillDraftStore draftStore,
            String model) {

        if (agentDid == null || projects == null) return List.of();

        var entries = collectRecentDrafts(projects);
        if (entries.size() < MIN_CLUSTER_SIZE) {
            log.debug("ScriptDraftConsolidator: {} only has {} recent drafts, "
                + "below cluster floor {}", agentDid, entries.size(), MIN_CLUSTER_SIZE);
            return List.of();
        }

        var clusters = clusterBySimilarity(entries);
        if (clusters.isEmpty()) {
            log.debug("ScriptDraftConsolidator: {} drafts present but none cluster",
                agentDid);
            return List.of();
        }

        var proposed = new ArrayList<SkillDraft>();
        for (var cluster : clusters) {
            var draft = buildSkillDraft(agentDid, cluster, model);
            if (draft == null) continue;
            if (draftStore != null) draftStore.upsert(draft);
            proposed.add(draft);
            log.info("ScriptDraftConsolidator: proposed skill '{}' for {} from {} clustered drafts",
                draft.name(), agentDid, cluster.size());
        }
        return proposed;
    }

    // ── Loading ────────────────────────────────────────────────────────────

    /**
     * Collect all {@code craft.script_draft} project entries from the
     * agent's store that fall inside {@link #WINDOW}.
     */
    public static List<DraftEntry> collectRecentDrafts(PersonalProjectStore projects) {
        var draftProject = findDraftProject(projects);
        if (draftProject == null || draftProject.entries() == null) return List.of();

        var cutoff = Instant.now().minus(WINDOW);
        var out = new ArrayList<DraftEntry>();
        for (var entry : draftProject.entries()) {
            if (entry == null || entry.at() == null) continue;
            if (entry.at().isBefore(cutoff)) continue;
            var decoded = decodeEntry(entry);
            if (decoded != null) out.add(decoded);
        }
        return out;
    }

    private static PersonalProject findDraftProject(PersonalProjectStore projects) {
        return projects.list().stream()
            .filter(p -> p.tags() != null && p.tags().contains("craft.script_draft"))
            .findFirst()
            .orElse(null);
    }

    private static DraftEntry decodeEntry(PersonalProject.Entry entry) {
        try {
            JsonNode node = MAPPER.readTree(entry.text());
            var script = node.path("script").asText("");
            if (script.isBlank()) return null;
            var summary = node.path("summary").asText("");
            var tier = node.path("tier").asText("improvisation");
            var ok = node.path("ok").asBoolean(true);
            return new DraftEntry(entry.at(), script, summary, tier, ok);
        } catch (Exception e) {
            log.debug("ScriptDraftConsolidator: skip unparseable entry: {}", e.getMessage());
            return null;
        }
    }

    // ── Clustering ─────────────────────────────────────────────────────────

    /**
     * Greedy single-link clustering by similarity threshold. For each draft
     * not yet assigned, find all unassigned drafts within
     * {@link #SIMILARITY_THRESHOLD} (using cosine via FormEvolutionClassifier
     * with no embedder, which falls back to Jaccard on normalized identifiers).
     * Clusters of size ≥ {@link #MIN_CLUSTER_SIZE} are returned.
     */
    static List<Cluster> clusterBySimilarity(List<DraftEntry> entries) {
        var clusters = new ArrayList<Cluster>();
        var seen = new HashSet<Integer>();

        for (int i = 0; i < entries.size(); i++) {
            if (seen.contains(i)) continue;
            var anchor = entries.get(i);
            var members = new ArrayList<DraftEntry>();
            members.add(anchor);
            seen.add(i);

            var anchorNorm = normaliseScript(anchor.script());
            for (int j = i + 1; j < entries.size(); j++) {
                if (seen.contains(j)) continue;
                var candidate = entries.get(j);
                var candidateNorm = normaliseScript(candidate.script());
                var distance = scriptDistance(anchorNorm, candidateNorm);
                if (distance <= SIMILARITY_THRESHOLD) {
                    members.add(candidate);
                    seen.add(j);
                }
            }
            if (members.size() >= MIN_CLUSTER_SIZE) {
                clusters.add(new Cluster(anchor.script(), members, Instant.now()));
            }
        }
        return clusters;
    }

    /**
     * Distance between two normalised script bodies. Uses the existing
     * {@link FormEvolutionClassifier} machinery (cosine over embeddings if
     * available, Jaccard fallback). For script bodies we have no embedder
     * wired today, so this is effectively
     * {@code 1 − Jaccard(identifiersOfA, identifiersOfB)}. Good enough to
     * group "search this, search that, dedupe, summarize" patterns.
     */
    static double scriptDistance(String a, String b) {
        // FormEvolutionClassifier.classify() does the work; we discard the
        // semver recommendation and pull out the deviation.
        var result = FormEvolutionClassifier.classify(a, b, null);
        return result.deviation();
    }

    /**
     * Normalise a script for similarity comparison: lowercase, collapse
     * whitespace, strip JS comments. The Jaccard fallback inside
     * FormEvolutionClassifier tokenizes on non-alphanumerics already, so
     * this normalisation mostly reduces noise from formatting variation.
     */
    static String normaliseScript(String script) {
        if (script == null) return "";
        var s = script;
        // Strip line comments
        s = s.replaceAll("//[^\n]*", " ");
        // Strip block comments (non-greedy)
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        // Collapse whitespace
        s = s.replaceAll("\\s+", " ");
        return s.toLowerCase().trim();
    }

    // ── Proposal building ──────────────────────────────────────────────────

    /**
     * Build a {@link SkillDraft} from a cluster. Name is inferred from the
     * exemplar script's identifiers; description references the cluster size.
     * The skill body is the exemplar — Phase 5+ may merge across cluster
     * members when the cosine classifier matures.
     */
    static SkillDraft buildSkillDraft(String agentDid, Cluster cluster, String model) {
        if (cluster.size() < MIN_CLUSTER_SIZE) return null;
        var exemplar = cluster.exemplarScript();
        if (exemplar == null || exemplar.isBlank()) return null;

        var name = inferSkillName(exemplar, cluster.members());
        var description = "Recurring script-shape detected from "
            + cluster.size() + " improvised drafts in the last 24h.";
        var rationale = "Pattern detected by SkillMat consolidation: "
            + cluster.size() + " similar scripts (cosine ≤ " + SIMILARITY_THRESHOLD
            + "). Sample summaries: "
            + cluster.members().stream()
                .map(DraftEntry::summary)
                .filter(s -> s != null && !s.isBlank())
                .limit(3)
                .reduce((a, b) -> a + "; " + b)
                .orElse("(none)");

        // Wrap the exemplar in the skill execute() shape so the existing
        // WorkbenchValidator + materializer accept it. Per SkillProposer
        // schema: function execute(params) { ... }.
        var code = "function execute(params) {\n"
            + exemplar
            + "\n}";

        var closesGaps = List.of(
            "recurring code-mode improvisation pattern (×" + cluster.size() + ")");

        // Pre-validate exactly like SkillProposer does, so the steward
        // doesn't see drafts the workbench will reject on approval.
        var validation = WorkbenchValidator.validate(name, "graaljs", code, List.of());
        if (!validation.valid()) {
            log.debug("ScriptDraftConsolidator: drafted skill '{}' fails workbench validation: {}",
                name, validation.summary());
            return null;
        }

        return SkillDraft.pending(
            UUID.randomUUID().toString(),
            agentDid,
            name, description, rationale,
            code, "graaljs",
            closesGaps,
            null,                           // replaces — Phase 5+
            model == null ? "script-draft-consolidator" : model);
    }

    /**
     * Pick a snake_case name for the skill. Heuristic: the most common
     * identifier across the cluster's exemplar that isn't a JS keyword,
     * suffixed with {@code _spell}. Falls back to a hash-based id when no
     * good identifier is found.
     */
    static String inferSkillName(String exemplar, List<DraftEntry> members) {
        // Try to find a verb-shaped identifier from the exemplar — common
        // method calls like {@code .search(}, {@code .read(} are good
        // candidates.
        var candidates = new ArrayList<String>();
        var matcher = Pattern
            .compile("\\.([a-z][a-zA-Z0-9_]+)\\s*\\(")
            .matcher(exemplar == null ? "" : exemplar);
        while (matcher.find()) candidates.add(matcher.group(1));

        if (!candidates.isEmpty()) {
            // Pick the first non-trivial candidate
            for (var c : candidates) {
                if (c.length() >= 3 && !isJsKeyword(c)) {
                    return camelToSnake(c) + "_spell";
                }
            }
        }
        // Fallback — synthesize from the cluster's first few characters.
        var seed = exemplar == null ? "spell" : exemplar.substring(0, Math.min(8, exemplar.length()));
        var hash = Math.abs(seed.hashCode()) % 10000;
        return "drafted_spell_" + hash;
    }

    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private static boolean isJsKeyword(String s) {
        return switch (s) {
            case "log", "warn", "error", "info", "push", "pop", "shift",
                 "slice", "map", "filter", "reduce", "forEach", "for",
                 "let", "var", "const", "if", "else", "return", "function",
                 "true", "false", "null" -> true;
            default -> false;
        };
    }
}
