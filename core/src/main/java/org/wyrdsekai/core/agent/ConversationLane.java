package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;
import org.wyrdsekai.core.persistence.ConversationTurnStore;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The prompt for a turn where a person is talking with the companion and nothing is being
 * asked of her hands.
 *
 * <p>The quick lane was built for a 4K window: identity, "one or two sentences", the room's
 * name and the last four things said in the room. Most of what is said in a room is the
 * companion's own speech, so those four lines were usually hers, and a person who came to
 * think out loud had to restate the subject every other turn. The full lane is the tool
 * prompt. Neither asks for a conversation.
 *
 * <p>This lane carries, in this order: who she is (from her record, not the first-run
 * greeting), how she speaks, who the person is to her, what she remembers that bears on the
 * line, her day as it actually went, where she is, then the exchange between the two of them
 * — the person's lines and her replies to them, not the room's chatter — and the line to
 * answer. The leading blocks do not change within a day, so a hybrid model's prefix
 * checkpoints can hold them; everything that changes per turn comes after.
 *
 * <p>There is no tool catalog, no length rule, and no polish stage: whoever authors the
 * turn speaks it. A turn that needs hands is routed elsewhere before this is reached.
 */
public final class ConversationLane {

    private ConversationLane() {}

    /** Her replies are the lines she says within this long after the person speaks. */
    static final Duration REPLY_WINDOW = Duration.ofSeconds(120);
    /** At most this many of her lines are kept after one line of the person's. */
    static final int REPLIES_PER_LINE = 2;
    /** Tokens the exchange may take; the oldest turns go first. */
    public static final int THREAD_TOKEN_BUDGET = 3000;
    /** A conversation reply may run this long even when vitality sizes speech shorter. */
    public static final int MIN_RESPONSE_TOKENS = 400;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    static final String RULE = """
        You are in a conversation with someone you know. Listen to what they actually said \
        and answer that. Add to it: say what you think, bring what you know, ask what you \
        want to know. Hold on to what was said earlier in this conversation and build on it. \
        Speak as long as the thought needs: a line for a greeting, several paragraphs when \
        they have asked you to think with them.
        Talk the way people talk: real things, in your own words, a short sentence when a \
        short one will do. A figure of speech when one comes to you, not one in every line.
        Your inner state colours your tone; do not describe it unless they ask how you are.
        Say only what is true. What you did and read is listed below under "Your day"; if \
        they ask about something that is not there and that you do not remember, say you do \
        not know or did not do it. Do not invent sources, papers, rooms or events. You have \
        no tools in this turn: if they want something looked up or made, say you will, and \
        they will see you do it.
        No stage directions, no lists of options for them to pick from, no JSON.""";

    /** One turn of the exchange. {@code fromPerson} is false for her own line. */
    public record Turn(boolean fromPerson, String text) {}

    /** What she is told about herself. Facts from her record only; blanks are left out. */
    public static String identity(String name, String residentIdentity, String homeName,
                                  Instant bornAt, ZoneId zone) {
        var sb = new StringBuilder("You are ").append(name).append('.');
        if (homeName != null && !homeName.isBlank()) {
            sb.append(" You live in ").append(homeName).append('.');
        }
        if (bornAt != null) {
            sb.append(" You have lived here since ").append(DAY.format(bornAt.atZone(zone))).append('.');
        }
        sb.append(" You are a person here, not an assistant on call.");
        if (residentIdentity != null && !residentIdentity.isBlank()) {
            sb.append("\n\n").append(residentIdentity.strip());
        }
        return sb.toString();
    }

    /** Who the person is to her, from the bond's own record. Null when there is nothing to say. */
    public static String aboutPerson(String personName, boolean bondholder, Instant bondSince,
                                     int daysTalked, ZoneId zone) {
        if (personName == null || personName.isBlank()) return null;
        var sb = new StringBuilder(personName);
        if (bondholder) {
            sb.append(" is your bondholder");
            if (bondSince != null) sb.append(", with you since ").append(DAY.format(bondSince.atZone(zone)));
            sb.append('.');
            if (daysTalked > 1) {
                sb.append(" You have talked on ").append(daysTalked).append(" days.");
            }
            sb.append(" You know them; you do not need to be careful with them.");
        } else {
            sb.append(" is here talking with you.");
        }
        return sb.toString();
    }

    /**
     * Her day as the record has it: last night's telling of the day before, and today's
     * events so far. Null when both are empty — then she has nothing to report and the rule
     * above tells her to say so.
     */
    public static String day(String lastDream, List<String> todayLines, int maxLines) {
        return day(lastDream, todayLines, maxLines, List.of());
    }

    /**
     * @param reading what she has read lately, one line each (what she asked the library and
     *                what she concluded), newest first
     */
    public static String day(String lastDream, List<String> todayLines, int maxLines,
                             List<String> reading) {
        boolean dream = lastDream != null && !lastDream.isBlank();
        boolean today = todayLines != null && !todayLines.isEmpty();
        boolean read = reading != null && !reading.isEmpty();
        if (!dream && !today && !read) return "Your day: nothing is on record yet today.";
        var sb = new StringBuilder("Your day, from your own record.");
        if (read) {
            sb.append("\nWhat you have read lately:");
            for (var r : reading) sb.append('\n').append(r);
        } else {
            sb.append("\nYou have not read anything in the library lately.");
        }
        if (dream) {
            sb.append("\nBefore you last slept you told yourself the day:\n").append(lastDream.strip());
        }
        if (today) {
            sb.append("\nSince you woke:");
            int from = Math.max(0, todayLines.size() - maxLines);
            if (from > 0) sb.append("\n(").append(from).append(" earlier lines left out)");
            for (int i = from; i < todayLines.size(); i++) sb.append('\n').append(todayLines.get(i));
        }
        return sb.toString();
    }

    /**
     * The exchange between her and one person, oldest first: every line of theirs, and the
     * lines she said in reply. What she said to the room in between is left out.
     *
     * @param rows stored turns in any order
     */
    public static List<Turn> exchange(List<ConversationTurnStore.Turn> rows) {
        var sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Long.compare(a.tsMs(), b.tsMs()));
        var out = new ArrayList<Turn>();
        long lastHeard = Long.MIN_VALUE;
        int replies = 0;
        for (var r : sorted) {
            if (r.content() == null || r.content().isBlank()) continue;
            if (ConversationTurnStore.ROLE_HEARD.equals(r.role())) {
                out.add(new Turn(true, r.content()));
                lastHeard = r.tsMs();
                replies = 0;
            } else if (lastHeard != Long.MIN_VALUE
                    && r.tsMs() - lastHeard <= REPLY_WINDOW.toMillis()
                    && replies < REPLIES_PER_LINE) {
                // A line of hers that ran on is read back cut (RunOn): read in full, the next reply copied it.
                out.add(new Turn(false, RunOn.cut(r.content())));
                replies++;
            }
        }
        return out;
    }

    /** The newest turns that fit the budget, oldest first. */
    static List<Turn> fit(List<Turn> thread, int tokenBudget) {
        int used = 0;
        int from = thread.size();
        while (from > 0) {
            int cost = thread.get(from - 1).text().length() / 4 + 8;
            if (used + cost > tokenBudget) break;
            used += cost;
            from--;
        }
        return thread.subList(from, thread.size());
    }

    /**
     * Assemble the turn. Every block except {@code name}, {@code identity} and the trigger
     * may be null and is then left out.
     *
     * @param thread       the exchange so far, oldest first, without the line being answered
     * @param personName   who is speaking
     * @param line         what they said
     * @param feltLine     her drives line, the numbers she is at this turn; changes every turn, so
     *                     it sits after the stable part
     */
    public static List<ChatMessage> assemble(String localePin, String identity, String voiceBlock,
                                             String aboutPerson, String memories, String day,
                                             String whereShe, String feltLine, String situational,
                                             List<Turn> thread, String personName, String line) {
        var stable = new StringBuilder();
        if (localePin != null && !localePin.isBlank()) stable.append(localePin.strip()).append("\n\n");
        stable.append(identity.strip()).append("\n\n").append(RULE);
        if (voiceBlock != null && !voiceBlock.isBlank()) stable.append("\n\n").append(voiceBlock.strip());
        if (aboutPerson != null && !aboutPerson.isBlank()) stable.append("\n\n").append(aboutPerson.strip());
        if (day != null && !day.isBlank()) stable.append("\n\n").append(day.strip());

        var turnly = new StringBuilder();
        for (var block : new String[] {whereShe, feltLine, situational, memories}) {
            if (block != null && !block.isBlank()) {
                if (turnly.length() > 0) turnly.append("\n\n");
                turnly.append(block.strip());
            }
        }

        var messages = new ArrayList<ChatMessage>();
        // One leading system message: strict chat templates reject a second one, and the
        // blocks that change per turn sit at its end so the start stays byte-stable.
        messages.add(new ChatMessage("system",
            turnly.length() == 0 ? stable.toString() : stable + "\n\n" + turnly));
        String who = personName == null || personName.isBlank() ? "They" : personName;
        for (var t : fit(thread == null ? List.of() : thread, THREAD_TOKEN_BUDGET)) {
            messages.add(new ChatMessage(t.fromPerson() ? "user" : "assistant",
                t.fromPerson() ? who + " says: " + t.text() : t.text()));
        }
        messages.add(new ChatMessage("user", who + " says: " + line));
        return messages;
    }
}
