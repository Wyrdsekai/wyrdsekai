package org.wyrdsekai.core.item;

import org.wyrdsekai.core.agent.ActionPolicy;

import org.wyrdsekai.scripting.api.ItemApiSurface;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code wyrd items check [dir…]}: every scripted item's {@code world.*} calls, checked against
 * the world API this build serves. With no directories it scans what the loader would at boot —
 * the bundled items, the household's own, the user's. Prints one line per mis-wired call and
 * exits 1 when there is any, so a release gate can run it and a steward can read it.
 */
public final class ItemWiringCli {

    private ItemWiringCli() {}

    public static void main(String[] args) {
        var dirs = new ArrayList<Path>();
        boolean quiet = false;
        for (var a : args) {
            if (a.equals("--quiet") || a.equals("-q")) quiet = true;
            else if (!a.startsWith("-")) dirs.add(Path.of(a));
        }
        if (dirs.isEmpty()) {
            var bundled = ScriptedItemLoader.resolveBundledDir();
            if (bundled != null) dirs.add(bundled);
            var household = ScriptedItemLoader.householdItemsDir();
            if (household != null) dirs.add(household);
        }
        int files = 0, broken = 0;
        for (var dir : dirs) {
            if (!Files.isDirectory(dir)) { System.out.println("(no such dir: " + dir + ")"); continue; }
            List<Path> scripts;
            try (Stream<Path> s = Files.list(dir)) {
                scripts = s.filter(p -> p.getFileName().toString().endsWith(".js")).sorted().toList();
            } catch (IOException e) { System.out.println("(cannot list " + dir + ": " + e.getMessage() + ")"); continue; }
            for (var p : scripts) {
                files++;
                String script;
                try { script = Files.readString(p); } catch (IOException e) { System.out.println("BROKEN  " + p + ": unreadable (" + e.getMessage() + ")"); broken++; continue; }
                var manifest = ItemManifestParser.parse(script);
                var name = manifest == null ? p.getFileName().toString() : manifest.name();
                var bad = new ArrayList<String>();
                for (var u : ItemApiSurface.check(script)) bad.add(u.reason());
                if (manifest != null) {
                    ItemApiSurface.commandsNeverRead(script,
                        manifest.commands() == null ? List.of() : manifest.commands()).ifPresent(bad::add);
                    if (!ScriptedItemLoader.isBundledPath(p) && !ScriptedItemLoader.isSystemAuthor(manifest.author())
                            && ActionPolicy.isKnownAction(manifest.name())) {
                        bad.add("'" + manifest.name() + "' is the name of a builtin action — an item by that "
                            + "name can never be reached (the builtin always wins). Give it its own name.");
                    }
                }
                if (bad.isEmpty()) { if (!quiet) System.out.println("ok      " + name + "  (" + p + ")"); continue; }
                broken++;
                System.out.println("MIS-WIRED " + name + "  (" + p + ")");
                for (var r : bad) System.out.println("    " + r);
            }
        }
        System.out.println(files + " item script(s) checked in " + dirs.size() + " dir(s): " + broken + " mis-wired");
        System.exit(broken == 0 ? 0 : 1);
    }
}
