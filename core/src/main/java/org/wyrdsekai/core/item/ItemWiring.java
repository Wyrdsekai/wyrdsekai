package org.wyrdsekai.core.item;

import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.scripting.api.ItemApiSurface;
import org.wyrdsekai.scripting.api.ItemCapabilityAudit;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Is this item wired to the world it will run in? Four questions, each a way an item says it
 * does something and does not: a {@code world.*} call that does not exist on this node (it
 * WILL fail on use), a call whose capability the manifest does not declare (refused on use),
 * commands the manifest declares and {@code invoke()} never reads (every
 * command does the same thing), and a name a builtin action already owns (it can never be
 * reached). One implementation, asked by the loader's audit, by {@code wyrd items check}, and
 * by the contract gate an item passes before it is placed. Until 2026-09-17 the gate did not
 * ask, and items that called {@code world.memory.get} were placed as finished.
 */
public final class ItemWiring {

    private ItemWiring() {}

    /** Every wiring problem, each message naming its fix. Empty when the item is wired. */
    public static List<String> problems(String script, ItemManifest manifest) {
        var out = new ArrayList<String>();
        if (script == null) return out;
        for (var u : ItemApiSurface.check(script)) out.add(u.reason());
        if (manifest != null) {
            // The manifest is enforced when the item runs (2026-09-28): an undeclared call is
            // refused in the person's hands. Say so here, where it can still be fixed.
            out.addAll(ItemCapabilityAudit.undeclared(script, manifest));
            ItemApiSurface.commandsNeverRead(script,
                manifest.commands() == null ? List.of() : manifest.commands()).ifPresent(out::add);
            if (manifest.name() != null && !ScriptedItemLoader.isSystemAuthor(manifest.author())
                    && ActionPolicy.isKnownAction(manifest.name())) {
                out.add("'" + manifest.name() + "' is the name of a builtin action — an item by that "
                    + "name can never be reached (the builtin always wins). Give it its own name.");
            }
        }
        return out;
    }

    /** True when at least one problem means the item will break when used, not merely mislead. */
    public static boolean willFailOnUse(String script) {
        if (script == null) return false;
        if (!ItemApiSurface.check(script).isEmpty()) return true;
        var manifest = ItemManifestParser.parse(script);
        return manifest != null && !ItemCapabilityAudit.undeclared(script, manifest).isEmpty();
    }
}
