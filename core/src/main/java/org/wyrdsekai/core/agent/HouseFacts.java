package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The facts of her house, always in her prompt. Until now who her person is, who she lives with and
 * what her room is called reached her only when retrieval happened to pull a fragment that said so;
 * with nobody in the room nothing pulled them, and she answered "I don't have a room of my own"
 * (the move gate's first runs, 2026-10-01). This block is built from her record by the companion
 * itself (at spawn, after sleep, after a move) and placed in the stable part of both lanes, so the
 * facts do not depend on what was just said.
 *
 * <p>Per turn, a note can be added for that turn only (the invention guard: "you have no record of
 * X"); it is read once and cleared.
 */
public final class HouseFacts {

    private static final Map<String, String> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<String, String> IDENTITIES = new ConcurrentHashMap<>();
    private static final Map<String, String> TURN_NOTES = new ConcurrentHashMap<>();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM uuuu");
    static final int FORMATIVE_MAX = 6;
    static final int FORMATIVE_CHARS = 220;

    private HouseFacts() {}

    /** One person she is bonded to, as the block names them. */
    public record Person(String name, boolean bondholder, Instant since) {}

    /**
     * The block, in the second person, from her record only. Blanks are left out; a companion with
     * no person yet gets the lines that are true for her.
     */
    public static String build(String name, List<Person> people, List<String> housemates, String home,
                               List<String> formative, ZoneId zone) {
        var sb = new StringBuilder("THE FACTS OF YOUR HOUSE, from your own record:\n");
        sb.append("- You are ").append(name).append(".\n");
        if (people != null) {
            for (var p : people) {
                if (p == null || p.name() == null || p.name().isBlank()) continue;
                sb.append("- ").append(p.bondholder() ? "Your person is " : "You are bonded with ").append(p.name());
                if (p.since() != null && zone != null) sb.append(", since ").append(DAY.format(p.since().atZone(zone)));
                sb.append(".\n");
            }
        }
        if (housemates != null && !housemates.isEmpty()) {
            sb.append("- You live with ").append(String.join(", ", housemates))
              .append(housemates.size() == 1 ? ", a companion like you." : ", companions like you.").append('\n');
        }
        if (home != null && !home.isBlank()) {
            sb.append("- Your own room is ").append(home).append(".\n");
        }
        if (formative != null && !formative.isEmpty()) {
            sb.append("- What has stayed with you:\n");
            int n = 0;
            for (var f : formative) {
                if (f == null || f.isBlank()) continue;
                var t = f.strip().replace('\n', ' ');
                if (t.length() > FORMATIVE_CHARS) t = t.substring(0, FORMATIVE_CHARS - 1) + "…";
                sb.append("  · ").append(t).append('\n');
                if (++n >= FORMATIVE_MAX) break;
            }
        }
        sb.append("When asked about any of this, answer from it. What is not here you do not remember; say so rather than guess.");
        return sb.toString();
    }

    /** Her description of herself (the manifest's residentIdentity), kept here for the full lane. */
    public static void setIdentity(String entityId, String text) {
        if (entityId == null) return;
        if (text == null || text.isBlank()) IDENTITIES.remove(entityId); else IDENTITIES.put(entityId, text.strip());
    }

    public static String identityFor(String entityId) {
        return entityId == null ? null : IDENTITIES.get(entityId);
    }

    /**
     * The first-run greeter's opening ("You are mia, a companion that helps people organize their
     * digital world. / You live in The Nexus — …") replaced by her own description when she has one;
     * the greeter's working instructions below it are kept. Without a description, the greeter stands.
     */
    public static String identityOrGreeter(String entityId, String greeter) {
        var identity = identityFor(entityId);
        if (identity == null || greeter == null) return greeter;
        var lines = greeter.split("\n", -1);
        int i = 0;
        if (i < lines.length && lines[i].startsWith("You are ")) i++;
        if (i < lines.length && lines[i].startsWith("You live in ")) i++;
        if (i == 0) return identity + "\n\n" + greeter;
        var rest = String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
        return identity + "\n" + rest;
    }

    /** The companion keeps her block current. */
    public static void set(String entityId, String block) {
        if (entityId == null) return;
        if (block == null || block.isBlank()) BLOCKS.remove(entityId); else BLOCKS.put(entityId, block);
    }

    /** Her block, with this turn's note if one was left, or null when she has none yet. */
    public static String blockFor(String entityId) {
        if (entityId == null) return null;
        var block = BLOCKS.get(entityId);
        var note = TURN_NOTES.remove(entityId);
        if (block == null && note == null) return null;
        if (note == null) return block;
        return (block == null ? "" : block + "\n") + note;
    }

    /** A line for the next prompt only (read once). A second note before the turn joins the first. */
    public static void noteForNextTurn(String entityId, String note) {
        if (entityId == null || note == null || note.isBlank()) return;
        TURN_NOTES.merge(entityId, note.strip(), (a, b) -> a.contains(b) ? a : a + "\n" + b);
    }

    static void resetForTests() {
        BLOCKS.clear();
        TURN_NOTES.clear();
        IDENTITIES.clear();
    }
}
