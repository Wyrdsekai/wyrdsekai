package org.wyrdsekai.core.recipe;

import java.util.Objects;

/**
 * Evaluates a {@link RecipeStep.Gate} condition against a {@link RecipeContext}
 * Deliberately tiny + dependency-free — NO scripting engine — so a
 * gate cannot do anything but compare a value. Grammar:
 *
 * <pre>{@code <operand> <op> <operand>}   op ∈ { >= <= > < == != }</pre>
 *
 * <p>An operand is a context variable name, or a literal (number, true/false, or a
 * "quoted" / bare string). {@code {{ }}} templates are resolved first. Fails SAFE: a
 * missing variable or malformed condition evaluates to {@code false} (gate does not pass),
 * so a gate can only ever block — never wave through — on bad input.
 */
public final class GateEvaluator {

    private static final String[] OPS = {">=", "<=", "==", "!=", ">", "<"};

    private GateEvaluator() {}

    public static boolean evaluate(String condition, RecipeContext ctx) {
        if (condition == null || condition.isBlank()) return false;
        String expr = ctx.resolve(condition.trim());

        String op = null;
        int idx = -1;
        for (String candidate : OPS) {
            int i = expr.indexOf(candidate);
            if (i >= 0) { op = candidate; idx = i; break; }
        }
        if (op == null) return false; // not a comparison → fail safe

        Object lhs = operand(expr.substring(0, idx).trim(), ctx);
        Object rhs = operand(expr.substring(idx + op.length()).trim(), ctx);
        if (lhs == null || rhs == null) {
            // only == / != are meaningful with a null operand (missing var)
            return switch (op) {
                case "==" -> Objects.equals(lhs, rhs);
                case "!=" -> !Objects.equals(lhs, rhs);
                default -> false;
            };
        }

        Double ln = asNumber(lhs), rn = asNumber(rhs);
        if (ln != null && rn != null) {
            int c = Double.compare(ln, rn);
            return switch (op) {
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case ">"  -> c > 0;
                case "<"  -> c < 0;
                case "==" -> c == 0;
                case "!=" -> c != 0;
                default   -> false;
            };
        }
        // non-numeric: only equality is defined
        return switch (op) {
            case "==" -> valueEquals(lhs, rhs);
            case "!=" -> !valueEquals(lhs, rhs);
            default   -> false; // ordering on non-numbers is invalid → fail safe
        };
    }

    /** Resolve an operand to a context value or a literal. */
    private static Object operand(String token, RecipeContext ctx) {
        if (token.isEmpty()) return null;
        if (ctx.has(token)) return ctx.get(token);
        if (token.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (token.equalsIgnoreCase("false")) return Boolean.FALSE;
        if ((token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2)
                || (token.startsWith("'") && token.endsWith("'") && token.length() >= 2)) {
            return token.substring(1, token.length() - 1);
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return token; // bare string literal
        }
    }

    private static Double asNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static boolean valueEquals(Object a, Object b) {
        if (a instanceof Boolean || b instanceof Boolean) {
            return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
