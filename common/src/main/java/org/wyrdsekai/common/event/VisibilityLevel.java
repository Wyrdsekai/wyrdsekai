package org.wyrdsekai.common.event;

/**
 * Visibility levels for world events (§2.1).
 * Controls which subscribers receive event notifications.
 *
 * <ul>
 *   <li>PUBLIC — visible to all room subscribers (speech, movement, room changes)</li>
 *   <li>DIRECTED — visible to specific entity (tells, direct messages)</li>
 *   <li>PRIVILEGED — visible to agents and system actors (security alerts, vitality updates)</li>
 *   <li>SYSTEM — visible only to system-level subscribers (heartbeats, internal state)</li>
 * </ul>
 *
 * Subscribers declare their maximum visibility level when subscribing.
 * Events with a higher level than the subscriber's are filtered out.
 */
public enum VisibilityLevel {
    /** Visible to everyone in the room. */
    PUBLIC(0),
    /** Visible to specific entity or entities. */
    DIRECTED(1),
    /** Visible to privileged subscribers (agents, wardens, system actors). */
    PRIVILEGED(2),
    /** Visible only to system-level subscribers. */
    SYSTEM(3);

    private final int level;

    VisibilityLevel(int level) {
        this.level = level;
    }

    /** Returns true if this level can see events at the given visibility. */
    public boolean canSee(VisibilityLevel eventVisibility) {
        return this.level >= eventVisibility.level;
    }

    /**
     * Default visibility for a given WorldEvent type.
     * Most events are PUBLIC. Override per-event when needed.
     */
    public static VisibilityLevel defaultFor(WorldEvent event) {
        return switch (event) {
            case WorldEvent.Said _ -> PUBLIC;
            case WorldEvent.EntityEntered _ -> PUBLIC;
            case WorldEvent.EntityLeft _ -> PUBLIC;
            case WorldEvent.ObjectTaken _ -> PUBLIC;
            case WorldEvent.ObjectDropped _ -> PUBLIC;
            case WorldEvent.ObjectUsed _ -> PUBLIC;
            case WorldEvent.ExitOpened _ -> PUBLIC;
            case WorldEvent.ExitClosed _ -> PUBLIC;
            case WorldEvent.DescriptionChanged _ -> PUBLIC;
            case WorldEvent.HintsUpdated _ -> PUBLIC;
            case WorldEvent.RoomCreated _ -> SYSTEM;
            case WorldEvent.ScriptTriggered _ -> PRIVILEGED;
            case WorldEvent.PropertyChanged _ -> PRIVILEGED;
            case WorldEvent.ObjectAdded _ -> PUBLIC;
            case WorldEvent.Whispered _ -> DIRECTED;
            case WorldEvent.Told _ -> DIRECTED;
            case WorldEvent.VitalitySuggested _ -> PRIVILEGED;
            case WorldEvent.Emoted _ -> PUBLIC;
            case WorldEvent.EntityTraveling _ -> PUBLIC;
            case WorldEvent.EntityReturned _ -> PUBLIC;
            case WorldEvent.PostureChanged _ -> PUBLIC;
            case WorldEvent.LookedAt _ -> PUBLIC;
            case WorldEvent.AmbientChanged _ -> PUBLIC;
        };
    }
}
