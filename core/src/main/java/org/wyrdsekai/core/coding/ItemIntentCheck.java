package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Does this item do what was actually asked?
 *
 * <h2>The gap this fills</h2>
 * {@link ItemContractCheck} answers "will this register and run". Both of the items that
 * reached the steward's hands on 2026-08-21 in working order passed it completely, and
 * neither did what he asked:
 *
 * <ul>
 *   <li>He asked for a tool that "speaks out loud a story based on what it found". The
 *       item called {@code world.llm.summarize(text, "summarize into exactly two
 *       paragraphs")} and returned an accurate précis. A summariser wearing the word
 *       story.</li>
 *   <li>He asked for weather by city and state. The household holds an OpenWeather key.
 *       The item scraped the web, found nothing readable, and said so honestly.</li>
 * </ul>
 *
 * <p>Nothing was broken either time. The contract gates cannot see this class of defect,
 * because the file is perfectly well-formed — it is the wrong tool, competently built.
 *
 * <h2>Two rules, chosen so they cannot rot</h2>
 * A keyword table mapping topics to services is exactly the hand-maintained mirror that
 * caused the original problem, so there isn't one. Instead:
 *
 * <ol>
 *   <li><b>Scraping when a keyed service exists.</b> If the item reaches for the open web
 *       while this household holds keys the item is permitted to use, say so and list
 *       them. Always correct to raise — the author decides whether one of them fits, and
 *       it needs no per-topic knowledge.</li>
 *   <li><b>Summarising when asked to compose.</b> {@code llm.summarize} condenses text
 *       that already exists; it cannot invent. If the request asks for something written
 *       — a story, a tale, a poem — and the item only summarises, that is a mismatch
 *       between the verb asked for and the verb used.</li>
 * </ol>
 *
 * <p>Both are advisory: they produce a repair PROMPT, never a rejection. An item that
 * scrapes the web on purpose is legitimate, and the author is allowed to say so.
 */
public final class ItemIntentCheck {

    private ItemIntentCheck() {}

    /** Words that ask for prose to be COMPOSED rather than condensed. */
    private static final List<String> COMPOSE_WORDS = List.of(
        "story", "stories", "tale", "tales", "fairy", "poem", "poetry", "verse",
        "narrate", "narrative", "retell", "compose", "invent", "make up", "fiction");

    /** Words that ask for a PRACTICE — something that challenges, grades and
     *  remembers (play-loop seam 3; the contract is practiceBlock() in the
     *  preamble). */
    private static final List<String> PRACTICE_WORDS = List.of(
        "practice", "practise", "drill", "quiz", "flashcard", "flash card",
        "exercise me", "train me", "training tool", "test me", "help me get better",
        "help me learn", "small practice");

    /** How an item reaches the open web. */
    private static final List<String> SCRAPES = List.of(
        "world.web.search", "world.web.fetch");

    /**
     * Mismatches between what was asked and what was built. Empty when nothing to say.
     *
     * @param request the person's own words, as dispatched
     * @param script  the file the backend wrote
     * @param ceiling what the item will be allowed to call
     */
    public static List<String> gaps(String request, String script, ItemCapabilitySet ceiling) {
        var out = new ArrayList<String>();
        if (script == null || script.isBlank()) return List.copyOf(out);
        var lower = script.toLowerCase(Locale.ROOT);
        var asked = request == null ? "" : request.toLowerCase(Locale.ROOT);

        if (scrapes(lower)) {
            var available = ItemApiSurface.availableLines(ceiling);
            if (!available.isEmpty()) {
                out.add("this item reaches the open web (web.search / web.fetch) while "
                    + "this household holds keys you are allowed to use. Keyed services "
                    + "return exact, structured data instead of a page you have to read. "
                    + "Available now:\n"
                    + String.join("\n", available.stream().map(l -> "      " + l).toList())
                    + "\n    If one of these answers the request, use it instead. If none "
                    + "does, keep the web calls and say so in your summary."
                    + "\n    CHANGE ONLY THE CALLS. Do not restructure the file, do not "
                    + "wrap it in a function, do not move invoke() — a file that stops "
                    + "registering is worse than one that scrapes the web.");
            }
        }

        // A DECLARED CAPABILITY THE BODY NEVER CALLS IS A CLAIM, NOT A FEATURE.
        // Live on the home node (2026-08-24): asked for a weather tool, goose
        // declared ["web.search", "web.fetch", "nominatim.geocode",
        // "openweather.current"] — proof the preamble advertised the keyed
        // services — and then wrote an invoke() whose only call is
        // world.library.search, so the person got dictionary rows and
        // StackExchange posts where the forecast should have been. The
        // open-web nudge above never fired because nothing in the BODY
        // scrapes. Same family as "a claim of files is not a file": the
        // manifest is what it says, the body is what it does. Mechanical
        // check, no semantics: a namespaced capability whose namespace never
        // appears as world.<ns> in the body was declared and abandoned.
        var abandoned = new ArrayList<String>();
        for (var cap : declaredCapabilities(script)) {
            var dot = cap.indexOf('.');
            if (dot <= 0) continue;                    // tier-1 style, no namespace
            var ns = cap.substring(0, dot);
            if ("llm".equals(ns) || "embed".equals(ns)) continue; // generic compute
            if (!lower.contains("world." + ns + ".")) abandoned.add(cap);
        }
        if (!abandoned.isEmpty() && abandoned.size() < declaredCapabilities(script).size()) {
            out.add("the manifest declares " + abandoned + " and invoke() never calls "
                + (abandoned.size() == 1 ? "it" : "them") + ". If the request needs "
                + (abandoned.size() == 1 ? "that service" : "those services")
                + " — and a declared capability usually means it does — CALL "
                + (abandoned.size() == 1 ? "it" : "them") + " in the body. If not, "
                + "remove the declaration. A capability in the manifest is a promise "
                + "about what the tool does, and the person will be handed whatever "
                + "the body actually produces.");
        }

        // A practice that forgets is a toy. The preamble's PRACTICE ITEMS block
        // asks for one notes line per attempt so the next challenge can start
        // where the last one ended; a practice-shaped request whose body never
        // writes to any persistent namespace will greet every use as the first.
        // Mechanical check, no semantics: does the body call a store at all.
        if (wantsPractice(asked) && !persistsAnything(lower)) {
            out.add("the request asks for a practice tool (\"" + practiceWordIn(asked)
                + "\"), but invoke() never writes to any persistent namespace, so "
                + "every use starts from zero and progress cannot exist. In invoke(), "
                + "after grading an attempt, add ONE line:\n"
                + "      world.notes.add(\"attempt: <challenge> -> <result>\", "
                + "[\"practice-<item name>\"]);\n"
                + "    and read it back at the start with "
                + "world.notes.list(\"practice-<item name>\") to pick the next "
                + "challenge. Declare \"notes.add\" in the manifest capabilities. "
                + "CHANGE ONLY invoke()'s body and the capabilities array — do not "
                + "restructure the file.");
        }

        if (wantsComposition(asked) && summarisesOnly(lower)) {
            out.add("the request asks for something WRITTEN (\"" + composeWordIn(asked)
                + "\"), but this item only calls world.llm.summarize, which condenses "
                + "text that already exists and cannot invent. Use world.llm.complete "
                + "with a prompt that asks for the piece to be composed — otherwise the "
                + "person receives an accurate summary where they asked for a story.");
        }
        return List.copyOf(out);
    }

    /** The capabilities the manifest declares, parsed shallowly from the array
     *  literal. Regex over source, not a JS parse — same fidelity as every
     *  other check in this class, and a malformed manifest fails the loader
     *  long before it gets here. */
    static List<String> declaredCapabilities(String script) {
        if (script == null) return List.of();
        var m = CAPABILITIES_ARRAY.matcher(script);
        if (!m.find()) return List.of();
        var out = new ArrayList<String>();
        var inner = INNER_STRING.matcher(m.group(1));
        while (inner.find()) out.add(inner.group(1));
        return List.copyOf(out);
    }

    private static final Pattern CAPABILITIES_ARRAY =
        Pattern.compile("capabilities\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern INNER_STRING =
        Pattern.compile("[\"']([a-z0-9_.*]+)[\"']");

    static boolean scrapes(String lowerScript) {
        return SCRAPES.stream().anyMatch(lowerScript::contains);
    }

    static boolean wantsComposition(String lowerRequest) {
        return composeWordIn(lowerRequest) != null;
    }

    static String composeWordIn(String lowerRequest) {
        if (lowerRequest == null) return null;
        for (var w : COMPOSE_WORDS) {
            if (lowerRequest.contains(w)) return w;
        }
        return null;
    }

    /** Summarises and never composes. */
    static boolean summarisesOnly(String lowerScript) {
        return lowerScript.contains("llm.summarize") && !lowerScript.contains("llm.complete");
    }

    static boolean wantsPractice(String lowerRequest) {
        return practiceWordIn(lowerRequest) != null;
    }

    static String practiceWordIn(String lowerRequest) {
        if (lowerRequest == null) return null;
        for (var w : PRACTICE_WORDS) {
            if (lowerRequest.contains(w)) return w;
        }
        return null;
    }

    /** Every persistent namespace a crafted item may write. Any one of them
     *  counts — the point is that SOMETHING survives between uses, not which
     *  store the author chose. */
    static boolean persistsAnything(String lowerScript) {
        return lowerScript.contains("world.notes.add")
            || lowerScript.contains("world.journal.write")
            || lowerScript.contains("world.memory.add")
            || lowerScript.contains("world.library.add");
    }
}
