package org.wyrdsekai.core.agent.affordance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How well does a tool match the thing the person actually asked for?
 *
 * <p>The tool menu used to be ranked by drive pressure alone, so it answered "what is this
 * agent drawn to right now?" and never "what was she asked?". Measured on second-node 2026-07-13:
 * asked <em>"what is 17 times 3?"</em>, mia's eight surfaced tools were
 * {@code summon_familiar, dispatch_bunshin, bunshin_check_in}. The calculator was one of her
 * 110 tools and could not reach the menu. She delegated the arithmetic to the coding backend,
 * which reported SUCCESS having touched zero files. A model can only choose from what it is
 * shown — so this is the root of the whole "talks but doesn't do" family, and it was never
 * the agent's failure.</p>
 *
 * <p>Deliberately dumb and deterministic: lexical, no inference, no embedding call, no added
 * latency or tokens. It does not need to be clever — it needs to make sure the obvious tool
 * for an obvious request is <em>reachable</em>. Ranking still blends this with need
 * ({@link ToolAffordanceRanker}), so the agent keeps its own pull.</p>
 */
public final class RequestRelevance {

    private RequestRelevance() {}

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /** Words too common to say anything about which tool is wanted. */
    private static final Set<String> STOP = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "be", "do", "does", "did", "can",
        "could", "would", "will", "what", "whats", "how", "why", "who", "when", "where",
        "i", "you", "me", "my", "your", "we", "us", "it", "this", "that", "there",
        "to", "of", "in", "on", "at", "for", "with", "and", "or", "but", "if", "so",
        "please", "just", "now", "then", "tell", "give", "get", "make", "let", "use",
        "from", "about", "into", "out", "up", "down", "over", "some", "any", "not");

    /**
     * Intent cues → tool names that plainly serve them. A SEED, not the mechanism.
     *
     * <p>It exists because pure lexical overlap genuinely cannot see "17 times 3" →
     * {@code calculator}: the request shares no word with that tool's name or description.
     * When the user's vocabulary and the tool's vocabulary differ, overlap scores zero and
     * the agent cannot reach the tool at all.</p>
     *
     * <p><b>But a hardcoded name list is the exact bug this codebase keeps shipping</b> —
     * {@code REACT_PRODUCTIVE_TOOLS} named no scripted item, {@code EXPLORATORY_TOOL_NAMES}
     * is frozen, and the single free-form {@code query} slot assumed every tool wanted the
     * same thing. A static map cannot know about the 55 disk items, let alone the ones an
     * agent forges at runtime. So this map is a floor for the shipped built-ins ONLY.
     * Everything else — including anything an agent makes for itself — is covered by
     * {@link #score}'s description-overlap branch, which asks the tool what it is for
     * instead of consulting a list. If this map is ever the only thing keeping a tool
     * reachable, that tool's description is too vague and THAT is the thing to fix.</p>
     */
    /**
     * What the household's own volumes can answer about.
     *
     * <p>Two kinds of noun, and the second was missing. The <b>containers</b> —
     * library, shelf, volume — are what a person says when they are thinking
     * about where a thing is kept. The <b>contents</b> — poem, passage, letter,
     * lyrics — are what they say when they are thinking about the thing itself,
     * which is most of the time. "What is the poem that so-and-so sent?" names
     * no furniture at all, and scored zero.</p>
     *
     * <p>Still nouns only: "read", "find" and "look" stay out, because they
     * apply to every tool and cueing on them is what once sent "find the sum of
     * these numbers" to web search.</p>
     */
    private static final List<String> LIBRARY_CUES = List.of(
        // containers
        "library", "book", "books", "shelf", "shelves", "bibliography",
        "novel", "novels", "volume", "volumes", "chapter", "reading",
        "manuscript", "anthology",
        // contents
        "poem", "poems", "verse", "verses", "stanza", "passage", "passages",
        "excerpt", "excerpts", "quotation", "quotations", "epigraph",
        "letter", "letters", "essay", "essays", "lyrics", "prose",
        "author", "novelist", "poet");

    private static final Map<String, List<String>> INTENT = Map.ofEntries(
        Map.entry("calculator", List.of(
            "calculate", "calculation", "compute", "arithmetic", "math", "maths",
            "times", "multiply", "multiplied", "divide", "divided", "plus", "minus",
            "subtract", "sum", "average", "median", "stddev", "deviation",
            "percent", "percentage", "squared", "cubed", "sqrt", "root",
            "power", "exponent", "factorial", "logarithm", "modulo", "remainder",
            "product", "quotient")),
        Map.entry("morning_briefing", List.of(
            "weather", "forecast", "temperature", "rain", "snow", "sunny", "cloudy",
            "briefing", "outlook")),
        // A thing that DOES something. Nouns only, and deliberately not the verbs
        // "build"/"make"/"create" — those are equally true of a room and of a lantern, and
        // the note above records what cueing on universal verbs already cost once.
        //
        // Live on staging 2026-08-22: asked twice, in plain words, to "build me an item
        // called venture_scout", she answered about wanting the request heard and never
        // dispatched. It read as evasion. It was not: dispatch_task appeared on ZERO of
        // three menus, because nothing here named it and description overlap alone caps at
        // 0.5. The tool that builds tools has to be reachable by asking for a tool.
        Map.entry("dispatch_task", List.of(
            "tool", "tools", "item", "items", "script", "scripts", "code",
            "program", "utility", "gadget", "widget", "automate", "automation")),
        // And the room verbs, so "make me a room where…" reaches the room-maker rather
        // than competing on prose overlap with everything else that says "room".
        Map.entry("create_room_from_template", List.of(
            "room", "rooms", "chamber", "hall", "space", "place", "sanctum")),
        Map.entry("trip_planner", List.of(
            "trip", "travel", "route", "directions", "itinerary", "journey")),
        // NOT "search"/"look"/"find": those are verbs that apply to EVERY tool. Cueing on them
        // sent "find the sum of these numbers" and "look at the calculator" straight to web
        // search at full confidence. A cue has to be about the tool, not about wanting a tool.
        Map.entry("searching_glass", List.of(
            "web", "google", "news", "online", "internet", "browse")),
        // Both library paths carry the same cues, and they must.
        //
        // Only library_card had them, and library_card is a CRAFTABLE starter
        // item — a companion who never crafted one has it nowhere in reach.
        // library_search is the native action that always exists and is the one
        // wired to the household's own volumes, and it scored on name overlap
        // alone: "library" out of {library, search} = 0.5, a dead tie with any
        // furnishing that happens to have "library" in its name. So "can u look
        // through my books and tell me…" ranked the workshop's library_shelves
        // furnishing level with the tool built to answer it (2026-08-07).
        //
        // Nouns only. "search"/"look"/"find" are excluded on purpose — they are
        // verbs that apply to every tool, and cueing on them is what once sent
        // "find the sum of these numbers" straight to web search.
        // CONTAINERS *AND* CONTENTS.
        //
        // The list held only words for the furniture — library, shelf, volume —
        // so it fired on "look through my books" and scored ZERO on "what is the
        // poem that Finkle-McGraw sent to Hackworth? please recite it". Live
        // 2026-08-09, four runs: library_card appeared on NONE of the eight
        // ranked surfaces for that question. She was offered go_to_room,
        // promote_familiar and bear_the_wound, and went out the use_item escape
        // hatch to the WEB — the only source she had left — where
        // "Finkle-McGraw" matched "Norman Finkelstein (poet)". On the fourth run
        // she composed a poem of her own and recited it.
        //
        // A person asking about a poem, a passage or a letter is asking about
        // something written down. Naming the artefact is naming the library.
        Map.entry("library_search", LIBRARY_CUES),
        Map.entry("library_card", LIBRARY_CUES),
        Map.entry("web_clipper", List.of("clip", "bookmark", "url")),
        Map.entry("quote_card", List.of("quote", "quotation", "citation")),
        Map.entry("research_assistant", List.of("research", "investigate")),
        Map.entry("nostr_quill", List.of("nostr", "publish")),
        Map.entry("notify_team", List.of("notify", "slack", "announce")),
        Map.entry("expense_summary", List.of("spend", "spending", "expense", "expenses", "budget")),
        Map.entry("audit_log", List.of("audit", "auditing")),
        Map.entry("agenda_board", List.of("agenda", "docket", "proposal", "tally")));

    /**
     * Relevance of {@code toolName} to {@code request}, in 0..1.
     *
     * <p>Every signal is PROPORTIONAL, and we take the strongest. An earlier cut returned a flat
     * 1.0 on any single hit, which made two tools indistinguishable the moment they shared one
     * word: "craft a new item from <b>template</b>" scored {@code create_room_from_template} as
     * highly as {@code craft_from_template}. A confident wrong tool is worse than a hesitant right
     * one — the whole point here is that the agent gets a menu that means something.</p>
     *
     * @param description the tool's own description (may be null) — its words count too
     */
    public static double score(String request, String toolName, String description) {
        if (request == null || request.isBlank() || toolName == null) return 0.0;
        var words = words(request);
        if (words.isEmpty()) return 0.0;

        double best = 0.0;

        var lower = toolName.toLowerCase(Locale.ROOT);

        // 1. An intent cue: the request says "times"/"multiply", the tool is the calculator.
        //    This is the case overlap cannot see — the two vocabularies simply differ.
        var cues = INTENT.get(lower);
        if (cues != null) {
            for (var cue : cues) {
                if (words.contains(cue) && !usedAsVerb(request, cue)) { best = 1.0; break; }
            }
        }

        // 1b. A written-out expression ("48273 * 9182") names no operation at all — it IS the
        //     operation. The symbols are the cue.
        if (best < 1.0 && lower.equals("calculator") && looksArithmetic(request)) best = 1.0;

        // 2. The tool's own name, spoken in the request ("use your calculator"). Scored as the
        //    FRACTION of the name that was said, so a tool named for the whole request beats one
        //    that merely shares a word with it.
        var nameParts = new ArrayList<>(words(toolName.replace('_', ' ')));
        nameParts.removeIf(p -> STOP.contains(p) || p.length() <= 3);
        if (!nameParts.isEmpty()) {
            long hit = nameParts.stream().filter(words::contains).count();
            if (hit > 0) best = Math.max(best, (double) hit / nameParts.size());
        }

        // 3. Overlap with the tool's description — a nudge, never a verdict. Capped below a
        //    name/cue match so prose can't out-shout the tool's actual identity.
        if (description != null && !description.isBlank()) {
            var desc = words(description);
            desc.removeAll(STOP);
            if (!desc.isEmpty()) {
                long hits = desc.stream().filter(words::contains).count();
                if (hits > 0) best = Math.max(best, Math.min(0.5, 0.15 * hits));
            }
        }
        return best;
    }

    /**
     * Is this cue word being used as a VERB here rather than naming a thing?
     *
     * <p>The cue lists are meant to be nouns — "a cue has to be about the tool,
     * not about wanting a tool". Several of them are also common verbs, and the
     * comment claiming otherwise went untested until someone asked whether the
     * library work had been overfitted to one sentence. It had:</p>
     *
     * <pre>
     * "book me a flight to osaka"      → library, at full confidence
     * "shelf that idea for now"        → library
     * "i'm reading you loud and clear" → library
     * </pre>
     *
     * <p>English marks this position reliably enough for a lexical heuristic: a
     * noun is not normally followed straight by an object pronoun or a bare
     * determiner. "book me", "book a", "shelf that" are verbs; "my books",
     * "the book about grief", "on the shelf" are not. Deliberately narrow — it
     * only ever <em>withholds</em> a cue, so the worst case is falling back to
     * name and description overlap, which is where every uncued tool already
     * lives.</p>
     */
    private static final Set<String> VERB_OBJECT_MARKERS = Set.of(
        "me", "us", "him", "her", "them", "myself", "yourself",
        "a", "an", "that", "this", "these", "those", "it");

    /** An article or possessive immediately before the cue: "a tool", "my calculator". */
    private static final Set<String> NOUN_MARKERS = Set.of(
        "a", "an", "the", "my", "your", "our", "their", "his", "her", "its",
        "one", "some", "another", "this", "that");

    static boolean usedAsVerb(String request, String cue) {
        // A determiner in front settles it: "make me a tool that looks up a topic" is a
        // NOUN, whatever follows. Without this, "a tool that…" was read as a verb because
        // "that" is a verb-object marker, and the tool-maker scored 0.15 on the plainest
        // request there is — measured on the steward's own words, 2026-08-22.
        var noun = Pattern.compile("\\b([a-z']+)\\s+" + Pattern.quote(cue) + "\\b",
            Pattern.CASE_INSENSITIVE).matcher(request);
        while (noun.find()) {
            if (NOUN_MARKERS.contains(noun.group(1).toLowerCase(Locale.ROOT))) return false;
        }
        var m = Pattern.compile("\\b" + Pattern.quote(cue) + "\\b\\s+([a-z']+)",
            Pattern.CASE_INSENSITIVE).matcher(request);
        while (m.find()) {
            if (!VERB_OBJECT_MARKERS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return false;   // at least one occurrence reads as a noun
            }
        }
        // Every occurrence was followed by a verb-object marker, and there was at
        // least one. A trailing cue ("what's on the shelf") matches nothing here
        // and correctly counts as a noun.
        return m.reset().find();
    }

    /** Bare arithmetic — "17 * 3", "48273 x 9182" — carries no cue word at all. */
    private static final Pattern BARE_ARITHMETIC =
        Pattern.compile("\\d+\\s*[-+*/x×÷^%]\\s*\\d+");

    /** True when the request is a naked expression the agent should reach for a tool over. */
    public static boolean looksArithmetic(String request) {
        return request != null && BARE_ARITHMETIC.matcher(request.toLowerCase(Locale.ROOT)).find();
    }

    private static HashSet<String> words(String s) {
        var out = new HashSet<String>();
        var m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }
}
