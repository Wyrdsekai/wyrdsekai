package org.wyrdsekai.common.home;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of known resource types and their capability matrices.
 *
 * <p>Seeded with the M1 resource types; additional types register via
 * {@link #register}. The registry is the single source of truth for "is this
 * a valid grant shape?" — CheckAccess uses it to reject nonsensical
 * combinations (e.g. {@code use} on a {@code journal}, {@code write} on
 * {@code audit-log}) at issue time rather than at access time.</p>
 */
public final class ResourceTypeRegistry {

    // --- Canonical resource type names ---
    public static final String JOURNAL          = "journal";
    public static final String COLLECTION       = "collection";
    public static final String COMPANION        = "companion";
    public static final String BOND             = "bond";
    public static final String AGREEMENT        = "agreement";
    public static final String INFERENCE_BUDGET = "inference-budget";
    public static final String INVENTORY_ITEM   = "inventory-item";
    public static final String SOUL_FRAGMENT    = "soul-fragment";
    public static final String HOME_ROOM        = "home-room";
    public static final String MCP_TOOL         = "mcp-tool";
    public static final String MEMORY_INDEX     = "memory-index";
    public static final String AUDIT_LOG        = "audit-log";
    public static final String ACTION           = "action";  // companion autonomy (§11.2)
    public static final String RELAY            = "relay";        // message-routing infra
    public static final String RELAY_ADMIN      = "relay-admin";  // delegatable relay management

    private static final Map<String, ResourceType> TYPES = new ConcurrentHashMap<>();

    static {
        // --- M1 types seeded matrix ---
        register(new ResourceType(JOURNAL,
            EnumSet.of(Capability.read, Capability.write, Capability.delegate), true));
        register(new ResourceType(COLLECTION,
            EnumSet.of(Capability.read, Capability.write, Capability.delegate), true));
        register(new ResourceType(COMPANION,
            EnumSet.of(Capability.read, Capability.use, Capability.delegate), true));
        register(new ResourceType(BOND,
            EnumSet.of(Capability.read, Capability.write, Capability.attest), true));
        register(new ResourceType(AGREEMENT,
            EnumSet.of(Capability.read, Capability.write, Capability.use, Capability.delegate), true));
        register(new ResourceType(INFERENCE_BUDGET,
            EnumSet.of(Capability.use, Capability.delegate), false));
        register(new ResourceType(INVENTORY_ITEM,
            EnumSet.of(Capability.read, Capability.use), true));
        register(new ResourceType(SOUL_FRAGMENT,
            EnumSet.of(Capability.read, Capability.attest), true));
        register(new ResourceType(HOME_ROOM,
            EnumSet.of(Capability.use, Capability.delegate), false));
        register(new ResourceType(MCP_TOOL,
            EnumSet.of(Capability.use, Capability.delegate), true));
        register(new ResourceType(MEMORY_INDEX,
            EnumSet.of(Capability.read, Capability.write, Capability.delegate), true));
        register(new ResourceType(AUDIT_LOG,
            EnumSet.of(Capability.read), false));
        register(new ResourceType(ACTION,
            EnumSet.of(Capability.use, Capability.delegate), true));
        // --- ---
        // A relay is a resource owned by its steward's Home, identified by the
        // relay's stable DID (id = relay did:key:, derived from its NKey).
        register(new ResourceType(RELAY,
            EnumSet.of(Capability.use, Capability.delegate), true));
        // relay-admin is the delegatable management capability over a relay.
        // The grant's scope payload carries the §6 scope vocabulary
        // (full | moderation | invite-only); capability use (act) or delegate.
        register(new ResourceType(RELAY_ADMIN,
            EnumSet.of(Capability.use, Capability.delegate), true));
    }

    private ResourceTypeRegistry() {}

    /** Register a new resource type. Replaces any previous registration with the same name. */
    public static void register(ResourceType type) {
        TYPES.put(type.name(), type);
    }

    /** Look up a type by name. Returns {@code null} if not registered. */
    public static ResourceType get(String name) {
        return TYPES.get(name);
    }

    /** All registered type names. */
    public static Set<String> typeNames() {
        return Set.copyOf(TYPES.keySet());
    }

    /**
     * Validate that a grant's (resource-type, capability) combination is registered and valid.
     * Throws {@link IllegalArgumentException} with a specific reason on failure.
     */
    public static void validate(ResourceUri resource, Capability capability) {
        var type = get(resource.type());
        if (type == null) {
            throw new IllegalArgumentException(
                "unknown resource type '" + resource.type() + "' in " + resource);
        }
        if (!type.supports(capability)) {
            throw new IllegalArgumentException(
                "capability '" + capability + "' not valid on resource type '"
                + type.name() + "' — valid caps are " + type.validCaps());
        }
        if (type.hasId() && resource.id() == null) {
            throw new IllegalArgumentException(
                "resource type '" + type.name() + "' requires an id: " + resource);
        }
        if (!type.hasId() && resource.id() != null) {
            throw new IllegalArgumentException(
                "resource type '" + type.name() + "' is type-only; id must not be set: " + resource);
        }
    }
}
