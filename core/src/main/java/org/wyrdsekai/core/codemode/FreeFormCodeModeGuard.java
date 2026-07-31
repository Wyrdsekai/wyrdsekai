package org.wyrdsekai.core.codemode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Track A Phase 2c — hallucination guard for free-form
 * code-mode scripts.
 *
 * <p>Free-form code-mode lets the model write JavaScript that calls into a
 * typed namespace bundle ({@link CodeModeNamespace#forActor}). The bundle
 * exposes a finite set of top-level identifiers — equipped item aliases
 * plus {@code world} and {@code mcp}. If the model writes
 * {@code calendar.next()} or {@code email.summarize(...)} the script
 * compiles cleanly under GraalJS but throws at runtime with a confusing
 * "calendar is not defined" message that the companion then narrates back
 * to the bondholder.
 *
 * <p>This guard runs <em>before</em> dispatch to {@link
 * org.wyrdsekai.scripting.codemode.CodeModeExecutor#run}. It scans the
 * script for top-level identifiers used in member-access or call position
 * ({@code foo.bar(...)} or {@code foo.bar}) and returns any that aren't
 * declared in the namespace bundle. Callers can then:
 *
 * <ul>
 *   <li>Warn-log + run anyway (Phase 2c default — the model usually
 *       recovers from the runtime error gracefully and we want the
 *       observability data).</li>
 *   <li>Block + ask the model to retry with the actual surface (Phase 2d
 *       — once we have a soak-tested baseline of how often the model
 *       hallucinates).</li>
 * </ul>
 *
 * <p>The guard intentionally does NOT use a full JS parser. A regex pass
 * over a stripped-comment-and-string script is enough to catch obvious
 * hallucinations and never fires false positives on legitimate language
 * features — the cost of a missed detection is a runtime error the model
 * already handles, while a false positive would block a working script.
 *
 * <p>Stateless and side-effect free.
 */
public final class FreeFormCodeModeGuard {

    private FreeFormCodeModeGuard() {}

    /**
     * JS keywords + builtin globals that are ALWAYS available regardless
     * of what we wire into the namespace bundle. Identifiers in this set
     * never count as hallucinations even when used as {@code foo.bar(...)}.
     *
     * <p>Kept narrow on purpose — we want {@code calendar.next()} flagged,
     * but we don't want to flag {@code Math.max(...)} or {@code JSON.parse(...)}.
     * Adding to this list is a deliberate decision about what the model is
     * allowed to lean on without our explicit blessing.
     */
    private static final Set<String> JS_BUILTINS = Set.of(
        "console",       // console.log / console.warn — wired into observation buffer
        "JSON",          // JSON.parse / JSON.stringify
        "Math",          // Math.max / Math.floor / etc.
        "Object",        // Object.keys / Object.entries
        "Array",         // Array.from / Array.isArray
        "String",        // String.fromCharCode etc.
        "Number",        // Number.parseInt / Number.isFinite
        "Boolean",       // rarely needed but harmless
        "Date",          // Date.now / new Date(...)
        "Promise",       // Promise.all / Promise.race
        "Error",         // throw new Error(...)
        "Symbol",        // Symbol.iterator etc.
        "Reflect",       // Reflect.has / Reflect.get
        "Map",           // new Map()
        "Set",           // new Set()
        "WeakMap",
        "WeakSet",
        "RegExp",        // new RegExp(...) and string.match etc.
        "globalThis",    // sandbox-scoped, but a legal reference
        "undefined",
        "NaN",
        "Infinity",
        "parseInt",      // top-level functions, not member-access — listed for future
        "parseFloat",
        "isNaN",
        "isFinite",
        "encodeURIComponent",
        "decodeURIComponent",
        "encodeURI",
        "decodeURI"
    );

    /**
     * Reserved-word and operator-context identifiers that surface in JS
     * grammar but aren't real identifiers — we strip these to avoid noise.
     * For example {@code typeof foo === "string"} would have us see
     * {@code typeof} as a candidate top-level identifier; it isn't.
     */
    private static final Set<String> JS_KEYWORDS = Set.of(
        "var", "let", "const", "function", "return", "if", "else", "for",
        "while", "do", "break", "continue", "switch", "case", "default",
        "try", "catch", "finally", "throw", "new", "delete", "typeof",
        "instanceof", "in", "of", "void", "yield", "async", "await",
        "true", "false", "null", "this", "super", "class", "extends",
        "import", "export", "static"
    );

    /**
     * Identifier in member-access or call position. Captures the head
     * identifier of a {@code foo.bar} or {@code foo(...)} expression.
     * Anchored on word boundary so {@code _foo} and {@code $foo} don't
     * match accidentally; JS allows {@code $} and {@code _} as identifier
     * starts but they're rare in machine-written code and we'd rather
     * skip than false-positive.
     */
    private static final Pattern HEAD_IDENT = Pattern.compile(
        "\\b([A-Za-z_$][A-Za-z0-9_$]*)(?=\\s*[.(])");

    /**
     * Strings (single/double/backtick) and comments (line + block). We
     * remove these before scanning so identifiers mentioned in narration
     * strings or commented-out code don't trip the guard.
     */
    private static final Pattern STRINGS_AND_COMMENTS = Pattern.compile(
        "//[^\\n]*"                                       // line comment
        + "|/\\*[\\s\\S]*?\\*/"                           // block comment
        + "|\"(?:\\\\.|[^\"\\\\])*\""                     // double-quoted
        + "|'(?:\\\\.|[^'\\\\])*'"                        // single-quoted
        + "|`(?:\\\\.|[^`\\\\])*`"                        // template literal (no interpolation parsing)
    );

    /**
     * Local variable declarations — {@code const x = ...}, {@code let y = ...},
     * {@code var z = ...}, plus function parameters. We harvest these names
     * from the script and add them to the allowed-identifier set so the
     * guard doesn't flag a model's own locals as hallucinations.
     */
    private static final Pattern LOCAL_DECL = Pattern.compile(
        "\\b(?:const|let|var|function|class)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /**
     * Destructuring assignments — both object and array forms. Captures the
     * raw bracket-body so {@link #harvestLocals} can split out individual
     * identifier names. Without this pattern the model writing
     * {@code const { sources, count } = ...} would have {@code sources} flagged
     * as an unknown top-level identifier on its first member-access (e.g.
     * {@code sources.length}).
     */
    private static final Pattern LOCAL_DESTRUCTURE = Pattern.compile(
        "\\b(?:const|let|var)\\s*\\{([^}]+)\\}\\s*="            // { a, b: c, ...rest } = ...
        + "|\\b(?:const|let|var)\\s*\\[([^\\]]+)\\]\\s*=");      // [ a, b ] = ...

    private static final Pattern FUNCTION_PARAMS = Pattern.compile(
        "\\bfunction\\s+(?:[A-Za-z_$][A-Za-z0-9_$]*)?\\s*\\(([^)]*)\\)"
        + "|\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*=>"            // arrow with single bare param
        + "|\\(([^)]*)\\)\\s*=>"                            // arrow with paren-wrapped params
    );

    /**
     * @param script        the JS source extracted by {@link FreeFormCodeModeParser}
     * @param knownTopLevel the keys present in the namespace bundle
     *                      (typically {@code namespace.keySet()}).
     * @return a list of identifiers used as {@code <name>.<method>} or
     *         {@code <name>(...)} that are NOT in {@code knownTopLevel},
     *         not in JS builtins, not local variables, and not keywords.
     *         Empty list = no hallucinations detected. Order is the order
     *         of first occurrence; duplicates collapsed.
     */
    public static List<String> findUnknownTopLevelIdentifiers(
            String script, Set<String> knownTopLevel) {
        if (script == null || script.isBlank()) return List.of();

        // 1. Strip strings + comments so identifiers mentioned inside them
        // don't pollute the scan.
        var stripped = STRINGS_AND_COMMENTS.matcher(script).replaceAll(" ");

        // 2. Harvest locals from declarations + function params.
        var locals = harvestLocals(stripped);

        // 3. Build the full set of "this is fine" names.
        var allowed = new HashSet<String>();
        if (knownTopLevel != null) allowed.addAll(knownTopLevel);
        allowed.addAll(JS_BUILTINS);
        allowed.addAll(JS_KEYWORDS);
        allowed.addAll(locals);

        // 4. Scan for head identifiers in member-access / call position.
        var unknown = new LinkedHashSet<String>();
        var m = HEAD_IDENT.matcher(stripped);
        while (m.find()) {
            var name = m.group(1);
            if (name == null || name.isEmpty()) continue;
            if (allowed.contains(name)) continue;
            // After a `.` the next identifier is a member name, not a head.
            // Check the char before this match: if it's `.` we're inside
            // a chain (e.g. `a.b.c()` — `b` is a member of `a`, `c` is the
            // call). Skip.
            int start = m.start();
            if (start > 0) {
                char prev = stripped.charAt(start - 1);
                if (prev == '.') continue;
            }
            unknown.add(name);
        }
        return List.copyOf(unknown);
    }

    private static Set<String> harvestLocals(String stripped) {
        var out = new HashSet<String>();
        var m = LOCAL_DECL.matcher(stripped);
        while (m.find()) out.add(m.group(1));

        // Destructuring — the body inside `{ ... }` or `[ ... ]` may contain:
        //   bare identifier:        a
        //   alias:                  a: aa            (we want `aa`, the local name)
        //   default value:          a = 7
        //   rest spread:            ...rest
        //   nested (ignored — uncommon in machine-written code):  { x: { y } }
        var dm = LOCAL_DESTRUCTURE.matcher(stripped);
        while (dm.find()) {
            String body = dm.group(1) != null ? dm.group(1) : dm.group(2);
            if (body == null) continue;
            for (var raw : body.split(",")) {
                var name = raw.trim();
                if (name.isEmpty()) continue;
                if (name.startsWith("...")) name = name.substring(3).trim();
                int colon = name.indexOf(':');
                if (colon >= 0) {
                    // `a: aa` — aa is the local; aa may itself have a default.
                    name = name.substring(colon + 1).trim();
                }
                int eq = name.indexOf('=');
                if (eq >= 0) name = name.substring(0, eq).trim();
                if (name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) out.add(name);
            }
        }

        var fm = FUNCTION_PARAMS.matcher(stripped);
        while (fm.find()) {
            for (int g = 1; g <= 3; g++) {
                var grp = fm.group(g);
                if (grp == null || grp.isBlank()) continue;
                for (var raw : grp.split(",")) {
                    var name = raw.trim();
                    // Strip default-value suffix `name = expr`
                    int eq = name.indexOf('=');
                    if (eq >= 0) name = name.substring(0, eq).trim();
                    // Strip rest-spread `...name`
                    if (name.startsWith("...")) name = name.substring(3).trim();
                    if (name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) out.add(name);
                }
            }
        }
        return out;
    }

    /**
     * Convenience: true iff the script contains <em>any</em> unknown
     * identifier — useful for a quick branch that doesn't need the full
     * list. Use {@link #findUnknownTopLevelIdentifiers} when you want to
     * surface the names in a log message.
     */
    public static boolean containsHallucination(String script, Set<String> knownTopLevel) {
        return !findUnknownTopLevelIdentifiers(script, knownTopLevel).isEmpty();
    }
}
