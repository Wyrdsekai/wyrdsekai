package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code world.*} surface a scripted item can actually call, read from the code that
 * serves it — and a static check that a script's calls resolve against it.
 *
 * <p>Live 2026-09-08: {@code use journal} in the household's Study died with
 * {@code TypeError: invokeMember (list) on ItemWorldApi$JournalApi failed: Unknown identifier:
 * list}. A copy of the journal item in the data directory called {@code world.journal.list},
 * a method that had been renamed; the manifest was valid, the entrypoint was there, and every
 * check the loader made passed. A script whose calls do not exist is broken before its first
 * use, and the loader can know that at load time.
 *
 * <p>The surface is what {@link ItemWorldApi} exports: each {@code @HostAccess.Export} field
 * whose type is a nested {@code *Api} class is a namespace, and that class's exported public
 * methods are its members (one level of sub-namespace, {@code world.soul.fragments}, the same
 * way). Adapter namespaces ({@code world.openweather}, …) are resolved dynamically from a
 * registry the provider holds; a namespace this surface does not know is left alone, never
 * flagged — the check is only ever wrong in the direction of silence.
 */
public final class ItemApiSurface {

    private ItemApiSurface() {}

    /** A call the surface cannot resolve: the call as written and a sentence that carries the fix. */
    public record Unresolved(String call, String reason) {}

    /** The argument counts a member accepts: the fixed ones, and {@code varargsMin} (or -1) when one overload is variadic. */
    record Arity(Set<Integer> fixed, int varargsMin) {
        boolean accepts(int n) { return fixed.contains(n) || (varargsMin >= 0 && n >= varargsMin); }
        String describe() {
            var parts = new ArrayList<String>();
            for (var n : new TreeSet<>(fixed)) parts.add(Integer.toString(n));
            if (varargsMin >= 0) parts.add(varargsMin + " or more");
            return String.join(" or ", parts);
        }
    }

    /** {@code "ns"} or {@code "ns.sub"} → member → the arities its overloads accept. Filled by {@link #build()}; declared first so it exists when build() runs. */
    private static final Map<String, Map<String, Arity>> ARITY = new LinkedHashMap<>();
    private static final Map<String, Map<String, Set<String>>> SURFACE = build();

    /** {@code world.<ns>.<member>(} or {@code world.<ns>.<sub>.<member>(}. */
    private static final Pattern CALL = Pattern.compile(
        "\\bworld\\.([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)(?:\\.([A-Za-z_]\\w*))?\\s*\\(");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)//.*$");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /** The static namespaces on {@code world}. */
    public static Set<String> namespaces() { return Collections.unmodifiableSet(SURFACE.keySet()); }

    /** The exported methods of a namespace (empty for one the surface does not know). */
    public static Set<String> methods(String namespace) {
        var ns = SURFACE.get(namespace);
        return ns == null ? Set.of() : Collections.unmodifiableSet(ns.getOrDefault("", Set.of()));
    }

    /** The sub-namespaces of a namespace ({@code soul} → {@code fragments, imprints}). */
    public static Set<String> subNamespaces(String namespace) {
        var ns = SURFACE.get(namespace);
        if (ns == null) return Set.of();
        var out = new TreeSet<String>();
        for (var k : ns.keySet()) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /**
     * Every {@code world.*} call in {@code script} that names a static namespace the surface
     * knows and a member it does not have. Comments are stripped first. Calls into namespaces
     * the surface does not know (adapters) are not reported.
     */
    public static List<Unresolved> check(String script) {
        var out = new ArrayList<Unresolved>();
        if (script == null || script.isBlank()) return out;
        var seen = new java.util.LinkedHashSet<String>();
        var text = BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(script).replaceAll("")).replaceAll("");
        Matcher m = CALL.matcher(text);
        while (m.find()) {
            var ns = m.group(1); var second = m.group(2); var third = m.group(3);
            var space = SURFACE.get(ns);
            if (space == null) continue;   // a dynamic adapter namespace, or a member we cannot see: silence
            String call, member, arityKey; Set<String> have;
            if (third != null && space.containsKey(second)) {
                call = "world." + ns + "." + second + "." + third; member = third; have = space.get(second);
                arityKey = ns + "." + second;
            } else {
                call = "world." + ns + "." + second; member = second; have = space.getOrDefault("", Set.of());
                arityKey = ns;
            }
            if (!have.contains(member)) {
                if (seen.add(call)) out.add(new Unresolved(call, reason(call, member, have)));
                continue;
            }
            // The member exists: does this call hand it a number of arguments an overload takes?
            // Host methods have no defaults — one argument short is "no applicable overload" at
            // runtime (the room.emit copies that passed one argument, household node 2026-09-08).
            var arity = ARITY.getOrDefault(arityKey, Map.of()).get(member);
            if (arity == null) continue;
            int n = countArgs(text, m.end());
            if (n < 0 || arity.accepts(n)) continue;
            var key = call + "/" + n;
            if (seen.add(key)) out.add(new Unresolved(call, call + " called with " + n + " argument" + (n == 1 ? "" : "s")
                + "; it takes " + arity.describe() + " — the call will fail with 'no applicable overload'"));
        }
        return out;
    }

    /**
     * The number of top-level arguments in the call whose opening parenthesis ends just before
     * {@code from}. Strings, template literals and nested brackets are skipped over; -1 when the
     * call cannot be read (unbalanced, or a spread argument), which the check treats as silence.
     */
    static int countArgs(String text, int from) {
        int depth = 0, args = 0; boolean any = false; char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '\'', '"', '`' -> { quote = c; any = true; }
                case '(', '[', '{' -> { depth++; any = true; }
                case ')', ']', '}' -> {
                    if (depth == 0) return c == ')' ? (any ? args + 1 : 0) : -1;
                    depth--;
                }
                case ',' -> { if (depth == 0) args++; else any = true; }
                case '.' -> {
                    if (depth == 0 && text.startsWith("...", i)) return -1;   // spread: count unknown
                    any = true;
                }
                default -> { if (!Character.isWhitespace(c)) any = true; }
            }
        }
        return -1;
    }

    static String reason(String call, String member, Set<String> have) {
        var hint = nearest(member, have);
        var has = have.isEmpty() ? "nothing callable" : String.join(", ", have);
        return call + " does not exist on this node" + (hint == null ? "" : " (did you mean " + hint + "?)")
            + "; " + call.substring(0, call.lastIndexOf('.')) + " has: " + has;
    }

    /** The closest existing member by edit distance, when it is close enough to be a rename. */
    static String nearest(String member, Set<String> have) {
        String best = null; int bestD = Integer.MAX_VALUE;
        for (var h : have) {
            int d = distance(member.toLowerCase(), h.toLowerCase());
            if (d < bestD) { bestD = d; best = h; }
        }
        if (best == null) return null;
        // a rename, a prefix, or a synonym-length neighbour — not a wild guess
        boolean prefix = best.toLowerCase().startsWith(member.toLowerCase()) || member.toLowerCase().startsWith(best.toLowerCase());
        return (bestD <= Math.max(2, member.length() / 2) || prefix) ? best : null;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            var t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static Map<String, Map<String, Set<String>>> build() {
        var surface = new LinkedHashMap<String, Map<String, Set<String>>>();
        for (Field f : ItemWorldApi.class.getFields()) {
            if (!f.isAnnotationPresent(HostAccess.Export.class) || Modifier.isStatic(f.getModifiers())) continue;
            var type = f.getType();
            if (!type.getSimpleName().endsWith("Api")) continue;
            var space = new LinkedHashMap<String, Set<String>>();
            space.put("", exported(type));
            ARITY.put(f.getName(), arities(type));
            for (Field sub : type.getFields()) {
                if (sub.isAnnotationPresent(HostAccess.Export.class) && sub.getType().getSimpleName().endsWith("Api")) {
                    space.put(sub.getName(), exported(sub.getType()));
                    ARITY.put(f.getName() + "." + sub.getName(), arities(sub.getType()));
                }
            }
            surface.put(f.getName(), space);
        }
        return surface;
    }

    private static Map<String, Arity> arities(Class<?> type) {
        var fixed = new LinkedHashMap<String, Set<Integer>>();
        var varargs = new LinkedHashMap<String, Integer>();
        for (Method m : type.getMethods()) {
            if (!m.isAnnotationPresent(HostAccess.Export.class)) continue;
            if (m.isVarArgs()) {
                varargs.merge(m.getName(), m.getParameterCount() - 1, Math::min);
            } else {
                fixed.computeIfAbsent(m.getName(), k -> new TreeSet<>()).add(m.getParameterCount());
            }
        }
        var out = new LinkedHashMap<String, Arity>();
        for (var name : fixed.keySet()) out.put(name, new Arity(Set.copyOf(fixed.get(name)), varargs.getOrDefault(name, -1)));
        for (var name : varargs.keySet()) out.putIfAbsent(name, new Arity(Set.of(), varargs.get(name)));
        return out;
    }

    /** The arities the surface accepts for {@code world.<namespace>.<member>} (empty when unknown). */
    public static java.util.Optional<String> arityOf(String namespace, String member) {
        var a = ARITY.getOrDefault(namespace, Map.of()).get(member);
        return a == null ? java.util.Optional.empty() : java.util.Optional.of(a.describe());
    }

    private static Set<String> exported(Class<?> type) {
        var names = new TreeSet<String>();
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(HostAccess.Export.class)) names.add(m.getName());
        }
        // exported fields that are not namespaces are members too (constants, sub-objects)
        for (Field f : type.getFields()) {
            if (f.isAnnotationPresent(HostAccess.Export.class) && !f.getType().getSimpleName().endsWith("Api")) names.add(f.getName());
        }
        return names;
    }
}
