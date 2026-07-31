package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-companion routing preferences for delegated coding tasks.
 *
 * <p>. Surfaces through the Hearth (Drives
 * Mirror / Coding Slate) and is consumed by the GraalJS selection policy
 * script (see {@code scripts/policy/coding-backend.js}). The household
 * policy may override these — companions <i>express</i> preferences, the
 * household decides whether to honor them.</p>
 *
 * <p>All fields are nullable / defaulted so soul manifests forged before
 * Phase 1b round-trip cleanly. Empty defaults mean "no opinion".</p>
 *
 * @param preferredBackend       Hint: when no per-task override applies,
 *                               try this backend first. {@code null}
 *                               means "follow household fallback chain".
 * @param avoidBackends          Backends the companion would rather skip
 *                               (e.g. an agent with bad past results from
 *                               a particular paid tier). The policy script
 *                               filters these out of the available pool.
 * @param taskTypeOverrides      Map of task type tag (e.g. {@code "explore"},
 *                               {@code "refactor"}, {@code "implement_feature"})
 *                               to backend name. Beats {@code preferredBackend}
 *                               when the task type matches.
 */
public record CodingPreferences(
    @JsonProperty("preferred_backend") String preferredBackend,
    @JsonProperty("avoid_backends") List<String> avoidBackends,
    @JsonProperty("task_type_overrides") Map<String, String> taskTypeOverrides
) {
    @JsonCreator
    public CodingPreferences {
        // Round-trip tolerance — companions forged before this field existed
        // hydrate as empty rather than null so the policy script can dot-walk
        // without null checks.
        if (avoidBackends == null) avoidBackends = List.of();
        if (taskTypeOverrides == null) taskTypeOverrides = new LinkedHashMap<>();
    }

    /** Empty preferences — companion has no opinion, household policy decides. */
    public static CodingPreferences empty() {
        return new CodingPreferences(null, List.of(), new LinkedHashMap<>());
    }

    /** True when the companion has expressed no preferences at all. */
    @JsonIgnore
    public boolean isEmpty() {
        return (preferredBackend == null || preferredBackend.isBlank())
            && (avoidBackends == null || avoidBackends.isEmpty())
            && (taskTypeOverrides == null || taskTypeOverrides.isEmpty());
    }
}
