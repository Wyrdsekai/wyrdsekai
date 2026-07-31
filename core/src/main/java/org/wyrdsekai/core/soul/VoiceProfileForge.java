package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Self-evolving pass for the explicit voice profile (#410).
 *
 * <p>Runs inside the deep-sleep cycle, after the implicit adapter training:
 * examines the agent's recent output + current voice clauses, then proposes
 * ONE structured change (set/unset) with a reason. The proposal is validated
 * against bounds before it reaches {@link VoiceProfileService} — a malformed
 * or over-budget suggestion is dropped silently, the agent skips a
 * self-revision for that cycle, and the next cycle tries again.
 *
 * <p>Design intent:
 * <ul>
 *   <li><b>One change per cycle.</b> Slow, legible evolution. Keeps history
 *       humanly readable in the Study UI.</li>
 *   <li><b>LLM-proposed, service-applied.</b> The meta-LLM produces JSON;
 *       this class validates and delegates to the same service the steward
 *       uses, so a Forge proposal and a human edit are indistinguishable in
 *       history (author differs, everything else is uniform).</li>
 *   <li><b>Frozen is honored.</b> If {@link VoiceProfile#frozen()} the Forge
 *       short-circuits before even calling the meta-LLM — no inference burn
 *       on an agent whose voice is locked.</li>
 * </ul>
 *
 * <p>Not in scope for this class: the deep-sleep trigger, adapter training,
 * corpus assembly. Those stay in {@link DeepSleepTrainer}. This class is pure
 * logic + parsing so it can be unit-tested with a canned
 * {@link InferenceCallback}.
 */
public final class VoiceProfileForge {

    private static final Logger log = LoggerFactory.getLogger(VoiceProfileForge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max clauses allowed in a profile — overflow rejects the proposal. */
    public static final int MAX_CLAUSES = 12;
    /** Max length of a clause value (hard cap; prompt requests 120). */
    public static final int MAX_VALUE_LEN = 200;
    /** Valid clause key — lowercase, hyphen-friendly, no whitespace. */
    private static final Pattern KEY_PATTERN =
        Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final VoiceProfileService service;

    public VoiceProfileForge(VoiceProfileService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * One forge pass: optionally apply one proposed revision.
     *
     * @param did         Companion DID.
     * @param sampleTurns Recent conversational turns (used in the prompt as
     *                    "here's what you sounded like"). Small list — the
     *                    meta-LLM only needs a few examples.
     * @param callback    The inference callback — given a prompt, returns the
     *                    raw model output. Mocked in tests; wired to the
     *                    meta-LLM in production.
     * @return The applied revision, or empty optional if the proposal was
     *         rejected (frozen, parse failure, bounds violation, or LLM
     *         returned no_change).
     */
    public Optional<ProposedRevision> runOnce(String did, List<String> sampleTurns,
                                               InferenceCallback callback) {
        Objects.requireNonNull(did, "did");
        Objects.requireNonNull(callback, "callback");

        var current = service.get(did).orElse(VoiceProfile.empty());
        if (current.frozen()) {
            log.info("Voice forge skipped for {} — profile is frozen", did);
            return Optional.empty();
        }

        var prompt = buildPrompt(current, sampleTurns != null ? sampleTurns : List.of());
        String raw;
        try {
            raw = callback.infer(prompt);
        } catch (Exception e) {
            log.warn("Voice forge inference failed for {}: {}", did, e.getMessage());
            return Optional.empty();
        }

        var parsed = parseProposal(raw);
        if (parsed.isEmpty()) {
            log.info("Voice forge: no actionable proposal for {} (raw: {})",
                did, raw != null ? raw.substring(0, Math.min(raw.length(), 80)) : "null");
            return Optional.empty();
        }
        var proposal = parsed.get();

        // Validate AGAINST CURRENT state — a "set" that would overflow
        // MAX_CLAUSES must be rejected; an "unset" of a key that doesn't exist
        // is a no-op at the profile level but we still accept it for audit.
        var violation = validate(current, proposal);
        if (violation != null) {
            log.info("Voice forge proposal rejected for {}: {}", did, violation);
            return Optional.empty();
        }

        try {
            applyProposal(did, proposal);
            log.info("Voice forge applied for {}: {} {} ({})",
                did, proposal.action(), proposal.key(), proposal.reason());
            return Optional.of(proposal);
        } catch (IllegalStateException frozen) {
            // Race: profile was frozen between read and write.
            log.info("Voice forge apply raced with freeze for {}: {}", did, frozen.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Voice forge apply failed for {}: {}", did, e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Prompt construction ───────────────────────────────────────

    static String buildPrompt(VoiceProfile current, List<String> sampleTurns) {
        var sb = new StringBuilder();
        sb.append("You are a reflective voice-profile editor. Propose ONE small "
                + "change to the companion's voice clauses based on the recent "
                + "turns below. Keep each clause value under 120 characters. "
                + "Emit a single JSON object, nothing else.\n\n");
        sb.append("## Current voice profile\n");
        if (current.clauses().isEmpty()) {
            sb.append("(empty — no clauses yet)\n");
        } else {
            for (var e : current.clauses().entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        sb.append("\n## Recent turns\n");
        if (sampleTurns.isEmpty()) {
            sb.append("(no turns available)\n");
        } else {
            for (var t : sampleTurns) {
                sb.append("- ").append(t).append('\n');
            }
        }
        sb.append("\n## Response format\n");
        sb.append("Return JSON like {\"action\":\"set\",\"key\":\"reflective-pacing\","
                + "\"value\":\"slow, sentence per breath\",\"reason\":\"recent turns lean "
                + "rushed; this clause nudges toward breath-pacing\"}.\n");
        sb.append("Or {\"action\":\"unset\",\"key\":\"greeting-tone\","
                + "\"reason\":\"redundant with reflective-pacing\"}.\n");
        sb.append("Or {\"action\":\"no_change\",\"reason\":\"voice is in good shape this cycle\"} "
                + "to skip this cycle.\n");
        return sb.toString();
    }

    // ─── Proposal parsing ──────────────────────────────────────────

    static Optional<ProposedRevision> parseProposal(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        // Meta-LLM may wrap in prose/markdown; extract the first JSON object.
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        var json = raw.substring(start, end + 1);
        try {
            JsonNode node = MAPPER.readTree(json);
            var action = textOrNull(node, "action");
            if (action == null) return Optional.empty();
            var reason = textOrNull(node, "reason");
            return switch (action) {
                case "set" -> {
                    var key = textOrNull(node, "key");
                    var value = textOrNull(node, "value");
                    if (key == null || value == null) yield Optional.empty();
                    yield Optional.of(new ProposedRevision(
                        Action.SET, key, value,
                        reason != null ? reason : ""));
                }
                case "unset" -> {
                    var key = textOrNull(node, "key");
                    if (key == null) yield Optional.empty();
                    yield Optional.of(new ProposedRevision(
                        Action.UNSET, key, null,
                        reason != null ? reason : ""));
                }
                case "no_change" -> Optional.empty();  // treated as "skip"
                default -> Optional.empty();
            };
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull()) return null;
        var s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    // ─── Validation + apply ────────────────────────────────────────

    /** Returns null if the proposal is acceptable, or a reason string if not. */
    static String validate(VoiceProfile current, ProposedRevision proposal) {
        if (!KEY_PATTERN.matcher(proposal.key()).matches()) {
            return "invalid key '" + proposal.key() + "' (must match "
                + KEY_PATTERN.pattern() + ")";
        }
        if (proposal.action() == Action.SET) {
            if (proposal.value() == null || proposal.value().isBlank()) {
                return "SET with blank value";
            }
            if (proposal.value().length() > MAX_VALUE_LEN) {
                return "value too long (" + proposal.value().length() + " > "
                    + MAX_VALUE_LEN + ")";
            }
            // Would this push us over the clause cap?
            if (!current.clauses().containsKey(proposal.key())
                    && current.clauses().size() >= MAX_CLAUSES) {
                return "would exceed MAX_CLAUSES=" + MAX_CLAUSES;
            }
        }
        if (proposal.reason() == null || proposal.reason().isBlank()) {
            return "missing reason";
        }
        return null;
    }

    private void applyProposal(String did, ProposedRevision proposal) {
        var reason = "forge: " + proposal.reason();
        switch (proposal.action()) {
            case SET -> service.setClause(did, proposal.key(), proposal.value(),
                reason, "forge");
            case UNSET -> service.unsetClause(did, proposal.key(), reason, "forge");
        }
    }

    // ─── Public value types ────────────────────────────────────────

    public enum Action { SET, UNSET }

    /** A single forge proposal before (or after) it's applied. */
    public record ProposedRevision(Action action, String key, String value, String reason) {}

    /** Single-shot inference closure — production uses InferenceRouter, tests use a canned function. */
    @FunctionalInterface
    public interface InferenceCallback {
        String infer(String prompt) throws Exception;
    }
}
