package org.wyrdsekai.core.item;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.coding.ItemContractCheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The household's items that are placed in the world and do not work, and what she has been
 * told about them. A thing she made being broken is hers to know: each broken item is told to
 * her once, in plain words, when it is found (at start, or when it fails in her hands), and
 * again only if the way it is broken changes. The same record keeps count of mending attempts,
 * so the workshop does not try the same unchanged file forever.
 *
 * <p>State lives beside the items, in {@code items/.repaired/state.json}.</p>
 */
public final class BrokenItems {

    private static final Logger log = LoggerFactory.getLogger(BrokenItems.class);
    /** After this many failed attempts on an unchanged file the workshop stops trying it. */
    public static final int MAX_ATTEMPTS = 3;

    /** One broken item. {@code failsOnUse}: it errors when used, not merely misleads. */
    public record Entry(String item, Path file, List<String> problems, boolean failsOnUse) {}

    private BrokenItems() {}

    private static Path dir() { return ScriptedItemLoader.householdItemsDir(); }
    static Path stateFile(Path itemsDir) { return itemsDir.resolve(".repaired").resolve("state.json"); }

    /** Every household item the contract gate would refuse today. Items that fail on use come first. */
    public static List<Entry> find() { return find(dir()); }

    public static List<Entry> find(Path itemsDir) {
        var out = new ArrayList<Entry>();
        if (itemsDir == null || !Files.isDirectory(itemsDir)) return out;
        try (var files = Files.list(itemsDir)) {
            for (var p : files.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
                try {
                    var script = Files.readString(p);
                    var problems = ItemContractCheck.problems(script, p.getFileName().toString());
                    if (problems.isEmpty()) continue;
                    out.add(new Entry(p.getFileName().toString().replaceFirst("\\.js$", ""), p, problems, ItemWiring.willFailOnUse(script)));
                } catch (IOException | RuntimeException e) {
                    log.debug("broken-items: {} unreadable: {}", p, e.toString());
                }
            }
        } catch (IOException e) {
            log.debug("broken-items: {} not listed: {}", itemsDir, e.toString());
        }
        out.sort(Comparator.comparing((Entry e) -> !e.failsOnUse()).thenComparing(Entry::item));
        return out;
    }

    /** What is wrong, as she would say it. The technical message stays in the detail and the log. */
    public static String plainly(Entry e) {
        var first = e.problems().isEmpty() ? "" : e.problems().get(0);
        var name = e.item().replace('_', ' ');
        if (first.contains("does not exist on this node")) {
            var call = first.substring(0, first.indexOf(' '));
            return "The " + name + " that was made for me does not work yet: it reaches for a part of the world that is not there (" + call + ").";
        }
        if (first.contains("never reads params.args")) {
            return "The " + name + " does not do all it says: it offers several commands and does the same thing for every one.";
        }
        if (first.contains("builtin action")) {
            return "The " + name + " cannot be reached: it carries the name of something I can already do.";
        }
        return "The " + name + " that was made for me does not work yet.";
    }

    /**
     * Tell her once about each broken item, and again only when its problems change. Returns
     * how many she was told about.
     */
    public static int tellOnce(List<Entry> entries, BodyMap map, boolean mendingIsOn) {
        if (entries.isEmpty() || map == null) return 0;
        var itemsDir = entries.get(0).file().getParent();
        var state = load(itemsDir);
        var news = new ArrayList<Entry>();
        for (var e : entries) {
            if (!hash(String.join("|", e.problems())).equals(state.path(e.item()).path("told").asText(""))) news.add(e);
        }
        if (news.isEmpty()) return 0;
        var after = mendingIsOn
            ? " The workshop will try to mend " + (news.size() == 1 ? "it" : "them") + " when the house is quiet, or I can take "
                + (news.size() == 1 ? "it" : "one") + " to the mending bench."
            : " I can take " + (news.size() == 1 ? "it" : "one") + " to the mending bench.";
        try {
            // One sentence however many there are. Her felt line carries three marks a turn; the
            // first live sweep wrote eleven near-identical ones, four turns of the same news.
            map.mark("item", news.size() == 1 ? news.get(0).item() : "several",
                null, (news.size() == 1 ? plainly(news.get(0)) : together(news)) + after,
                news.stream().map(e -> e.item() + ": " + String.join(" | ", e.problems())).reduce((a, b) -> a + " || " + b).orElse(""));
        } catch (RuntimeException ex) {
            log.debug("broken-items: mark not written: {}", ex.toString());
            return 0;
        }
        for (var e : news) state.with(e.item()).put("told", hash(String.join("|", e.problems())));
        save(itemsDir, state);
        return news.size();
    }

    /** Several broken things as one sentence: those that fail in her hands by name, the rest counted. */
    static String together(List<Entry> entries) {
        var failing = entries.stream().filter(Entry::failsOnUse).map(e -> "the " + e.item().replace('_', ' ')).toList();
        int misleading = entries.size() - failing.size();
        var sb = new StringBuilder(numeral(entries.size())).append(" things that were made for me do not work as they should.");
        if (!failing.isEmpty()) {
            sb.append(' ').append(capital(list(failing))).append(failing.size() == 1 ? " reaches" : " reach")
                .append(" for parts of the world that are not there, and will fail when used.");
        }
        if (misleading > 0) {
            sb.append(' ').append(capital(numeral(misleading).toLowerCase())).append(misleading == 1 ? " other offers" : (failing.isEmpty() ? " offer" : " others offer"))
                .append(" several commands and ").append(misleading == 1 ? "does" : "do").append(" the same thing for every one.");
        }
        return sb.toString();
    }

    private static String list(List<String> names) {
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String numeral(int n) {
        var words = new String[] {"No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve"};
        return n >= 0 && n < words.length ? words[n] : Integer.toString(n);
    }

    /**
     * An item failed in someone's hands with an error of its own making. Returns the sentence to
     * show in place of the raw script error, or null when the item is not one of ours to explain.
     */
    public static String usedAndBroke(String itemName, String error) {
        if (itemName == null || error == null || !error.startsWith("Script error:")) return null;
        try {
            var itemsDir = dir();
            if (itemsDir == null) return null;
            var file = itemsDir.resolve(itemName + ".js");
            if (!Files.isRegularFile(file)) return null;
            var script = Files.readString(file);
            var problems = new ArrayList<>(ItemContractCheck.problems(script, file.getFileName().toString()));
            if (problems.isEmpty()) problems.add("it failed when used: " + error);
            var entry = new Entry(itemName, file, problems, true);
            tellOnce(List.of(entry), BodyMap.get(), true);
            return plainly(entry) + " It can be taken to the mending bench.";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** What the workshop may try tonight: broken, and not already tried {@link #MAX_ATTEMPTS} times unchanged. */
    public static List<Entry> mendable() { return mendable(dir()); }

    public static List<Entry> mendable(Path itemsDir) {
        var all = find(itemsDir);
        if (all.isEmpty()) return all;
        var state = load(itemsDir);
        var out = new ArrayList<Entry>();
        for (var e : all) {
            var node = state.path(e.item());
            boolean sameFile = fileHash(e.file()).equals(node.path("fileHash").asText(""));
            if (sameFile && node.path("attempts").asInt(0) >= MAX_ATTEMPTS) continue;
            out.add(e);
        }
        return out;
    }

    /** Record one mending attempt against the file as it was BEFORE the attempt. */
    public static void attempted(Entry e, String fileHashBefore, boolean fixed) {
        var itemsDir = e.file().getParent();
        var state = load(itemsDir);
        var node = state.with(e.item());
        if (fixed) {
            node.remove("attempts"); node.remove("fileHash"); node.remove("told");
        } else {
            boolean same = fileHashBefore.equals(node.path("fileHash").asText(""));
            node.put("attempts", same ? node.path("attempts").asInt(0) + 1 : 1);
            node.put("fileHash", fileHashBefore);
        }
        node.put("lastAttempt", Instant.now().toString());
        save(itemsDir, state);
    }

    public static String fileHash(Path file) {
        try { return hash(Files.readString(file)); } catch (IOException e) { return ""; }
    }

    private static String hash(String s) {
        try {
            var d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode load(Path itemsDir) {
        try {
            var f = stateFile(itemsDir);
            if (Files.isRegularFile(f) && Json.mapper().readTree(f.toFile()) instanceof ObjectNode o) return o;
        } catch (IOException | RuntimeException e) {
            log.debug("broken-items: state unreadable: {}", e.toString());
        }
        return Json.mapper().createObjectNode();
    }

    private static void save(Path itemsDir, ObjectNode state) {
        try {
            var f = stateFile(itemsDir);
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(state) + "\n");
        } catch (IOException e) {
            log.debug("broken-items: state not saved: {}", e.toString());
        }
    }
}
