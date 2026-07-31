package org.wyrdsekai.core.naming;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation rules for zone labels and contact aliases.
 *
 * <p>Labels are the short, human-typed names that identify a zone <i>within</i>
 * a household ({@code kitchen}, {@code garage}). Aliases are the names a user
 * gives to someone else's household in their local contacts book ({@code alice},
 * {@code bob-studio}). The two namespaces share validation rules but live in
 * different files and never collide at runtime because their usage sites are
 * distinct.</p>
 *
 * <h2>Charset</h2>
 * <ul>
 *   <li>Lowercase ASCII letters and digits: {@code [a-z0-9]}.</li>
 *   <li>Internal hyphens allowed: {@code bob-studio}.</li>
 *   <li>Cannot start or end with a hyphen.</li>
 *   <li>No underscores, dots, spaces, or uppercase letters.</li>
 * </ul>
 *
 * <h2>Length</h2>
 * <p>1-32 characters. Keeps NATS subjects compact and CLI output readable.</p>
 *
 * <h2>Reserved keywords</h2>
 * <p>{@code home}, {@code self}, {@code me}, {@code here}, {@code origin} are
 * never valid zone labels or contact aliases. These are semantic directives in
 * the travel grammar — {@code travel home} must never
 * be a zone lookup, so we make the keyword unforgeable at the data layer.</p>
 *
 * <p>Rejecting at <i>add</i> time prevents the problem at source, rather than
 * having the resolver defend against it at every lookup. Existing deployments
 * with a zone labelled {@code home} migrate via
 * {@code wyrd zones rename home <new-label>} in Phase 1 of the migration.</p>
 */
public final class ZoneLabels {

    /**
     * Reserved keywords that are never valid as labels or aliases. Order matters
     * only for display — we quote the keyword in error messages, so iteration
     * stability isn't relied on by callers.
     */
    public static final Set<String> RESERVED = Set.of(
        "home", "self", "me", "here", "origin");

    /** Max label length (inclusive). */
    public static final int MAX_LENGTH = 32;

    /**
     * Valid label pattern: lowercase alphanumerics with internal hyphens only.
     * No leading/trailing hyphen (rejects {@code -foo} and {@code foo-}).
     */
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private ZoneLabels() {}

    /**
     * @return true if {@code s} passes charset + length validation, regardless
     *     of reserved status. Useful for the resolver, which needs to detect
     *     syntactically-valid-but-reserved inputs (to emit a specific error
     *     message) vs. purely malformed ones.
     */
    public static boolean isWellFormed(String s) {
        if (s == null) return false;
        if (s.isEmpty() || s.length() > MAX_LENGTH) return false;
        return LABEL.matcher(s).matches();
    }

    /**
     * @return true if {@code s} is one of the reserved keywords. Case-insensitive
     *     to match the spec's "{@code home} is reserved" framing — an operator
     *     typing {@code Home} should still be rejected.
     */
    public static boolean isReserved(String s) {
        if (s == null) return false;
        return RESERVED.contains(s.toLowerCase());
    }

    /**
     * @return true if {@code s} is usable as a label or alias (well-formed AND
     *     not reserved). Callers that need the distinction should use the
     *     two predicates directly.
     */
    public static boolean isValid(String s) {
        return isWellFormed(s) && !isReserved(s);
    }

    /**
     * Throw {@link IllegalArgumentException} with a caller-visible message if
     * {@code s} is not a valid label. Message distinguishes reserved vs.
     * malformed so CLI output can route users to the right remediation.
     *
     * @param s    candidate label
     * @param kind "label" or "alias" — used in the error message
     */
    public static void requireValid(String s, String kind) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(kind + " cannot be empty");
        }
        if (isReserved(s)) {
            throw new IllegalArgumentException(
                "'" + s + "' is a reserved keyword and cannot be used as a " + kind
                    + ". Reserved: " + String.join(", ", RESERVED));
        }
        if (!isWellFormed(s)) {
            throw new IllegalArgumentException(
                "'" + s + "' is not a valid " + kind
                    + ". Must be 1-" + MAX_LENGTH
                    + " lowercase alphanumerics, internal hyphens allowed,"
                    + " no leading/trailing hyphen.");
        }
    }
}
