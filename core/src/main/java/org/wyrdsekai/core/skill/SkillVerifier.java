package org.wyrdsekai.core.skill;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * the runtime verifier.
 *
 * <p>Pure deterministic code: runs a skill's GraalJS source (= {@link SkillDraft#code()})
 * in the item sandbox ({@link ItemScriptExecutor}) against a frozen {@link AnchorHarness}
 * and reports pass/fail per case. <b>No model, large or small.</b> This is the gate that
 * runs at {@code WorkshopPinboard.approve} before a draft is materialized, and the same
 * code a recipient re-runs when a verified skill arrives over the Trading Post / cross-zone.</p>
 *
 * <p><b>Scope:</b> proves CAPABILITY (does the skill do what it claims), NOT safety /
 * welfare-coherence. The substrate / honest-judge gate stays separate and primary — a green
 * verdict here must never be read as "safe".</p>
 */
public final class SkillVerifier {

    private final ItemScriptExecutor executor;

    public SkillVerifier(ItemScriptExecutor executor) {
        this.executor = executor;
    }

    /** Result of running a skill against an {@link AnchorHarness}. */
    public record Verdict(boolean passed, int casesRun, int casesPassed, List<Failure> failures) {}

    /** One failed case: which anchor, which output key, why. */
    public record Failure(int caseIndex, String outputKey, String reason, String source) {}

    /**
     * Run the skill against every case in the harness.
     *
     * @param skillId   identifier for sandbox source-caching + logging
     * @param skillCode GraalJS source ({@code SkillDraft.code})
     * @param harness   the frozen anchor-grounded test suite
     * @param provider  world API provider (a capturing stub at authoring time)
     * @param caps      capability set the skill runs under. Pass a RESTRICTED set
     *                  (e.g. {@code ItemCapabilitySet.of(List.of())}) so a skill that
     *                  reaches for ungranted {@code world.*} surfaces is denied, not silently passed.
     */
    public Verdict verify(String skillId, String skillCode, AnchorHarness harness,
                          ItemWorldApiProvider provider, ItemCapabilitySet caps) {
        var failures = new ArrayList<Failure>();
        var cases = harness.cases();
        int passed = 0;

        for (int i = 0; i < cases.size(); i++) {
            var c = cases.get(i);
            Map<String, Object> out;
            try {
                out = executor.execute(skillId, skillCode, c.params(), provider, caps);
            } catch (RuntimeException e) {
                failures.add(new Failure(i, c.outputKey(), "execution threw: " + e.getMessage(), c.source()));
                continue;
            }
            // The executor returns a structured error map rather than throwing on
            // compile failure, capability denial, or timeout — treat any of those
            // as a verification failure for this case.
            if (out.containsKey("error") || out.containsKey("capability_denied")) {
                failures.add(new Failure(i, c.outputKey(),
                    "skill did not run cleanly: " + out, c.source()));
                continue;
            }
            String reason = checkValue(out.get(c.outputKey()), c.check());
            if (reason == null) {
                passed++;
            } else {
                failures.add(new Failure(i, c.outputKey(), reason, c.source()));
            }
        }
        return new Verdict(failures.isEmpty(), cases.size(), passed, List.copyOf(failures));
    }

    /** @return null if {@code value} satisfies {@code check}, else a human-readable failure reason. */
    private static String checkValue(Object value, AnchorHarness.Check check) {
        switch (check.kind()) {
            case NON_EMPTY -> {
                if (value == null) return "value is null";
                if (value instanceof String s && s.isBlank()) return "value is blank";
                return null;
            }
            case NUMERIC_EQUALS -> {
                if (!(value instanceof Number n)) return "expected a number, got " + value;
                if (!(check.expected() instanceof Number exp)) return "malformed anchor (expected not numeric)";
                double eps = check.epsilon() == null ? 1e-9 : check.epsilon();
                if (Math.abs(n.doubleValue() - exp.doubleValue()) > eps) {
                    return "expected " + exp.doubleValue() + " (±" + eps + "), got " + n.doubleValue();
                }
                return null;
            }
            case STRING_EQUALS -> {
                String exp = String.valueOf(check.expected());
                if (!exp.equals(String.valueOf(value))) {
                    return "expected \"" + exp + "\", got \"" + value + "\"";
                }
                return null;
            }
            case REGEX_MATCHES -> {
                String pat = String.valueOf(check.expected());
                if (value == null || !Pattern.compile(pat).matcher(value.toString()).find()) {
                    return "expected match /" + pat + "/, got \"" + value + "\"";
                }
                return null;
            }
        }
        return "unknown check kind: " + check.kind();
    }
}
