package org.wyrdsekai.core.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * generates behaviour-changing mutants of a GraalJS skill so a harness can be
 * mutation-tested for <b>teeth</b> (does it actually discriminate a correct skill from a wrong one?).
 *
 * <p>The mutation operators are chosen to perturb the <i>value</i> a skill returns while keeping the
 * code runnable — numeric-literal increments and arithmetic/relational operator swaps. A harness that
 * only checks <i>presence</i> (NON_EMPTY) survives these; only a harness that checks the actual value
 * catches them. That is exactly the toothlessness we want to detect (see {@link HarnessMutationGate}).</p>
 *
 * <p>Pure string mutation, deterministic, no model. Compound operators ({@code ++}, {@code +=},
 * {@code //}, …) are guarded so mutants stay syntactically valid. Skills with no arithmetic to perturb
 * (e.g. pure string passthrough) yield few/no mutants — the gate then fails open (can't assess).</p>
 */
public final class SkillMutator {

    /** A single behaviour-changing variant of a skill. */
    public record Mutant(String description, String code) {}

    private static final int MAX_MUTANTS = 24;

    // Integer or decimal literal not glued to an identifier/dot (so we don't touch x2 or 1.2.3).
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?(?![\\w.])");

    private SkillMutator() {}

    public static List<Mutant> mutate(String code) {
        var out = new ArrayList<Mutant>();
        if (code == null || code.isBlank()) return out;

        // 1. Numeric-literal increment — the strongest value-perturbation signal.
        Matcher m = NUMBER.matcher(code);
        while (m.find() && out.size() < MAX_MUTANTS) {
            String tok = m.group();
            String bumped = bump(tok);
            out.add(new Mutant("literal " + tok + "->" + bumped + " @" + m.start(),
                code.substring(0, m.start()) + bumped + code.substring(m.end())));
        }

        // 2. Arithmetic operator swaps (+<->-, *</->/), binary-context only.
        addOperatorSwaps(code, out, '+', '-');
        addOperatorSwaps(code, out, '-', '+');
        addOperatorSwaps(code, out, '*', '/');
        addOperatorSwaps(code, out, '/', '*');

        // 3. A couple of relational swaps (multi-char first so single-char scan skips them).
        addStringSwaps(code, out, "<=", ">=");
        addStringSwaps(code, out, ">=", "<=");

        return out.size() > MAX_MUTANTS ? out.subList(0, MAX_MUTANTS) : out;
    }

    /** Swap each standalone binary occurrence of {@code from} to {@code to}, one mutant per site. */
    private static void addOperatorSwaps(String code, List<Mutant> out, char from, char to) {
        for (int i = 1; i < code.length() - 1 && out.size() < MAX_MUTANTS; i++) {
            if (code.charAt(i) != from) continue;
            char prev = code.charAt(i - 1), next = code.charAt(i + 1);
            // Skip compound operators: ++ -- += -= *= /= // /* and the mirror cases.
            if (prev == from || next == from) continue;          // ++  --  //  **
            if (next == '=' || prev == '=') continue;            // +=  ==  etc.
            if (from == '/' && (next == '*' || prev == '*')) continue; // /*  */ comment
            out.add(new Mutant("op '" + from + "'->'" + to + "' @" + i,
                code.substring(0, i) + to + code.substring(i + 1)));
        }
    }

    /** Replace each occurrence of {@code from} with {@code to}, one mutant per site. */
    private static void addStringSwaps(String code, List<Mutant> out, String from, String to) {
        int idx = 0;
        while ((idx = code.indexOf(from, idx)) >= 0 && out.size() < MAX_MUTANTS) {
            out.add(new Mutant("rel '" + from + "'->'" + to + "' @" + idx,
                code.substring(0, idx) + to + code.substring(idx + from.length())));
            idx += from.length();
        }
    }

    private static String bump(String numericToken) {
        if (numericToken.contains(".")) {
            double v = Double.parseDouble(numericToken) + 1.0;
            // Preserve a clean form; the only requirement is that it differs.
            return (v == Math.floor(v)) ? String.format("%.1f", v) : Double.toString(v);
        }
        try {
            return Long.toString(Long.parseLong(numericToken) + 1);
        } catch (NumberFormatException e) {
            return numericToken + "1"; // pathological overflow fallback — still a different literal
        }
    }
}
