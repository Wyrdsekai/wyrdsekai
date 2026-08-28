package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolving {@code use <thing> <args>} against what a person is CARRYING — once, for
 * every surface.
 *
 * <h2>Why this is one class and not three</h2>
 * The same resolution was written separately in the SSH shell, the WebSocket handler and
 * (not at all) in Telnet, and the three drifted exactly as you would expect:
 *
 * <ul>
 *   <li><b>SSH</b> learned the arg-split fallback in 2026-07-09 — {@code use web-search
 *       antikythera} arrives with the whole phrase as the object name, so the lookup has
 *       to retry on the first token. <b>WS never learned it</b>, so the same command
 *       worked over ssh and failed on the phone.</li>
 *   <li><b>SSH and WS</b> learned on 2026-08-20 that a backend-authored item keeps its
 *       code in {@link ScriptedItemLoader} and not in its inventory row, because
 *       {@code take} copies no script columns.</li>
 *   <li><b>Telnet</b> learned none of it. Its {@code use} went straight to the room, and
 *       the room does not hold what you are carrying — so a carried scripted item
 *       answered <i>"No such object"</i> there, while the SSH javadoc claimed the fix
 *       covered "SSH/Telnet users".</li>
 * </ul>
 *
 * <p>Four surfaces have now been patched one at a time for the same feature in two days.
 * The lesson recorded then was that a verb is not done until every surface has it; the
 * way to stop paying it again is to have one definition to call rather than one shape to
 * copy. Each surface still owns its own OUTPUT — prose over WS, lines over a terminal —
 * because that part genuinely differs.
 *
 * <p>Pure resolution: no execution, no IO beyond the inventory read.
 */
public final class CarriedItemUse {

    private static final Logger log = LoggerFactory.getLogger(CarriedItemUse.class);

    private CarriedItemUse() {}

    /**
     * A carried item, its runnable source, and the args that belong to it.
     *
     * @param item   the inventory row
     * @param source the script to run — from the row when crafted, from the loader when
     *               the item was authored by a coding backend
     * @param target the remaining arguments, after any arg-split correction
     */
    public record Resolved(InventoryService.InventoryItem item, String source, String target) {}

    /**
     * The carried scripted item this command names, if there is one.
     *
     * @param inventory   inventory service; null yields empty
     * @param entityId    who is using it
     * @param objectName  the name as parsed — may be the whole phrase including args
     * @param target      args as parsed — may be empty when the parser folded them in
     */
    public static Optional<Resolved> resolve(InventoryService inventory, String entityId,
                                             String objectName, String target) {
        if (inventory == null || entityId == null
                || objectName == null || objectName.isBlank()) {
            return Optional.empty();
        }
        var found = inventory.findByName(entityId, objectName);
        var args = target;
        // Arg-split fallback: `use web-search-window antikythera mechanism` arrives with
        // the WHOLE phrase as the object name. When that matches nothing carried, try the
        // first token as the item and pass the rest through as its arguments.
        if (found.isEmpty() && objectName.contains(" ")) {
            var sp = objectName.indexOf(' ');
            var head = objectName.substring(0, sp);
            var rest = objectName.substring(sp + 1).trim();
            var headMatch = inventory.findByName(entityId, head);
            if (headMatch.isPresent()) {
                found = headMatch;
                args = (args == null || args.isBlank()) ? rest : rest + " on " + args;
            }
        }
        if (found.isEmpty()) return Optional.empty();
        var item = found.get();
        var source = sourceFor(item);
        return source == null ? Optional.empty()
            : Optional.of(new Resolved(item, source, args));
    }

    /**
     * Where a carried item's code actually lives.
     *
     * <p>A crafted item carries its script in its own inventory row. A backend-authored
     * item does not: {@code take} copies id, name, description and nothing else, so the
     * moment such an item was picked up it lost the link to its own code —
     * {@code isScripted()} said false and the room, which no longer held it, answered
     * "No such object". The loader still knows it by manifest name; ask.
     */
    private static String sourceFor(InventoryService.InventoryItem item) {
        if (item.isScripted() && item.scriptSource() != null) return item.scriptSource();
        var loader = ScriptedItemLoader.get();
        if (loader == null) return null;
        return loader.all().stream()
            .filter(d -> item.objectName().equalsIgnoreCase(d.itemId())
                || item.objectName().equalsIgnoreCase(d.displayName()))
            .map(ScriptedItemDef::scriptSource)
            .findFirst().orElse(null);
    }

    /**
     * The params a first caller sends.
     *
     * <p>Both {@code target} and {@code query} are set to the same args on purpose: the
     * generic {@code use} verb carries arguments as {@code target}, while most item
     * scripts read {@code params.query}. Setting only one of them ran the script cleanly
     * with an empty search string (second-node 2026-07-09).
     */
    public static Map<String, Object> params(String entityId, String target) {
        return params(entityId, target, null);
    }

    public static Map<String, Object> params(String entityId, String target, String locale) {
        var params = new HashMap<String, Object>();
        var args = target == null ? "" : target;
        params.put("target", args);
        params.put("query", args);
        // The items-as-tools contract dispatches `use <name> <args>` with the arguments in
        // params.args, and that is the spelling the preamble teaches every backend to
        // read. Live 2026-08-21: goose wrote `if (typeof params.args !== "string") return
        // an error` — correct against the contract, and this path never set it.
        params.put("args", args);
        params.put("entityId", entityId);
        // The language of the person USING the item, not of whatever the item finds.
        // Live 2026-08-24: an EN speaker asked library_fairytale about a book, the
        // library hits happened to be Spanish catalog rows, the item passed them to
        // llm.complete with no language instruction, and the tale came back in
        // Spanish. An item cannot honor a locale it was never told.
        params.put("locale", locale == null || locale.isBlank() ? "en" : locale);
        return params;
    }

    /**
     * Point this invocation's {@code world.agent.speak} at a room.
     *
     * <p>An item that says something out loud is the ordinary case — it is the first
     * thing the preamble's Tier 3 list offers. For the companion's own items that lands
     * in her voice; for a player-held item it landed nowhere, because
     * {@link VisitorItemProvider} (and {@code HomeOwnerItemProvider}, which extends it)
     * left {@code agentSpeak} a no-op. Live 2026-08-21 that silently swallowed the entire
     * point of a tool built to speak a story into the room.
     *
     * <p>Narration, not impersonation: the words arrive as the room's own voice, so a
     * person's item does not put sentences in the companion's mouth. Called by every
     * surface just before it runs a carried item — one wiring, not one per transport.
     *
     * @param provider what {@code ItemProviderRegistry} handed back for this caller
     * @param roomId   where the person is standing; null skips the wiring
     * @param callerId whose use this is, for the event's caller field
     */
    /** Tell this invocation's provider what language its person speaks, so the
     *  world.llm.* prose surfaces can default to it. No-op for non-visitor
     *  providers (the companion's own voice pipeline already handles locale). */
    public static void attachLocale(ItemWorldApiProvider provider, String locale) {
        if (provider instanceof VisitorItemProvider visitor) {
            visitor.withCallerLocale(locale);
        }
    }

    public static void attachRoomVoice(ItemWorldApiProvider provider, String roomId,
                                       String callerId) {
        if (!(provider instanceof VisitorItemProvider visitor)
                || roomId == null || roomId.isBlank()) {
            return;
        }
        visitor.withRoomVoice(text -> {
            var room = RoomRegistry.get() == null ? null : RoomRegistry.get().ref(roomId);
            if (room == null) {
                log.debug("agent.speak from a carried item had no room {} to land in",
                    roomId);
                return;
            }
            room.tell(new RoomCommand.ItemBridgeAction(
                callerId, new RoomCommand.ItemBridgeSubAction.Narrate(text)));
        });
    }

    /**
     * What authority this carried item runs with.
     *
     * <p>DEFAULT-DENY (#1, 2026-07-19 OSS hardening; polarity fixed after adversarial
     * review): only a positively-identified bundled/disk-installed item runs
     * UNRESTRICTED. Crafted, companion-GIVEN and cross-zone TRANSITED scripts run under
     * the crafted ceiling. The old {@code "crafted".equals(takenFrom)} test failed OPEN.
     */
    public static ItemCapabilitySet capabilitiesFor(String objectId) {
        return ToolItemStarterKit.isTrustedScriptId(objectId)
            ? ItemCapabilitySet.UNRESTRICTED
            : ItemCapabilitySet.craftedDefault();
    }
}
