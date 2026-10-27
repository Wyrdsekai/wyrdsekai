package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.ItemContractCheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The household's scripted items as things: which are unfinished, and putting one away. The
 * workshop built twelve "check" tools on the household node from one sentence in her own-time
 * prompt; they sat on both companions' menus for weeks because nothing could put a household
 * item away (2026-10-27). A put-away item's file moves beside the items folder, so the loader
 * drops it on its next look; it is kept, and {@code wyrd items out} brings it back.
 */
public final class HouseholdItems {

    private static final Logger log = LoggerFactory.getLogger(HouseholdItems.class);
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{2,63}");

    private HouseholdItems() {}

    /** A household item the contract gate would refuse today, and the first reason. */
    public record Unfinished(String name, String problem) {}

    /** The household items that do not pass the gate, by name, up to {@code max}. */
    public static List<Unfinished> unfinished(int max) {
        return unfinished(max, null);
    }

    /**
     * As above, only items touched within {@code recent} (null for all). Her own-time prompt lists the
     * recent ones: a tool broken for weeks, whose mending already failed, would otherwise open every
     * build-or-fix turn of every companion in the house, and a nudge repeated every turn is a loop.
     */
    public static List<Unfinished> unfinished(int max, Duration recent) {
        var out = new ArrayList<Unfinished>();
        var dir = ScriptedItemLoader.householdItemsDir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        var since = recent == null ? null : Instant.now().minus(recent);
        try (Stream<Path> s = Files.list(dir)) {
            for (var p : s.filter(f -> f.getFileName().toString().endsWith(".js")).sorted().toList()) {
                if (out.size() >= max) break;
                if (since != null) {
                    try { if (Files.getLastModifiedTime(p).toInstant().isBefore(since)) continue; }
                    catch (IOException e) { continue; }
                }
                var name = p.getFileName().toString();
                try {
                    var problems = ItemContractCheck.problems(Files.readString(p), name);
                    if (!problems.isEmpty()) out.add(new Unfinished(name.substring(0, name.length() - 3), problems.getFirst()));
                } catch (IOException e) {
                    log.debug("HouseholdItems: {} not read: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("HouseholdItems: {} not listed: {}", dir, e.getMessage());
        }
        return out;
    }

    /** Where put-away items wait: beside the items folder, never inside it. */
    public static Path awayDir() {
        var dir = ScriptedItemLoader.householdItemsDir();
        return dir == null ? null : dir.resolveSibling("items-away");
    }

    /** The names of the items put away. */
    public static List<String> away() {
        var out = new ArrayList<String>();
        var dir = awayDir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (var p : s.filter(f -> f.getFileName().toString().endsWith(".js")).sorted().toList()) {
                var n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - 3));
            }
        } catch (IOException e) {
            log.debug("HouseholdItems: {} not listed: {}", dir, e.getMessage());
        }
        return out;
    }

    /**
     * Put a household item away: its file and its kept versions move to {@link #awayDir()}. Empty
     * when there is no such item. The loader drops it on its next look; every menu loses it.
     */
    public static Optional<String> putAway(String name) {
        return move(name, ScriptedItemLoader.householdItemsDir(), awayDir(), "is put away. It is kept; wyrd items out " + name + " brings it back.");
    }

    /** Take a put-away household item out again. Empty when none of that name is away. */
    public static Optional<String> takeOut(String name) {
        return move(name, awayDir(), ScriptedItemLoader.householdItemsDir(), "is out again; it is back on the menus within a minute.");
    }

    private static Optional<String> move(String name, Path from, Path to, String said) {
        if (name == null || !NAME.matcher(name).matches() || from == null || to == null) return Optional.empty();
        var src = from.resolve(name + ".js");
        if (!Files.isRegularFile(src)) return Optional.empty();
        try {
            Files.createDirectories(to);
            Files.move(src, to.resolve(name + ".js"), StandardCopyOption.REPLACE_EXISTING);
            var versions = from.resolve("versions");
            if (Files.isDirectory(versions)) {
                try (Stream<Path> s = Files.list(versions)) {
                    var mine = s.filter(f -> f.getFileName().toString().startsWith(name + ".")).toList();
                    if (!mine.isEmpty()) Files.createDirectories(to.resolve("versions"));
                    for (var v : mine) Files.move(v, to.resolve("versions").resolve(v.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("HouseholdItems: {} moved {} → {}", name, from, to);
            return Optional.of(name + " " + said);
        } catch (IOException e) {
            log.warn("HouseholdItems: could not move {}: {}", name, e.getMessage());
            return Optional.of(name + " could not be moved: " + e.getMessage());
        }
    }
}
