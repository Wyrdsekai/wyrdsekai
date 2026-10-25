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

    /** In order: the first rule whose words appear and whose tool she was offered wins. */
    private static final List<Rule> RULES = List.of(
        // Looking something up comes before reading a book: "read up on", "read the library on", "read about" are
        // research. Mia said "I'll read the library on what matters most" four times one night, and each went to the
        // book reader as a title that is no book, counted as a reach that missed (second-node, 2026-10-04).
        new Rule(Pattern.compile("\\b(?:look into|looking into|read up on|read (?:up )?about|read (?:more )?on|read (?:the|my) library|"
                + "read what (?:the library|it says|they say)|research|find out (?:about|more)|learn (?:about|more)|"
                + "dig into|search (?:for|the library)|look (?:it|that|this) up)\\b"),
            List.of("library_card", "searching_glass", "research_assistant", "research_tool", "library_search")),
        // Her own notebook comes before a book: "I'll read the project note now" went to the library
        // four times in one night and found no such book (household node, 2026-10-24). After the
        // research rule, which stays first: a book with no title falls back to it by index.
        new Rule(Pattern.compile("\\b(?:read|open|check|look at|look in|review|see)\\b[^.]*\\b(?:project[ _]?notes?|notebook|project notebook)\\b"),
            List.of("project_note")),
        new Rule(Pattern.compile("\\b(?:read|keep reading|finish reading|next chapter|the book)\\b"),
            List.of(READ_BOOK)),
        // Mending an unfinished thing is the workshop's: dispatch_task, not the furniture called workbench.
        new Rule(Pattern.compile("\\b(?:fix|fixing|mend|mending|repair|untangle|unfinished|broken|"
                + "get (?:it|one|them|this|that|the [\\w-]+) working|working again|"
                + "what needs (?:fixing|building|attention|mending)|finish (?:the|my|that|it|building))\\b"),
            List.of("dispatch_task")),
        new Rule(Pattern.compile("\\bclose\\b"),
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
            for (var r : RULES) {
                if (!r.words().matcher(clause).find()) continue;
                var tools = r.tools();
                if (tools.contains(READ_BOOK)) {
                    if (HER_OWN_MAKING.matcher(clause).find()) break;   // her own thing, not a shelf's
                    if (!aBookIsOpen && !NAMES_A_BOOK.matcher(clause).find()) tools = RULES.get(0).tools();
                }
                for (var tool : tools) {
                    if (offeredTools.contains(tool)) return new Act(tool, sentenceAround(spoken, m.start("rest")));
                }
                break;   // the words name an act she has no tool for: not a guess at another
            }
        }
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
