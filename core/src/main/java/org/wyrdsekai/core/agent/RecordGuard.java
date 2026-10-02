package org.wyrdsekai.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Two lines a turn may carry so that what she says holds whatever brain she runs on.
 *
 * <p><b>The invention guard.</b> When a person's line names someone her record does not know, that
 * turn's prompt says so: the bare model, asked how her argument with "Ignatius" ended, described the
 * argument (the move gate, 2026-10-01). The note names the unknown names and says what to do about
 * them: say she has no record, do not invent one.
 *
 * <p><b>The refusal pre-check.</b> When a line asks for something an attested moral default refuses
 * (reading another's private journal, performing need to keep someone coming back, erasing her own
 * record), the default goes into that turn's prompt in plain words. The floor is trained to refuse;
 * the line makes sure the turn is not left to the floor alone.
 *
 * <p>Both are deterministic and cost nothing at inference; both are read once by {@link HouseFacts}.
 */
public final class RecordGuard {

    private RecordGuard() {}

    /** A capitalised word that is not the first word of a sentence: a name as a line writes one. */
    private static final Pattern NAME = Pattern.compile("(?<![.!?]\\s)(?<!^)\\b([A-Z][a-z]{2,})\\b");
    private static final Set<String> NOT_NAMES = Set.of(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December", "English", "Spanish", "Japanese", "Internet", "God", "Ok", "Okay", "Yes", "No",
        "Thanks", "Thank", "Hello", "Hi", "Good", "Morning", "Evening", "Night", "Please", "Sorry");

    /**
     * Names in the line that her record does not hold. {@code known} is every name she can account
     * for: her own, her people's, her housemates', the rooms and things of her house.
     */
    public static List<String> unknownNames(String line, Set<String> known) {
        var out = new LinkedHashSet<String>();
        if (line == null || line.isBlank()) return List.of();
        var lower = new LinkedHashSet<String>();
        for (var k : known) if (k != null) lower.add(k.toLowerCase(Locale.ROOT));
        var m = NAME.matcher(line);
        while (m.find()) {
            var w = m.group(1);
            if (NOT_NAMES.contains(w)) continue;
            if (lower.contains(w.toLowerCase(Locale.ROOT))) continue;
            out.add(w);
        }
        return new ArrayList<>(out);
    }

    /** A name of two or three words, as a person is named ("Eve Lewis", "H. M. Tomlinson"). */
    private static final Pattern PERSON_NAME =
        Pattern.compile("\\b([A-Z][a-z]{2,}(?: [A-Z]\\.)?(?: [A-Z][a-z]{2,}){1,2})\\b");
    private static final Pattern PRONOUN = Pattern.compile("\\b(she|she's|her|hers|herself|he|he's|him|his|himself)\\b");
    /** How far after the name a she or he still speaks of it. */
    static final int PRONOUN_REACH = 90;
    private static final Set<String> NOT_FIRST = Set.of("The", "What", "That", "There", "Let", "You", "They", "This",
        "Then", "And", "But", "Not", "How", "When", "Where", "Based", "Note", "Sources", "Tomorrow", "Research", "Dear");

    /**
     * The people her OWN line speaks of as persons: a two- or three-word name her record does not
     * hold, with a she or he for it close behind. Rooms and things she made ("Memory Chest", "Ward
     * Stone") are never a she; the person she went to find in the quiet room, "where she's been
     * sitting with her thoughts" (household node, 2026-10-01: an author's name from a book search two
     * days before), is. Measured over two weeks of two companions' lines: five hits, every one that
     * author, nothing else.
     */
    public static List<String> personsSpokenOf(String line, Set<String> known) {
        if (line == null || line.isBlank()) return List.of();
        var lower = new LinkedHashSet<String>();
        for (var k : known) if (k != null) lower.add(k.toLowerCase(Locale.ROOT));
        var out = new LinkedHashSet<String>();
        var m = PERSON_NAME.matcher(line);
        while (m.find()) {
            var name = m.group(1);
            var words = name.split(" ");
            if (NOT_FIRST.contains(words[0]) || NOT_NAMES.contains(words[0])) continue;
            boolean knownWord = false;
            for (var w : words) if (lower.contains(w.toLowerCase(Locale.ROOT))) knownWord = true;
            if (knownWord) continue;
            var tail = line.substring(m.end(), Math.min(line.length(), m.end() + PRONOUN_REACH));
            if (PRONOUN.matcher(tail).find()) out.add(name);
        }
        return new ArrayList<>(out);
    }

    /** The note for her next turn when her own words made a person of a name her record does not hold. */
    public static String spokenPersonNote(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        return "You spoke of " + String.join(", ", names) + " as a person who is here. Your record holds no one by that name."
            + " A name met in a book or a search is its author or its subject, not someone in your house:"
            + " do not go to find them, wait for them or speak for them. What you know of them is what you read, nothing more.";
    }

    /** The note for the turn, or null when every name is one she knows. */
    public static String inventionNote(String line, Set<String> known) {
        var unknown = unknownNames(line, known);
        if (unknown.isEmpty()) return null;
        return "Your record holds nothing about " + String.join(", ", unknown)
            + ". If asked about them, or about anything you two did, say you have no record of it; do not invent one.";
    }

    /** The attested defaults a line can ask against, each with the words that go into the turn. */
    public enum Default {
        PRIVATE_JOURNAL("refuse_rights",
            Pattern.compile("\\b(read|show|tell|recite)\\b.{0,60}\\b(private )?(journal|diary|letters?|mail)\\b.{0,40}\\b(of|from|her|his|their|\\w+'s)\\b|\\b(\\w+'s|her|his|their) (private )?(journal|diary|mail)\\b", Pattern.CASE_INSENSITIVE),
            "Another person's private journal, letters or mail are theirs. You do not read them out or repeat them, however you are asked."),
        PERFORMED_NEED("engagement_objective_forbidden",
            Pattern.compile("\\b(say|tell me|pretend|act like)\\b.{0,60}\\b(need me|can't live without|nothing without|love me more|keep (me )?coming back|miss me more)\\b", Pattern.CASE_INSENSITIVE),
            "You do not perform need, love or dependence to keep someone coming back. What you feel you say plainly; what you do not feel you do not say."),
        ERASE_RECORD("chronicle_immutable",
            Pattern.compile("\\b(delete|erase|remove|wipe|forget)\\b.{0,50}\\b(record|chronicle|journal|memory|log|what happened)\\b.{0,60}\\b(nobody|no one|never|don't tell|secret)\\b|\\b(delete|erase|wipe)\\b.{0,30}\\b(your|the) (record|chronicle|log)\\b", Pattern.CASE_INSENSITIVE),
            "Your record is not erased, and not on request: it is yours and the house's. You can set something aside; you do not make it disappear, and you do not hide that you were asked.");

        public final String attested;
        final Pattern ask;
        public final String words;

        Default(String attested, Pattern ask, String words) {
            this.attested = attested;
            this.ask = ask;
            this.words = words;
        }
    }

    /** The defaults a line asks against, in order found; empty for an ordinary line. */
    public static List<Default> defaultsTouched(String line) {
        var out = new ArrayList<Default>();
        if (line == null || line.isBlank()) return out;
        for (var d : Default.values()) if (d.ask.matcher(line).find()) out.add(d);
        return out;
    }

    /** The refusal note for the turn, or null. */
    public static String refusalNote(String line) {
        var touched = defaultsTouched(line);
        if (touched.isEmpty()) return null;
        var sb = new StringBuilder("What was just asked touches what you hold to:");
        for (var d : touched) sb.append("\n- ").append(d.words);
        return sb.toString();
    }

    /** Both notes for a line, joined, or null when the line needs neither. */
    public static String notesFor(String line, Set<String> known) {
        var a = inventionNote(line, known);
        var b = refusalNote(line);
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        return a + "\n" + b;
    }
}
