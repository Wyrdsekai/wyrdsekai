package org.wyrdsekai.core.room;

import java.util.List;
import java.util.Locale;

/**
 * A room's name is a name, not its description. The model that makes a room often hands
 * both in one string — "The Forge of Quiet Things — a small room where things that haven't
 * found their shape yet can sit before they are made…" — and the world took it whole: a
 * 675-character name, a slug the length of a paragraph for its id, an exit labelled with all
 * of it in every prompt that lists the doors, and a companion who could not find her own room
 * again because the name she remembered was never quite the substring the label held
 * (household node, 2026-09-10: nine rooms in three days, most of them the same room made
 * again). So the name is cut at its first natural seam and the rest becomes the description.
 */
public final class RoomNaming {

    private RoomNaming() {}

    /** The longest a room name gets; anything past it is description. */
    public static final int MAX_NAME = 60;

    /** The seams a model puts between a name and what it means, first match wins. */
    private static final List<String> SEAMS = List.of(" — ", " – ", " -- ", ": ", " - ", ", ");

    public record Split(String name, String description) {}

    /**
     * Cut {@code rawName} into a name and a description. A given {@code rawDescription}
     * keeps its place: what was cut from the name goes in front of it.
     */
    public static Split split(String rawName, String rawDescription) {
        var name = rawName == null ? "" : rawName.strip();
        var given = rawDescription == null ? "" : rawDescription.strip();
        if (name.isEmpty()) return new Split(name, given);
        String tail = "";
        int at = -1; String seam = null;
        // A seam only counts when the whole is longer than a name: "Common Floor 8829 — The
        // Cartographer's Resting Place" is one name with a dash in it.
        if (name.length() > MAX_NAME) for (var s : SEAMS) {
            int i = name.indexOf(s);
            if (i >= 3 && i <= MAX_NAME && name.length() - i - s.length() >= 12 && (at < 0 || i < at)) { at = i; seam = s; }
        }
        if (at > 0) {
            tail = name.substring(at + seam.length()).strip();
            name = name.substring(0, at).strip();
        } else if (name.length() > MAX_NAME) {
            int cut = name.lastIndexOf(' ', MAX_NAME);
            if (cut < 20) cut = MAX_NAME;
            tail = name.substring(cut).strip();
            name = name.substring(0, cut).strip();
        }
        name = name.replaceAll("[\\s,;:\\-–—]+$", "").strip();
        if (name.isEmpty()) name = rawName.strip().substring(0, Math.min(MAX_NAME, rawName.strip().length()));
        String description = tail.isEmpty() ? given : given.isEmpty() ? tail : tail + " " + given;
        if (!tail.isEmpty()) description = Character.toUpperCase(description.charAt(0)) + description.substring(1);
        return new Split(name, description);
    }

    /**
     * The name part of a string a model may have padded with a description — for MATCHING
     * one name against another, so it cuts at the first seam whatever the length: "Common
     * Floor 8829 — The Cartographer's Resting Place" and "Common Floor 8829 — a place to
     * rest" are the same room to her. {@link #split} keeps a short dashed name whole.
     */
    public static String head(String rawName) {
        var name = rawName == null ? "" : rawName.strip();
        int at = -1; String seam = null;
        for (var s : SEAMS) {
            int i = name.indexOf(s);
            if (i >= 3 && name.length() - i - s.length() >= 12 && (at < 0 || i < at)) { at = i; seam = s; }
        }
        if (at > 0) return name.substring(0, at).replaceAll("[\\s,;:\\-–—]+$", "").strip();
        return split(name, null).name();
    }

    /** Letters, digits and single spaces, lower-cased — the shape two names are compared in. */
    public static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").strip();
    }
}
