package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

/**
 * Process-global registry handing out a REAL {@link ItemWorldApiProvider}
 * for an acting entity — the bridge that lets room-side item invocations
 * (furnishings, coding artifacts) reach live household services instead of
 * a stub.
 *
 * <p>Why this exists: the rich providers ({@link HomeOwnerItemProvider}
 * with HomeClient / inventory / federation / bond suppliers) are wired in
 * the server layer per surface (WS / SSH / telnet), historically only for
 * CARRIED inventory items. Room furnishings whose id matches a
 * {@code scripts/items/*.js} def are invoked inside {@link
 * org.wyrdsekai.core.room.RoomActor} (core), which can't see those server
 * services — so its invocations ran against
 * {@code StubItemWorldApiProvider} and every {@code world.*} call came
 * back empty ("used xxx" and nothing happens). The server layer registers
 * a factory here at boot; RoomActor asks for a provider per acting
 * entity at use-time.</p>
 *
 * <p>Mirrors the codebase's established process-global singletons
 * ({@link ScriptedItemLoader#get()},
 * {@code CodingItemRegistry.get()}, {@code LocalCommandRouter.get()}).
 * When no factory is registered (unit tests, bare boots) callers fall
 * back to their previous stub behavior.</p>
 */
public final class ItemProviderRegistry {

    /** Builds a provider scoped to the acting entity (player or agent). */
    @FunctionalInterface
    public interface Factory {
        ItemWorldApiProvider forEntity(String entityId);
    }

    private static volatile Factory factory;

    private static final Logger log = LoggerFactory.getLogger(ItemProviderRegistry.class);

    private ItemProviderRegistry() {}

    /** Server boot: install the live-service-backed factory. Last write wins. */
    public static void register(Factory f) {
        factory = f;
    }

    /**
     * Provider for the acting entity, or {@code null} when no factory is
     * registered — caller supplies its own fallback (typically the stub).
     */
    public static ItemWorldApiProvider forEntity(String entityId) {
        var f = factory;
        if (f == null) {
            log.warn("No item-provider factory registered — '{}' gets the stub "
                + "(every world.* surface will answer empty)", entityId);
            return null;
        }
        try {
            return f.forEntity(entityId);
        } catch (RuntimeException e) {
            // A provider-construction failure must degrade to stub behavior,
            // never break the use-command path — but NEVER silently (2026-07-18:
            // the bond crystal answered "no bonds yet" for weeks and nothing
            // said the provider had fallen back to the stub).
            log.warn("Item-provider factory threw for '{}' — falling back to stub: {}",
                entityId, e.toString());
            return null;
        }
    }

    /** Test seam. */
    public static void resetForTests() {
        factory = null;
    }
}
