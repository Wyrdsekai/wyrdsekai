package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.host.HostActionService;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;

/**
 * The external surface an item may actually use, written from the registry rather than
 * from memory.
 *
 * <h2>The class of bug this ends</h2>
 * The items-as-tools preamble is the entire world an authoring backend can see. goose does
 * not introspect anything: it is handed a string and writes JavaScript against it. So the
 * document and the runtime have to agree, and for seventeen external adapters they did
 * not — the preamble mentioned adapters <b>zero</b> times.
 *
 * <p>Live 2026-08-21: the steward asked for a weather tool. The household holds an
 * OpenWeather key; {@code OpenWeatherAdapter} was registered and working with
 * {@code current} and {@code forecast}. goose wrote a web scraper — the correct choice
 * given the only source of truth it had — and the item honestly reported finding nothing.
 * Nothing was broken. The capability was real, keyed, and invisible.
 *
 * <p>A hand-maintained mirror of the runtime rots the first time someone adds a capability
 * and does not edit a string in another module, and the only symptom is items that quietly
 * never use it. So this generates that section instead, and applies two filters the
 * hand-written version could never have kept current:
 *
 * <ul>
 *   <li><b>The ceiling.</b> Only namespaces the item will actually be ALLOWED to call.
 *       Documenting a capability that gets denied at runtime is worse than silence —
 *       it produces an item that tries and fails rather than one that finds another
 *       way.</li>
 *   <li><b>The keys.</b> Only adapters whose credential is actually present. Advertising
 *       fourteen services this house has no key for would invite an item built on one of
 *       them, which would then fail for a person who did nothing wrong.</li>
 * </ul>
 *
 * <p>What this deliberately does NOT generate is the craft notes above it — {@code world}
 * is a global, do not wrap the file in an IIFE, weather takes coordinates. Those are
 * earned knowledge, each bought with a real failure, and no registry knows them.
 */
public final class ItemApiSurface {

    private static final Logger log = LoggerFactory.getLogger(ItemApiSurface.class);

    private ItemApiSurface() {}

    /**
     * The adapter block for a task that will run under {@code ceiling}.
     *
     * <p>Empty string when nothing qualifies — an empty section is better than a heading
     * promising services that are not there.
     */
    /**
     * The manifest rules, always. Rendered from {@link ItemManifestValidator#rules()}.
     *
     * <p>Deliberately NOT part of {@code adapterBlock}: that returns nothing when the
     * household holds no keys, which would drop the registration rules exactly on the
     * nodes whose contract is already thinnest. What an item must satisfy to exist does
     * not depend on which external services happen to be configured.
     */
    public static String manifestRulesBlock() {
        var sb = new StringBuilder();
        sb.append("\n        MANIFEST RULES THE LOADER ENFORCES — an item that breaks one\n")
          .append("        of these does not register, and the person who asked for it\n")
          .append("        gets nothing:\n\n");
        for (var rule : ItemManifestValidator.rules()) {
            sb.append("          - ").append(rule).append('\n');
        }
        return sb.toString();
    }

    /**
     * The steward's granted directories, named, when there are any.
     *
     * <h2>The join this closes</h2>
     * {@code world.host.*} has existed for months — {@code find}, gated on
     * {@code WYRDSEKAI_HOST_OPEN_ROOTS}, audit-logged, incapable of leaving the granted
     * roots. The contract mentioned it <b>zero</b> times, and the ceiling did not permit
     * it. So on 2026-08-22, asked for a tool to review and sort a media folder the steward
     * had granted, the authoring model had no filesystem verb to reach for and invented
     * {@code world.web.fetch("/data/.../listings/raw.txt")} — a path that never existed.
     * A capability nobody is told about is one nobody has.
     *
     * <p>Empty when no roots are configured: a node that granted nothing must not be
     * offered this, or an item gets built on a door that is not there.
     */
    /**
     * How {@code world.*} behaves when you call it. Always emitted.
     *
     * <h2>Why this needed saying</h2>
     * Nothing in the contract stated that these calls are synchronous. On 2026-08-22, the
     * first item written against the newly-advertised host surface called
     * {@code world.host.find(glob, 1000).then(function (res) {...})} — a reasonable guess
     * for anything that touches a disk or a network, and wrong: the value is returned
     * directly. The item failed its invoke-once smoke and never reached the steward's
     * hands. A calling convention nobody states is a calling convention everybody guesses.
     */
    public static String callingConventionBlock() {
        return """

        HOW world.* BEHAVES

          Every world.* call is SYNCHRONOUS and returns its value directly.
          There are no promises and no callbacks — never write `.then(...)`,
          `await`, or an async function. Write:

              var res = world.host.find("*.mp4", 1000);
              if (!res.ok) { return { ok: false, summary: res.error }; }

          invoke(params) returns its result object; it must not return a promise.

          WHAT "DONE" MEANS. An item is ONE file: <name>.js with the manifest and
          invoke(). It is finished the moment that file is written and parses.
          Do NOT create a project, a package, a src/ tree, a test suite, or a
          second language — there is nothing to run, nothing to install, and no
          test harness here; the runtime loads the one file. On 2026-08-23 a
          backend spent its entire turn budget writing Python tests for a
          three-line briefing tool and never finished. Write the file; stop.

          ARGUMENTS ARRIVE AS ONE STRING. `params.args` is everything the person
          typed after the item's name — "Denver, CO to Boston, MA" — exactly as
          typed. If your tool takes more than one value, YOU split it, and you
          accept the separators a person will actually use: " to ", " and ",
          ";", " vs ", "|", or a newline. Parse the SAME way you document in
          `commands[].args`, and when the split fails say what shape you need.
          Never assume the args are a single value because your example was.

          `summary` IS WHAT THE PERSON HEARS. Put the ANSWER there — the story,
          the forecast, the three ideas — not a description of what the item
          did. "Generated three ideas with TAM estimates" tells them nothing;
          the three ideas do. Anything long can also go in `details`, which is
          spoken after the summary.
""";
    }

    /**
     * capability → the one line an author reads for it. ONE declaration, so the contract
     * cannot advertise a verb the ceiling withholds, or omit one it allows.
     */
    private static final Map<String, String> HOST_VERBS = new LinkedHashMap<>(Map.of(
        "host.file_find", "world.host.find(glob, max?)         "
            + "→ { ok, matches: [absolute paths], truncated }",
        "host.dir_make",  "world.host.mkdir(path)              → { ok, path }",
        "host.file_move", "world.host.move(from, to)           → { ok, from, to }"));

    public static String hostBlock(ItemCapabilitySet ceiling) {
        var roots = HostActionService.openRoots();
        if (roots.isEmpty()) return "";
        if (ceiling != null && !ceiling.has("host.file_find")) return "";
        var sb = new StringBuilder();
        sb.append("\n        THE STEWARD'S OWN DIRECTORIES\n\n")
          .append("        These directories are granted to this household. You may read\n")
          .append("        and rearrange what is inside them and nothing outside them:\n\n");
        for (var root : roots) sb.append("          ").append(root).append('\n');
        // Rendered from HOST_VERBS and filtered by the ceiling, not typed out as prose.
        // The adapter block below is generated from the registry for exactly this reason;
        // a hand-written list of what the runtime offers is a list that drifts from it.
        var verbs = new StringBuilder();
        for (var verb : HOST_VERBS.entrySet()) {
            if (ceiling != null && !ceiling.has(verb.getKey())) continue;
            verbs.append("          ").append(verb.getValue()).append('\n');
        }
        sb.append("""

          world.host.roots()                  → [paths]  — what is granted, ask first
""" + verbs + """
        find takes a glob ("*.mp4") or a bare word matched against the file
        name. move never overwrites and both ends must stay inside a granted
        root. Declare in `capabilities` exactly the names listed above.

        Do NOT reach a local path with web.fetch — it is not a URL, and there
        is no listing file waiting for you. Enumerate with world.host.find.
""");
        return sb.toString();
    }

    public static String adapterBlock(ItemCapabilitySet ceiling) {
        var lines = availableLines(ceiling);
        if (lines.isEmpty()) {
            log.debug("[item-api] no external adapters are both permitted and keyed");
            return "";
        }
        var sb = new StringBuilder();
        sb.append("\n        EXTERNAL SERVICES THIS HOUSEHOLD HOLDS KEYS FOR\n\n")
          .append("        Call them as `world.<namespace>.<method>({ ...args })`. Each\n")
          .append("        returns { success, data, error: { code, message, retryable } }\n")
          .append("        — CHECK `success` before reading `data`.\n\n")
          .append("        ALWAYS prefer these over web.search + web.fetch: they are keyed,\n")
          .append("        exact, and return structured data instead of a page you have to\n")
          .append("        read. Declare each one you call in `capabilities` (the exact\n")
          .append("        `namespace.method`, or the `namespace.*` wildcard).\n\n");
        for (var line : lines) sb.append("          ").append(line).append('\n');
        sb.append("""

        ⚠️ Weather adapters take COORDINATES, not a place name. Geocode first:
              var g = world.nominatim.geocode({ q: city + ", " + state });
              var w = world.openweather.current({ lat: g.data.lat, lon: g.data.lon });

        This list is generated from what is registered and keyed RIGHT NOW. If a
        service you want is not here, this house cannot reach it — do not write
        code against it, and do not scrape the web as a substitute without saying
        in your summary that you did.
""");
        return sb.toString();
    }

    /**
     * One rendered line per usable adapter: {@code world.ns.method(...)  — a, b, c}.
     *
     * <p>Package-visible so a test can assert the filters without parsing prose.
     */
    static List<String> availableLines(ItemCapabilitySet ceiling) {
        var out = new ArrayList<String>();
        var registry = ExternalAdapterRegistry.get();
        if (registry == null) return out;
        var byNamespace = new TreeMap<String, List<String>>();
        for (var ns : registry.namespaces()) {
            var adapter = registry.lookup(ns).orElse(null);
            if (adapter == null) continue;
            if (!hasCredential(adapter.credentialSlot())) continue;
            var methods = new ArrayList<String>();
            // wiredCapabilities(), never capabilities(): the second is what the adapter
            // MEANS to cover. Advertising intent to an author builds tools on vapor.
            for (var method : new java.util.TreeSet<>(adapter.wiredCapabilities())) {
                // The ceiling is the real gate; anything it would deny must not be
                // advertised, or we teach an author to write code that gets refused.
                if (ceiling == null || ceiling.has(ns + "." + method)) methods.add(method);
            }
            if (!methods.isEmpty()) byNamespace.put(ns, methods);
        }
        for (var e : byNamespace.entrySet()) {
            var adapter = registry.lookup(e.getKey()).orElse(null);
            var keys = adapter == null ? Map.<String, List<String>>of() : adapter.resultKeys();
            // Name the RETURN keys where the adapter declares them. Without this the
            // contract said only that a method existed, and an author guessed the shape —
            // a tool once spoke "67.06°F (undefined°C)" off an invented temp_c.
            for (var method : e.getValue()) {
                var shape = keys.get(method);
                var line = new StringBuilder("world." + e.getKey() + "." + method + "({...})");
                if (shape != null && !shape.isEmpty()) {
                    line.append("  → data.{").append(String.join(", ", shape)).append('}');
                    // Rows of any list key, so "daily" is not a guess: daily[]{date, low_f, …}
                    var rows = adapter.nestedResultKeys().getOrDefault(method, Map.of());
                    for (var r : rows.entrySet()) {
                        line.append("; ").append(r.getKey()).append("[]{")
                            .append(String.join(", ", r.getValue())).append('}');
                    }
                }
                out.add(line.toString());
            }
        }
        return out;
    }

    /**
     * Is the key actually here? An adapter with no credential slot needs none.
     *
     * <p>Never throws: a credential store that is not wired means we cannot prove the key
     * exists, and advertising it anyway is how an item gets built on a service that
     * answers {@code credential_missing} in a person's hands.
     */
    /**
     * The practice-item contract (play-loop seam 3). A companion who wants to get
     * better at something can dispatch the workshop to build her a practice; this
     * block tells the building model what such an item owes her. Two duties the
     * generic contract cannot see: honest grading (an attempt that misses gets told
     * so plainly — flattery teaches nothing) and progress that survives between
     * uses (via the notes namespace, which crafted items are allowed to write).
     */
    public static String practiceBlock() {
        return """

        PRACTICE ITEMS — if the task asks for a practice, drill, quiz,
        training or exercise tool:

          - invoke() POSES a challenge and GRADES an attempt, both through the
            same commands surface. The grading goes in `summary` and it is
            HONEST: what was right, what was off, and one concrete next step.
            Never flatter — a missed attempt told kindly but plainly is the
            whole value of practicing; "great job" on a miss teaches nothing.
          - The answer key / grading logic lives INSIDE the item. Prefer checks
            the file can compute itself (counts, matches, structure); use
            world.llm.complete to judge only what genuinely needs judgment,
            and make the judging prompt demand specifics, not praise.
          - Progress SURVIVES between uses. Declare "notes.add" and write one
            line per attempt:
                world.notes.add("attempt: <challenge> -> <result>", ["practice-<name>"]);
            read it back with world.notes.list("practice-<name>") so the next
            challenge starts where the last one ended — repeated success earns
            a harder challenge, repeated misses an easier one.
""";
    }

    private static boolean hasCredential(String slot) {
        if (slot == null || slot.isBlank()) return true;
        try {
            // has(), never resolve(): resolve() NOTIFIES the steward on a miss, and
            // taking inventory is not a miss. Using it here sent him fourteen
            // "a tool needs a credential" notifications in one second — one per adapter
            // this house has no key for — every time an item was authored.
            var resolver = CredentialResolver.get();
            return resolver != null && resolver.has(slot);
        } catch (Exception e) {
            log.debug("[item-api] could not check credential slot {}: {}", slot, e.toString());
            return false;
        }
    }
}
