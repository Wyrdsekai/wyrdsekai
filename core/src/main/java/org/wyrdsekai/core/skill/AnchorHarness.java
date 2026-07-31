package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A frozen, deterministic verification harness for a self-authored skill
 *
 * <p>Each {@link VerificationCase} is grounded in an independently-verifiable
 * <b>anchor</b> — a documented reference value, output format, or invariant — and
 * NOT in the answer to any target task (the leakage barrier). At authoring time the
 * strong model compiles anchors mined from the open world into these cases; at the
 * gate and at runtime they execute as pure code.</p>
 *
 * <p>The harness is a <b>portable JSON artifact</b>: it travels WITH the skill item,
 * so a recipient (Trading Post copy / cross-zone transit) can re-run it locally with
 * zero model calls before trusting the skill. Keep all values JSON-primitive
 * (String / Number / Boolean) so the harness round-trips.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnchorHarness(
    String skillName,
    List<VerificationCase> cases
) {

    /** Run the skill with {@code params}; check result key {@code outputKey} against {@code check}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerificationCase(
        Map<String, Object> params,
        String outputKey,
        Check check,
        /** provenance: the documented anchor this case is grounded in (for the human + audit). */
        String source
    ) {}

    /** A deterministic assertion. Runs as pure code — no model. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Check(
        Kind kind,
        /** numeric / string / regex literal; null for {@link Kind#NON_EMPTY}. */
        Object expected,
        /** tolerance for {@link Kind#NUMERIC_EQUALS}; null otherwise (defaults to 1e-9). */
        Double epsilon
    ) {
        public enum Kind { NUMERIC_EQUALS, STRING_EQUALS, NON_EMPTY, REGEX_MATCHES }

        public static Check numeric(double expected, double epsilon) {
            return new Check(Kind.NUMERIC_EQUALS, expected, epsilon);
        }
        public static Check string(String expected) { return new Check(Kind.STRING_EQUALS, expected, null); }
        public static Check nonEmpty() { return new Check(Kind.NON_EMPTY, null, null); }
        public static Check regex(String pattern) { return new Check(Kind.REGEX_MATCHES, pattern, null); }
    }
}
