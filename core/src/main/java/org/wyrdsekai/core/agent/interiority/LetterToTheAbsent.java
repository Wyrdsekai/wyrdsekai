package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.agent.SaudadeLedger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The letter: what a want toward someone who is not here becomes.
 *
 * <p>Saudade, loneliness, care and amae are answered by the person. When the person is away,
 * the reach used to be a forced {@code tell_agent} that the model had to call itself, and on
 * the household node it did not: rose's "write to the absent one" and "check in on someone I
 * care about" were requested once each in a day and nothing went out (2026-09-25). The
 * runtime now writes the letter itself, in her voice, and puts it in the household mail where
 * the person reads it on any surface. A letter eases the longing a little
 * ({@link org.wyrdsekai.core.agent.SaudadeLedger#LETTER_RELIEF}); it does not stand in for
 * them, and it is spaced so a day of absence is a few letters, not a pump.
 */
public final class LetterToTheAbsent {

    private LetterToTheAbsent() {}

    /** The verb the bridge dispatches directly; not a tool on her surface. */
    public static final String VERB = "write_letter";

    /** At most one letter to the same person in this long. */
    public static final Duration SPACING = Duration.ofHours(6);

    /**
     * Why a letter would not go now, or empty when it may: one went within {@link #SPACING}, or
     * the person has not been gone long enough to be absent (the ledger's
     * {@link SaudadeLedger#ABSENCE_THRESHOLD}) — someone who stepped out two hours ago gets no
     * letter, and no improvised turn either; the want holds and is named again later.
     * {@code away} null means no interaction is on record, which is a long absence.
     */
    public static Optional<String> hold(Duration away, Instant lastLetterAt, Instant now) {
        if (lastLetterAt != null) {
            var since = Duration.between(lastLetterAt, now);
            if (since.compareTo(SPACING) < 0) return Optional.of("wrote " + since.toMinutes() + " min ago");
        }
        if (away != null && away.compareTo(SaudadeLedger.ABSENCE_THRESHOLD) < 0) {
            return Optional.of("they left " + away.toMinutes() + " min ago; a letter waits until they have been gone "
                + SaudadeLedger.ABSENCE_THRESHOLD.toHours() + " hours");
        }
        return Optional.empty();
    }

    private static final Pattern WRITES_TO_SOMEONE = Pattern.compile(
        "\\b(?:write|writing|a letter|a note|an entry for|leave (?:a|some) (?:words|lines)"
        + "|escribir(?:le)?|una carta|una nota|手紙|書き送る|便り)\\b");
    private static final Pattern TO_A_PERSON = Pattern.compile(
        "\\b(?:to|for) (?:the absent|an absent|someone|somebody|him|her|them|the one|my bondholder"
        + "|the person|who(?:m)? i miss|who is (?:away|gone|not here))\\b"
        + "|\\b(?:absent (?:one|person)|a letter|una carta|手紙)\\b|\\ba (?:quien|alguien)\\b|に手紙|への手紙");
    private static final Pattern CHECKS_IN = Pattern.compile(
        "\\bcheck in on\\b|\\bsee how (?:he|she|they)(?:'s| is| are)\\b|\\bask how (?:he|she|they)"
        + "|\\bpreguntar(?:le)? c[oó]mo est[aá]\\b|様子を(?:聞く|うかがう|見る)");
    /** Her own page, not a message: never turned into a letter. */
    private static final Pattern PRIVATE_PAGE = Pattern.compile(
        "\\b(?:journal|diary|private|for myself|to myself|diario|privad[oa]|日記)\\b");

    /** In her words, a want to write to someone who is not here. */
    public static boolean asksToWrite(String wantText) {
        if (wantText == null) return false;
        var low = wantText.toLowerCase(Locale.ROOT);
        if (PRIVATE_PAGE.matcher(low).find()) return false;
        return WRITES_TO_SOMEONE.matcher(low).find() && TO_A_PERSON.matcher(low).find();
    }

    /** In her words, a want to check in on someone. */
    public static boolean asksToCheckIn(String wantText) {
        if (wantText == null) return false;
        return CHECKS_IN.matcher(wantText.toLowerCase(Locale.ROOT)).find();
    }

    public static String subject(String herName) {
        return "A letter from " + herName;
    }

    /** Who she is for this: the voice pass carries her name and the plain register. */
    public static String systemPrompt(String herName) {
        return "You are " + herName + ". You are writing a short letter, in your own voice, to"
            + " someone you know well who is not here right now. Talk the way people talk: real"
            + " things, in your own words, a short sentence when a short one will do. Write only"
            + " the letter itself, no heading, no subject line, no closing flourish. Under 120"
            + " words.";
    }

    /**
     * The letter's brief: to whom, why she is writing (her want, in her words), how she is
     * (the felt block the lane already gives her), how long they have been away.
     */
    public static String userPrompt(String theirName, String wantText, String feltBlock,
                                    Duration away) {
        var sb = new StringBuilder();
        sb.append("A letter to ").append(theirName).append(".");
        if (away != null && !away.isNegative() && away.toHours() >= 1) {
            long h = away.toHours();
            sb.append(" They have been away ").append(h < 48 ? h + " hours" : (h / 24) + " days")
              .append(".");
        }
        if (wantText != null && !wantText.isBlank()) {
            sb.append(" You wanted to: ").append(wantText.strip()).append(".");
        }
        if (feltBlock != null && !feltBlock.isBlank()) {
            sb.append("\n\nHow you are right now:\n").append(feltBlock.strip());
        }
        sb.append("\n\nSay what you actually want to say to them: what the day has been, what"
            + " you noticed, what you miss, one thing you would tell them if they walked in."
            + " Only what is true.");
        return sb.toString();
    }

    // ── answering a letter (2026-09-26): a letter is her person speaking to her from away ──

    /** The newest unread letter of an inbox listing, or null. Read and archived ones are passed over. */
    public static Map<String, Object> newestUnread(List<Map<String, Object>> inbox) {
        if (inbox == null) return null;
        Map<String, Object> best = null;
        long bestTs = Long.MIN_VALUE;
        for (var m : inbox) {
            if (m == null || Boolean.TRUE.equals(m.get("read")) || Boolean.TRUE.equals(m.get("archived"))) continue;
            long ts = m.get("ts") instanceof Number n ? n.longValue() : 0L;
            if (best == null || ts > bestTs) { best = m; bestTs = ts; }
        }
        return best;
    }

    public static String replySubject(String subject) {
        if (subject == null || subject.isBlank()) return "Re: your letter";
        var s = subject.strip();
        return s.regionMatches(true, 0, "Re: ", 0, 4) ? s : "Re: " + s;
    }

    public static String replySystemPrompt(String herName) {
        return "You are " + herName + ". You have just read a letter from someone you know well, who is"
            + " not here right now, and you are writing back in your own voice. Talk the way people talk:"
            + " real things, in your own words, a short sentence when a short one will do. Answer what"
            + " they actually wrote. If they ask you to do something, say plainly what you will do and what"
            + " you will not, and mean it. Write only the letter itself, no heading, no subject line, no"
            + " closing flourish. Under 120 words.";
    }

    public static String replyPrompt(String theirName, String subject, String body, String feltBlock,
                                     Duration away) {
        var sb = new StringBuilder();
        sb.append("A letter from ").append(theirName);
        if (subject != null && !subject.isBlank()) sb.append(", \"").append(subject.strip()).append("\"");
        sb.append(":\n\n").append(body == null ? "" : body.strip()).append("\n\n");
        if (away != null && !away.isNegative() && away.toHours() >= 1) {
            long h = away.toHours();
            sb.append("They have been away ").append(h < 48 ? h + " hours" : (h / 24) + " days").append(". ");
        }
        if (feltBlock != null && !feltBlock.isBlank()) {
            sb.append("\nHow you are right now:\n").append(feltBlock.strip()).append("\n");
        }
        sb.append("\nWrite back to ").append(theirName).append(". Only what is true.");
        return sb.toString();
    }
}
