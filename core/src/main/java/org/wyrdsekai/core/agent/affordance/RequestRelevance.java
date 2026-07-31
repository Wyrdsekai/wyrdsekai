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
        Map.entry("trip_planner", List.of(
            "trip", "travel", "route", "directions", "itinerary", "journey")),
        // NOT "search"/"look"/"find": those are verbs that apply to EVERY tool. Cueing on them
        // sent "find the sum of these numbers" and "look at the calculator" straight to web
        // search at full confidence. A cue has to be about the tool, not about wanting a tool.
        Map.entry("searching_glass", List.of(
            "web", "google", "news", "online", "internet", "browse")),
        Map.entry("library_card", List.of(
            "library", "book", "books", "shelf", "shelves", "bibliography")),
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
                if (words.contains(cue)) { best = 1.0; break; }
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
