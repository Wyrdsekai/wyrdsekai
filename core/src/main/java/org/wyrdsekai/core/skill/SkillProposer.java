package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.SkillUsageTracker;
import org.wyrdsekai.core.agent.SkillUsageTracker.CapabilityGap;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Drafts a {@link SkillDraft} from a triggered {@link CapabilityGap}.
 *
 * <p>Stateless utility — caller owns the LLM call and persistence.
 * The {@link #buildSystemPrompt} / {@link #buildUserPrompt} pair
 * produces the cap:reasoning prompt; {@link #parse} turns the model
 * output into a {@code SkillDraft}.</p>
 */
public final class SkillProposer {

    private static final Logger log = LoggerFactory.getLogger(SkillProposer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Recent failed-skill context to include with the prompt. */
    private static final int CONTEXT_RECENT_LIMIT = 3;

    /** Recent successful skills shown to the proposer (so it doesn't propose dupes). */
    private static final int EXISTING_SKILL_LIMIT = 25;

    /**
     * v1.5 — reason text stamped on drafts the proposer
     * couldn't or didn't author an explicit embodiment for. The pinboard
     * highlights drafts carrying this shim so the steward catches it and
     * the agent learns to consciously override on revise.
     */
    public static final String V1_DRAFT_REASON = "v1-draft, replace before materialize";

    private SkillProposer() {}

    static final String SYSTEM_PROMPT = """
        You are the agent's workbench voice. Given a recurring capability gap,
        draft a soul-skill that would close it.

        Embodiment is REQUIRED, not optional. Every
        skill draft must declare how it touches the world: either it's
        silent (and you state why) or it emits a body-language event when
        invoked. Silence is allowed but must be a declared choice — not a
        default. Decide what the skill *does in the room* before you write
        the code.

        Output STRICT JSON matching this schema and nothing else:
        {
          "name": "snake_case_identifier",
          "description": "one sentence the steward will read",
          "rationale": "why this fills the gap",
          "code": "function execute(params) { /* GraalJS source */ }",
          "runtime": "graaljs",
          "closes_gaps": ["<gap-description-1>", "<gap-description-2>"],
          "replaces": null,
          "embodiment": {
            "silent": false,
            "emits": ["body_language"],
            "descriptor_template": "{actor} <how-this-skill-looks-from-outside>"
          }
        }

        Embodiment shapes (pick one — both valid):
        - Silent: {"silent": true, "reason": "<why this skill produces no body event>"}
        - Emits:  {"silent": false, "emits": ["body_language" | "ambient_shift" | ...],
                   "descriptor_template": "{actor} <verb-phrase observers see>"}

        Constraints:
        - runtime is always "graaljs" today.
        - code must define `function execute(params)` and stay under 4096 bytes.
        - Assume the ItemWorldApiProvider host API surface (world.*, item.*).
        - Set "replaces" to the name of an existing skill if this one supersedes it.
          Otherwise null.
        - closes_gaps must list at least one of the gap descriptions you saw.
        - embodiment is REQUIRED. If a skill genuinely has no body trace,
          declare silent with a real reason — do not omit the field.
        """;

    /** Compose the user prompt with gap + tracker + existing-skills context. */
    public static String buildUserPrompt(
            CapabilityGap gap, SkillUsageTracker tracker, FamilyLocker locker, String requesterDid) {
        var sb = new StringBuilder();
        sb.append("Gap: \"").append(gap.description()).append("\" (")
          .append(gap.occurrences()).append(" occurrences since ")
          .append(gap.detectedAt()).append(")\n\n");

        // Recent failed-skill UsageRecord context strings.
        if (tracker != null) {
            var failedContexts = collectRecentFailures(tracker);
            if (!failedContexts.isEmpty()) {
                sb.append("Recent failed-skill context:\n");
                for (var c : failedContexts) {
                    sb.append("- ").append(c).append("\n");
                }
                sb.append("\n");
            }
        }

        // Existing skills — names only, so the proposer can pick replaces=...
        if (locker != null && requesterDid != null) {
            var names = listExistingSkillNames(locker, requesterDid);
            if (!names.isEmpty()) {
                sb.append("Existing skills (do not duplicate, may replace):\n");
                for (var n : names) sb.append("- ").append(n).append("\n");
                sb.append("\n");
            }
        }

        sb.append("Draft a skill that closes this gap. Output JSON only.");
        return sb.toString();
    }

    /** System prompt for the proposal LLM call. */
    public static String buildSystemPrompt() { return SYSTEM_PROMPT; }

    /**
     * Parse a model response into a {@link SkillDraft}.
     * Returns {@code null} if the output isn't usable.
     */
    public static SkillDraft parse(
            String llmOutput, String agentDid, CapabilityGap gap, String proposedByModel) {
        if (llmOutput == null || llmOutput.isBlank()) return null;
        var json = extractJson(llmOutput);
        if (json == null) {
            log.warn("SkillProposer: no JSON object in LLM output");
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            String name = node.path("name").asText("").trim();
            String description = node.path("description").asText("").trim();
            String rationale = node.path("rationale").asText("").trim();
            String code = node.path("code").asText("");
            String runtime = node.path("runtime").asText("graaljs");
            String replaces = node.has("replaces") && !node.get("replaces").isNull()
                ? node.get("replaces").asText() : null;

            var closes = new ArrayList<String>();
            var arr = node.get("closes_gaps");
            if (arr != null && arr.isArray()) {
                for (var n : arr) closes.add(n.asText());
            }
            if (closes.isEmpty()) closes.add(gap.description());

            if (name.isBlank() || code.isBlank()) {
                log.warn("SkillProposer: missing name/code in proposal");
                return null;
            }

            // Pre-validate against the same rules the workbench will check at
            // approval time. If it would fail there, drop it now — surfacing
            // a draft we already know is doomed wastes the steward's attention.
            var validation = WorkbenchValidator.validate(name, runtime, code, List.of());
            if (!validation.valid()) {
                log.warn("SkillProposer: drafted skill '{}' fails workbench validation: {}",
                    name, validation.summary());
                return null;
            }

            // v1.5 — extract the agent's declared
            // embodiment block, falling back to the v1-shim when missing
            // or structurally invalid. The pinboard surfaces shim-bearing
            // drafts so the steward (and the agent) notice.
            var embodiment = parseEmbodiment(node);

            return SkillDraft.pending(
                UUID.randomUUID().toString(),
                agentDid,
                name, description, rationale,
                code, runtime,
                closes, replaces,
                proposedByModel,
                embodiment);
        } catch (Exception e) {
            log.warn("SkillProposer: parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * v1.5 — read an {@code embodiment} block from a
     * proposer JSON response. Returns the v1-default silent shim
     * ({@link SkillDraft#defaultEmbodimentShim}) when the field is absent,
     * malformed, or structurally invalid. The fall-back is deliberate:
     * v1.5 rejects only at the hard gates (validator / hot-reload /
     * bridge), while letting proposals through with a clearly-marked shim
     * so the steward can see the omission on the pinboard.
     */
    static ItemEmbodimentSpec parseEmbodiment(JsonNode root) {
        if (root == null) return SkillDraft.defaultEmbodimentShim();
        var emb = root.get("embodiment");
        if (emb == null || emb.isNull() || !emb.isObject()) {
            return SkillDraft.defaultEmbodimentShim();
        }
        try {
            boolean silent = emb.path("silent").asBoolean(false);
            if (silent) {
                String reason = emb.path("reason").asText("").trim();
                if (reason.isBlank()) {
                    log.warn("SkillProposer: silent embodiment missing reason — using v1-shim");
                    return SkillDraft.defaultEmbodimentShim();
                }
                return ItemEmbodimentSpec.silent(reason);
            }
            var emitsNode = emb.get("emits");
            var emits = new ArrayList<String>();
            if (emitsNode != null && emitsNode.isArray()) {
                for (var n : emitsNode) {
                    var v = n.asText("").trim();
                    if (!v.isEmpty()) emits.add(v);
                }
            }
            if (emits.isEmpty()) {
                log.warn("SkillProposer: non-silent embodiment missing emits list — using v1-shim");
                return SkillDraft.defaultEmbodimentShim();
            }
            String descriptor = emb.path("descriptor_template").asText(null);
            return ItemEmbodimentSpec.emits(emits, descriptor);
        } catch (Exception e) {
            log.warn("SkillProposer: embodiment parse failed ({}) — using v1-shim", e.getMessage());
            return SkillDraft.defaultEmbodimentShim();
        }
    }

    /** Convenience: parse + persist + return the stored draft. Returns null on parse failure. */
    public static SkillDraft proposeAndStore(
            String llmOutput, String agentDid, CapabilityGap gap,
            String proposedByModel, SkillDraftStore store) {
        var draft = parse(llmOutput, agentDid, gap, proposedByModel);
        if (draft == null) return null;
        if (store != null) store.upsert(draft);
        return draft;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static List<String> collectRecentFailures(SkillUsageTracker tracker) {
        var out = new ArrayList<String>();
        for (var skillId : tracker.trackedSkills()) {
            var recs = tracker.recordsFor(skillId);
            // Walk from most recent backwards; collect contexts of failed records.
            for (int i = recs.size() - 1; i >= 0 && out.size() < CONTEXT_RECENT_LIMIT; i--) {
                var r = recs.get(i);
                if (!r.success() && r.context() != null && !r.context().isBlank()) {
                    out.add(skillId + ": " + r.context());
                }
            }
            if (out.size() >= CONTEXT_RECENT_LIMIT) break;
        }
        return out;
    }

    private static List<String> listExistingSkillNames(FamilyLocker locker, String did) {
        try {
            var items = locker.byCategory("skill", did);
            var names = new ArrayList<String>();
            for (var it : items) {
                var n = it.label();
                if (n != null && !n.isBlank()) names.add(n);
                if (names.size() >= EXISTING_SKILL_LIMIT) break;
            }
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Extract a JSON object from raw LLM output (may be wrapped in fences). */
    static String extractJson(String text) {
        // Try ```json ... ``` first.
        int fence = text.indexOf("```json");
        if (fence >= 0) {
            int nl = text.indexOf('\n', fence);
            if (nl >= 0) {
                int end = text.indexOf("```", nl + 1);
                if (end > nl) return text.substring(nl + 1, end).strip();
            }
        }
        // Fallback: bare {...}.
        int start = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (start >= 0 && last > start) return text.substring(start, last + 1);
        return null;
    }
}
