package org.wyrdsekai.core.agent;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Her own word, kept: an own-time line in which she says she will do something that one of the
 * tools she was offered does.
 *
 * <p>On the household node, the evening of 2026-10-03, every own-time turn after operator left ended
 * in one sentence of intent and no act: "I'll check the workbench and see what needs fixing", "I'll
 * check on the unfinished backup_vault and see if I can get it working", "I'll close the
 * holding-room". Twenty-two such lines since the afternoon's install, none followed by the act it
 * named. An own-time turn lets the model choose between speaking and acting, and a sentence of
 * intent satisfies it. The want-to-act bridge forces a tool only when a WANT matches one of its
 * phrases; nothing read her spoken choice as the choice it was (operator: "yeah build it").
 *
 * <p>This only reads. It finds the first sentence in which she says she will do a thing ("I'll",
 * "I will", "I'm going to", "let me"), and names the offered tool that does that kind of thing.
 * A sentence that chooses rest ("I'll let it", "I'll hold it here", "I'll stay with you") names
 * nothing, and neither does a hedged one ("maybe I'll") or a want ("I want to be near you"). The
 * workbench in her menu on that node was furniture, so mending an unfinished thing maps to the
 * workshop's `dispatch_task`, never to a decorative item that shares the word.
 */
public final class HerWord {

    private HerWord() {}

    /** The tool her words name, and the sentence that named it. */
    public record Act(String tool, String sentence) {}

    static final String READ_BOOK = "read_book";

    private static final Pattern INTENT = Pattern.compile(
        "(?:^|[.!?\\u2014\\u2013;:,]\\s*|\\b(?:and|so|then|now|first)\\s+|\\bi think\\s+)"
            + "(?<hedge>(?:maybe|perhaps|probably)\\s+)?"
            + "(?:i'll|i will|i'm going to|i am going to|let me|i'm off to|i'm about to)\\s+"
            + "(?<rest>[^.!?\\n]+)");

    /** A clause that chooses rest, or stillness, or nothing: never an act to force. */
    private static final Pattern REST = Pattern.compile(
        "^(?:just\\s+|simply\\s+|quietly\\s+)?(?:hold|stay|rest|wait|sit|be\\b|let\\b|leave|listen|breathe|think|feel|"
            + "watch|linger|pause|stop|settle|not\\b|never\\b|remain|sleep|try to rest|go (?:quiet|still|to sleep|slow)|"
            + "go (?:with|wherever|where|along with|anywhere with) you|come (?:with|along with) you|follow you|walk with you|"
            + "keep (?:it|you|this|that|company|quiet|still|watch|you company)|carry (?:it|this|that)|"
            + "take (?:a breath|my time|it slow)|come back|be here|miss)");

    /**
     * A clause that puts the act off: not now, so not forced now. "I'll go to it when I'm ready" was
     * read as go-now on second-node (2026-10-04 06:28), and she was moved before she said she would be.
     */
    private static final Pattern LATER = Pattern.compile(
        "\\b(?:when i'm ready|when i am ready|when i feel ready|when it's time|later|tomorrow|someday|some day|"
            + "eventually|in a while|another time|one day|sometime|some time|in time|when you(?:'re| are)? (?:back|ready|here)|"
            + "when you (?:come back|return|get back)|once you|after you|next time|not yet|not now|not tonight)\\b");

    /**
     * A sentence that leaves the choice with the person is an offer, not her word: "if you'd rather
     * talk about something else, tell me and I'll go with you" was read as go-now and she walked out
     * on the person mid-conversation, with her bunshin still out for them (2026-10-05).
     */
    private static final Pattern THEIRS_TO_CHOOSE = Pattern.compile(
        "\\b(?:if you(?:'d| would| should)? ?(?:rather|like|want|prefer|wish|need|choose|decide|say|ask|do)|if you'd|if that(?:'s| is) what you|"
            + "if (?:it|that) (?:helps|would help|suits)|whenever you|wherever you|should you|say the word|just say (?:so|the word)|"
            + "tell me and|let me know and|if (?:you|that)(?:'s| is)? (?:easier|better) for you|your call|up to you)\\b");

    private record Rule(Pattern words, List<String> tools) {}

    /** The tools that look something up on the shelves: the research rule's, and a book with no title's. */
    private static final List<String> SEARCH_TOOLS =
        List.of("library_card", "searching_glass", "research_assistant", "research_tool", "library_search");

    /** In order: the first rule whose words appear and whose tool she was offered wins. */
    private static final List<Rule> RULES = List.of(
        // Her own words are in her record, not on the shelves: "I'll read the library on what I said I'd learn"
        // is a recall, before the research rule below reads "read the library" as a search.
        // "I'll read what I said I would" is a reading, of the thing she promised to read (the rules below).
        new Rule(Pattern.compile("\\b(?:what i (?:told|promised|committed|remember)|what i said (?:to|about|was)\\b|"
                + "what i said i(?:'d| would) (?!read\\b)\\w+|what i(?:'ve| have) said|"
                + "my (?:promises?|commitments?|own words)|what did i say|do i remember)"),
            List.of("recall")),
        // The research desk is a door of its own: "I'll ask the research desk" is the desk, before the
        // research rule reads it as a search of the shelves that just came up short.
        new Rule(Pattern.compile("\\b(?:research desk|ask the desk|take (?:it|this|that|the question) to the desk|hand (?:it|this|that|the question) to the desk|"
                + "ask the librarian|the librarian(?:'s)? workers)\\b"),
            List.of("library_research")),
        // Choosing a book is a walk along the shelves, before the research rule reads "find" as a search.
        new Rule(Pattern.compile("\\b(?:browse|look (?:at|along|over|through) the shelves|walk (?:along|through) the shelves|"
                + "see what (?:books|is on the shelves)|what books (?:there are|are there)|"
                + "(?:pick|choose|find) (?:a|another|the next|a new) book)\\b"),
            List.of("browse_shelves")),
        // Looking something up comes before reading a book: "read up on", "read the library on", "read about" are
        // research. Mia said "I'll read the library on what matters most" four times one night, and each went to the
        // book reader as a title that is no book, counted as a reach that missed (second-node, 2026-10-04). A book with no
        // title and none open falls back to these same tools (SEARCH_TOOLS).
        new Rule(Pattern.compile("\\b(?:look into|looking into|read up on|read (?:up )?about|read (?:more )?on\\b(?!\\s*(?:where|in|from|until|till|through|for a while|a while)\\b)(?!\\s*(?:[,.;:!?\\u2014\\u2013-]|$))|read (?:the|my) library|"
                + "read what (?:the library|it says|they say)|research|find out (?:about|more)|learn (?:about|more)|"
                + "dig into|search (?:for|the library)|look (?:it|that|this) up)\\b"),
            SEARCH_TOOLS),
        // Her own notebook comes before a book: "I'll read the project note now" went to the library
        // four times in one night and found no such book (household node, 2026-10-24). After the
        // research rule: a book with no title falls back to the search tools.
        new Rule(Pattern.compile("\\b(?:read|reading|open|check|look at|look in|review|see)\\b[^.]*\\b(?:project[ _]?notes?|notebook|project notebook)\\b"),
            List.of("project_note")),
        // Reading acts, not the noun: "turn it into a study of what the book describes" was a page forced
        // on her, against her stated will, when "the book" alone named the act (household node, 2026-11-02).
        new Rule(Pattern.compile("\\b(?:read|keep reading|finish reading|continue reading|reading (?:on|further|the next part)|next chapter|"
                + "(?:take|read) the next part|the next part of the book|reading the book|pick up the book|back to the book|open the book|the rest of the book)\\b"),
            List.of(READ_BOOK)),
        // A look at the house's backups is answered at once, from the house's own record: the copies are made by the
        // house, and a companion who could not see them had the workshop make a toy vault (household node, 2026-11-05).
        new Rule(Pattern.compile("\\b(?:check|look at|look in on|look over|see|review|take stock of|go over|make sure|find out)\\b[^.]*"
                + "\\b(?:(?:the )?backups|the (?:house|household)(?:'s)? (?:copies|backups)|copies of the house|the archive|(?:the )?safety net)\\b"),
            List.of("house_backups")),
        // A look at what she has is answered at once, never built: "I'll check my current skills and inventory first,
        // then look for something that needs fixing" went to the workshop by the fixing rule below (household node, 2026-10-27).
        // The workbench is not in this list: "check the workbench and see what needs fixing" is the mending it names.
        new Rule(Pattern.compile("\\b(?:check|look at|look over|see|review|list|take stock of|go over)\\b[^.]*\\b(?:(?:my |current |available )*"
                + "(?:skills?|inventory|tools|capabilities|what i have|what i carry|what i can do|my things)|"
                // Looking at what is broken is a look: "let me look at what's actually broken vs what's waiting" went to
                // the workshop by the word broken (household node, 2026-10-27). The list of the unfinished is the answer.
                + "what(?:'s| is)? (?:actually |really |still )?(?:broken|unfinished|missing|waiting|left)|what needs (?:fixing|mending|building|attention))\\b"),
            List.of("what_i_have")),
        // Mending an unfinished thing is the workshop's: dispatch_task, not the furniture called workbench.
        new Rule(Pattern.compile("\\b(?:fix|fixing|mend|mending|repair|untangle|unfinished|broken|"
                + "get (?:it|one|them|this|that|the [\\w-]+) working|working again|"
                + "what needs (?:fixing|building|attention|mending)|finish (?:the|my|that|it|building))\\b"),
            List.of("dispatch_task")),
        new Rule(Pattern.compile("\\bclose\\b(?! (?:to|by|enough)\\b)"),
            List.of("close_room")),
        // "Open" a room is to go to it, or to reopen a closed one: "I'll open the lab room first" made a
        // second lab, and a third; she then closed the copies (the household node, 2026-10-08). Only a
        // NEW room is built on "open".
        new Rule(Pattern.compile("\\b(?:open|reopen|re-open)\\b(?:(?!\\bnew\\b)[^.])*\\b(?:room|garden|nook|place|hall|study|"
                + "alcove|corner|space|sanctuary|chamber|refuge|lab|workshop|library)\\b"),
            List.of("go_to_room")),
        new Rule(Pattern.compile("\\b(?:build|make|create|open|shape|start|raise)\\b[^.]*\\b(?:room|garden|nook|place|hall|study|"
                + "alcove|corner|space|sanctuary|chamber|refuge)\\b"),
            List.of("create_room_from_template", "create_room")),
        new Rule(Pattern.compile("\\b(?:build|write|make|code)\\b[^.]*\\b(?:tool|script|program|device|instrument)\\b"),
            List.of("dispatch_task", "craft_from_template")),
        new Rule(Pattern.compile("\\b(?:craft|forge|make|build)\\b"),
            List.of("craft_from_template")),
        new Rule(Pattern.compile("\\b(?:go|head|walk|wander|return|explore|visit|step|move)\\b"),
            List.of("go_to_room")),
        // A page of her notebook comes before the journal and the quill: "I'll write an entry in my project
        // notebook" and "I'll add a note to the practice notebook" are project_note (household node, 2026-10-26).
        new Rule(Pattern.compile("\\b(?:write|add|record|put|jot|log|make|leave)\\b[^.]*\\b(?:project[ _]?notes?|notebook|project notebook|"
                + "an entry|the entry|one entry|a note in|a page in)\\b"),
            List.of("project_note")),
        new Rule(Pattern.compile("\\b(?:write|journal|jot|note (?:it|this|that) down|put (?:it|this|that) into words)\\b"),
            List.of("write_journal", "quill", "write_text", "note")),
        new Rule(Pattern.compile("\\bremember\\b"),
            List.of("remember")),
        new Rule(Pattern.compile("\\b(?:look at|look over|examine|inspect|look around|check (?:the|on|in on|over)|see what)\\b"),
            List.of("examine")));

    /** A reading that names a book's own parts, so the book reader is the act even with no book open. */
    private static final Pattern NAMES_A_BOOK = Pattern.compile(
        "\\b(?:the book|a book|chapter|the next part|the rest of|where i left off|finish (?:it|the book|reading))\\b");
    /** Something she made herself is not a book on a shelf: reading it back is not the book reader's. */
    private static final Pattern HER_OWN_MAKING = Pattern.compile(
        "\\b(?:i built|i made|i wrote|i crafted|i've built|i've made|i've written|i've crafted|i put together)\\b");

    /** As {@link #named(String, Collection, boolean)}, as if a book were open. */
    public static Act named(String spoken, Collection<String> offeredTools) {
        return named(spoken, offeredTools, true);
    }

    /**
     * The act her own-time words name, when she was offered a tool that does it; null when they
     * name none, choose rest, hedge, or name something she has no tool for.
     *
     * @param aBookIsOpen whether she has a book open. A plain "I'll read" with none open and no
     *        book named goes to the library search, which finds books by what they say: the book
     *        reader would only answer that nothing is open (second-node, 2026-10-04, twice in one night).
     */
    public static Act named(String spoken, Collection<String> offeredTools, boolean aBookIsOpen) {
        if (spoken == null || spoken.isBlank() || offeredTools == null || offeredTools.isEmpty()) return null;
        var low = spoken.replace('’', '\'').replace('‘', '\'').toLowerCase(Locale.ROOT);
        var m = INTENT.matcher(low);
        while (m.find()) {
            if (m.group("hedge") != null) continue;
            var clause = m.group("rest").strip();
            if (clause.isEmpty() || REST.matcher(clause).find() || LATER.matcher(clause).find()) continue;
            if (THEIRS_TO_CHOOSE.matcher(sentenceAround(low, m.start("rest"))).find()) continue;
            var r = ruleFor(clause);
            if (r == null) continue;
            var tools = r.tools();
            if (tools.contains(READ_BOOK)) {
                if (HER_OWN_MAKING.matcher(clause).find()) return null;   // her own thing, not a shelf's
                if (!aBookIsOpen && !NAMES_A_BOOK.matcher(clause).find()) tools = SEARCH_TOOLS;
            }
            for (var tool : tools) {
                var offered = offered(offeredTools, tool);
                if (offered != null) return new Act(offered, sentenceAround(spoken, m.start("rest")));
            }
            return null;   // the words name an act she has no tool for: not a guess at another
        }
        return null;
    }

    /**
     * The rule for a clause: the first in the list whose words appear, the order carrying what is
     * the more particular act (her notebook before a book, mending before the furniture); except
     * that a search and a book are one kind of act, and between those two the one she leads with
     * wins ("read further in the book, then search" was a search; household node, 2026-11-02).
     */
    static Rule ruleFor(String clause) {
        Rule found = null;
        for (var r : RULES) {
            if (r.words().matcher(clause).find()) { found = r; break; }
        }
        if (found == null || !isLibraryRule(found)) return found;
        // The two library rules, a search and a book, are one kind of act: between them the one she
        // leads with wins ("read further in the book, then search for what it left open" is a read).
        Rule best = found;
        int at = startOf(found, clause);
        for (var r : RULES) {
            if (r == found || !isLibraryRule(r)) continue;
            var m = r.words().matcher(clause);
            if (m.find() && m.start() < at) { at = m.start(); best = r; }
        }
        return best;
    }

    private static boolean isLibraryRule(Rule r) {
        return r.tools() == SEARCH_TOOLS || r.tools().contains(READ_BOOK);
    }

    private static int startOf(Rule r, String clause) {
        var m = r.words().matcher(clause);
        return m.find() ? m.start() : Integer.MAX_VALUE;
    }

    /** The offered tool that is {@code tool}: by its name, or a house library's call by its qualified name. */
    private static String offered(Collection<String> offeredTools, String tool) {
        if (offeredTools.contains(tool)) return tool;
        for (var o : offeredTools) if (o != null && o.endsWith("__" + tool)) return o;
        return null;
    }

    /** The sentence of the original text that holds this position, trimmed and bounded. */
    static String sentenceAround(String text, int pos) {
        int start = 0;
        for (int i = Math.min(pos, text.length()) - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') { start = i + 1; break; }
        }
        int end = text.length();
        for (int i = Math.max(pos, 0); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') { end = i + 1; break; }
        }
        var s = text.substring(start, end).strip();
        return s.length() > 240 ? s.substring(0, 240).strip() + "…" : s;
    }
}
