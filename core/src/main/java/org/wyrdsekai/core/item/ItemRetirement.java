package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Taking an item out of the world — from every place it lives.
 *
 * <p>An item is not one thing. A scripted item made in-house exists as an inventory row, a
 * room object, a registration in {@link ScriptedItemLoader}, and a {@code .js} in the
 * household items directory that is re-read on every boot. Removing some of those and not
 * the others is worse than removing none: the object disappears and the name still
 * resolves, or the row goes and the file brings it back at the next restart.
 *
 * <p>There was no way for a steward to remove an item at all — only {@code drop}, which
 * leaves it in the room. That is why a Nexus ends up with two objects called {@code codex}
 * and no way to be rid of either. The companion's own {@code destroy_tool} tombstones a
 * locker entry and touches none of the above.
 *
 * <p><b>Soft by default.</b> The file is moved to {@code items/retired/}, not deleted.
 * These are things she made; a typo must not be able to erase one. Restoring is moving the
 * file back and reloading. The companion's own destroy verb already works this way (a
 * tombstone, un-retirable for 30 days) and this keeps faith with that.
 */
public final class ItemRetirement {

    private static final Logger log = LoggerFactory.getLogger(ItemRetirement.class);

    /** Where retired scripts wait, in case removing one was a mistake. */
    public static final String RETIRED_DIR = "retired";

    private ItemRetirement() {}

    /** What was actually removed, so a caller can tell the person the truth. */
    public record Outcome(boolean found, String itemId, List<String> removed,
                          List<String> problems) {

        public boolean clean() { return found && problems.isEmpty(); }

        /** A sentence describing what happened, for the person who asked. */
        public String describe(String requested) {
            if (!found) return "There's nothing called '" + requested + "' to retire.";
            var sb = new StringBuilder("Retired ").append(itemId);
            if (!removed.isEmpty()) sb.append(" — ").append(String.join(", ", removed));
            sb.append('.');
            if (!problems.isEmpty()) {
                sb.append(" Not everything came away cleanly: ")
                  .append(String.join("; ", problems)).append('.');
            }
            return sb.toString();
        }
    }

    /**
     * Retire a scripted item by id or display name.
     *
     * <p>Removes the registration and moves the backing script aside. Inventory rows and
     * room objects are the caller's to clear — they know which player and which room —
     * but this is what stops it coming back.
     */
    public static Outcome retireScripted(String nameOrId) {
        var removed = new ArrayList<String>();
        var problems = new ArrayList<String>();
        var loader = ScriptedItemLoader.get();
        if (loader == null || nameOrId == null || nameOrId.isBlank()) {
            return new Outcome(false, nameOrId, removed, problems);
        }
        var def = loader.all().stream()
            .filter(d -> nameOrId.equalsIgnoreCase(d.itemId())
                || nameOrId.equalsIgnoreCase(d.displayName()))
            .findFirst().orElse(null);
        if (def == null) return new Outcome(false, nameOrId, removed, problems);

        // Move the backing file first: if this fails, leaving it registered is the
        // honest state, and a boot would have brought it back anyway.
        var source = def.sourcePath();
        var home = ScriptedItemLoader.householdItemsDir();
        if (source != null && Files.isRegularFile(source)) {
            try {
                var retiredDir = (home != null ? home : source.getParent())
                    .resolve(RETIRED_DIR);
                Files.createDirectories(retiredDir);
                Files.move(source, retiredDir.resolve(source.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
                removed.add("its script moved to " + RETIRED_DIR + "/ (restorable)");
            } catch (Exception e) {
                problems.add("the script could not be moved (" + e.getMessage()
                    + "), so it will come back on restart");
            }
        }
        try {
            loader.reloadAll();   // drops the registration for the moved file
            removed.add("unregistered");
        } catch (Exception e) {
            problems.add("the loader did not reload: " + e.getMessage());
        }
        log.info("[item-retire] '{}' → removed={} problems={}",
            def.itemId(), removed, problems);
        return new Outcome(true, def.itemId(), removed, problems);
    }

    /**
     * Retire whatever answers to this name, wherever it lives.
     *
     * <p>{@link #retireScripted} only knows items the {@link ScriptedItemLoader} has
     * registered from a file. Most things in a world are not that: an item made by
     * {@code craft_from_template} carries its script in an inventory row, and a plain
     * backend artifact is just a room object. Asked to retire one of those, the
     * file-only version reported <i>"there's nothing called that"</i> while the thing sat
     * in plain view — which is what a steward saw on 2026-08-20.
     *
     * <p>The caller supplies the two removals only it can perform: pulling the object out
     * of the room, and clearing the inventory row. Both report whether they found
     * anything, so the answer given to the person is the truth about all three places.
     *
     * @param fromRoom      removes the named object from the room; true if it was there
     * @param fromInventory removes the named item from the person's inventory; true if held
     */
    public static Outcome retireAnywhere(String nameOrId,
            java.util.function.Predicate<String> fromRoom,
            java.util.function.Predicate<String> fromInventory) {
        var script = retireScripted(nameOrId);
        var removed = new ArrayList<>(script.removed());
        var problems = new ArrayList<>(script.problems());
        boolean found = script.found();

        try {
            if (fromRoom != null && fromRoom.test(nameOrId)) {
                removed.add("taken out of the room");
                found = true;
            }
        } catch (Exception e) {
            problems.add("could not clear it from the room: " + e.getMessage());
        }
        try {
            if (fromInventory != null && fromInventory.test(nameOrId)) {
                removed.add("removed from your inventory");
                found = true;
            }
        } catch (Exception e) {
            problems.add("could not clear it from your inventory: " + e.getMessage());
        }
        return new Outcome(found, script.found() ? script.itemId() : nameOrId,
            removed, problems);
    }

    /** Put a retired script back and re-register it. */
    public static Outcome restore(String fileName) {
        var removed = new ArrayList<String>();
        var problems = new ArrayList<String>();
        var home = ScriptedItemLoader.householdItemsDir();
        if (home == null || fileName == null || fileName.isBlank()) {
            return new Outcome(false, fileName, removed, problems);
        }
        var retired = home.resolve(RETIRED_DIR).resolve(fileName);
        if (!Files.isRegularFile(retired)) {
            return new Outcome(false, fileName, removed, problems);
        }
        try {
            Files.move(retired, home.resolve(fileName),
                StandardCopyOption.REPLACE_EXISTING);
            ScriptedItemLoader.get().reloadAll();
            removed.add("restored and registered again");
            return new Outcome(true, fileName, removed, problems);
        } catch (Exception e) {
            problems.add(e.getMessage());
            return new Outcome(true, fileName, removed, problems);
        }
    }

    /** Everything currently sitting in retirement, newest first is not promised. */
    public static List<String> listRetired() {
        var home = ScriptedItemLoader.householdItemsDir();
        if (home == null) return List.of();
        var dir = home.resolve(RETIRED_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted().toList();
        } catch (Exception e) {
            log.debug("[item-retire] could not list {}: {}", dir, e.toString());
            return List.of();
        }
    }
}
